import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/content/platforms/xhs.js", import.meta.url), "utf8");

function fixture() {
  let elapsed = 0;
  let packetListener;
  let tick = () => {};
  const threads = [];
  let domItems = [];
  const progress = [];
  const scroller = { scrollTop: 600, clientHeight: 400, scrollHeight: 1200 };
  const document = {
    scrollingElement: scroller,
    documentElement: scroller,
    querySelector: selector => /comments-container/.test(selector) ? {
      querySelectorAll: () => threads.map(thread => thread.button).filter(button => button.isConnected && /^展开/.test(button.textContent))
    } : null,
    querySelectorAll: selector => selector === ".comments-container .comment-item" ? domItems : []
  };
  const window = {
    addEventListener() {}, innerHeight: scroller.clientHeight,
    get scrollY() { return scroller.scrollTop; },
    scrollBy({ top }) { scroller.scrollTop = Math.min(scroller.scrollTop + top, scroller.scrollHeight - scroller.clientHeight); }
  };
  const context = vm.createContext({
    window, document, URL, location: { href: "https://www.xiaohongshu.com/explore/test", origin: "https://www.xiaohongshu.com" },
    Date: class extends Date { static now() { return elapsed; } },
    setTimeout(callback, ms) { elapsed += ms; tick(elapsed); callback(); },
    chrome: {
      storage: {
        local: { async get() { return { stopRequested: false }; }, set(value) { progress.push(value.scanProgress); } },
        onChanged: { addListener() {} }
      },
      runtime: { onMessage: { addListener() {} } }
    },
    __commentFilterApiV1: {
      listen(callback) { packetListener = callback; },
      extractPagination: payload => ({ hasMore: payload.data?.has_more ?? null, cursor: payload.data?.cursor || "" }),
      rowsFromPacket: packet => JSON.parse(packet.body).rows || []
    }
  });
  const exportPoint = "  chrome.runtime.onMessage.addListener";
  vm.runInContext(source.replace(exportPoint, `
    globalThis.testApi = { expandPendingReplies, canExpandReply, replyThreadKey, replyAttemptSignature,
      waitForReplyExpansion, replyThreadSignature, loadedComments,
      extractPostStats,
      pagination: () => ({ apiHasMore, latestApiCursor, lastApiResponseAt }),
      overrideRows: fn => { loadedComments = fn; },
      parseCommentElement, scanComments };
  ${exportPoint}`), context);
  function thread(id, count, click) {
    const replies = [];
    const button = {
      textContent: `展开 ${count} 条回复`, isConnected: true,
      closest: () => entry,
      scrollIntoView() { scroller.scrollTop = 0; },
      click() { click?.(entry); }
    };
    const entry = {
      id, replies, button,
      querySelector: () => ({ id: `comment-${id}` }),
      querySelectorAll: () => replies
    };
    threads.push(entry);
    return entry;
  }
  return {
    api: context.testApi, context, thread, scroller, progress,
    packet: packet => packetListener({ platform: "xhs", ...packet }),
    render(items) { domItems = items; },
    advance(ms) { elapsed += ms; },
    onTick(callback) { tick = callback; }
  };
}

{
  const f = fixture();
  f.advance(1);
  f.packet({ url: "https://edith.xiaohongshu.com/api/sns/web/v1/feed",
    body: JSON.stringify({ data: { items: [{ note_card: { note_id: "note-1", interact_info: {
      liked_count: 123, comment_count: 65, shared_count: 7, collected_count: 9
    } } }] } }) });
  const stats = f.api.extractPostStats({ data: { items: [{ note_card: { note_id: "note-1", interact_info: {
    liked_count: 123, comment_count: 65, shared_count: 7, collected_count: 9
  } } }] } });
  assert.deepEqual({
    diggCount: stats.diggCount,
    commentCount: stats.commentCount,
    shareCount: stats.shareCount,
    collectCount: stats.collectCount,
    statsSource: stats.statsSource
  }, { diggCount: 123, commentCount: 65, shareCount: 7, collectCount: 9, statsSource: "xhs-feed-api" });
}

{
  const f = fixture();
  let page = 0;
  f.thread("multi-page", 3, thread => {
    thread.replies.push({ id: `reply-${++page}` });
    thread.button.textContent = page < 3 ? `展开 ${3 - page} 条回复` : "收起回复";
  });
  const attempts = new Map();
  for (let i = 0; i < 3; i++) {
    assert.equal((await f.api.expandPendingReplies(0, 10, 0, attempts)).expanded, 1);
    assert.equal(f.scroller.scrollTop, 600, "restore the pagination scroll position");
  }
  assert.equal(page, 3);
  assert.equal((await f.api.expandPendingReplies(0, 10, 0, attempts)).clicked, 0);
}

{
  const f = fixture();
  const a = f.thread("first", 1);
  const b = f.thread("second", 1);
  assert.notEqual(f.api.replyThreadKey(a.button), f.api.replyThreadKey(b.button));
  f.onTick(() => { b.replies.push({ id: "unrelated-reply" }); });
  assert.equal((await f.api.waitForReplyExpansion(a.button, "", 600)).loaded, false,
    "another thread must not acknowledge this click");
}

{
  const f = fixture();
  const slow = f.thread("slow", 1);
  f.onTick(elapsed => { if (elapsed >= 4500 && !slow.replies.length) slow.replies.push({ id: "late-reply" }); });
  assert.equal((await f.api.expandPendingReplies(0, 1, 0, new Map())).expanded, 1);
}

{
  const f = fixture();
  let clicks = 0;
  const failed = f.thread("failed", 1, () => { clicks++; });
  const attempts = new Map();
  for (let i = 0; i < 4; i++) {
    await f.api.expandPendingReplies(0, 10, 0, attempts);
    f.advance(1100);
  }
  assert.equal(clicks, 3, "persistent failures have a bounded retry count");
  assert.equal(f.api.canExpandReply(failed.button, attempts), false);
  failed.replies.push({ id: "late-arrival" });
  assert.equal(f.api.canExpandReply(failed.button, attempts), true, "late progress re-enables the thread");
}

{
  const f = fixture();
  f.thread("stop", 1);
  f.onTick(() => { f.context.window.__xhsCommentFilterStopRequested = true; });
  assert.equal((await f.api.expandPendingReplies(0, 1, 0, new Map())).stopped, true);
  assert.equal(f.scroller.scrollTop, 600);
}

{
  const f = fixture();
  f.packet({ url: "https://edith.xiaohongshu.com/api/sns/web/v2/comment/page?note_id=test",
    body: JSON.stringify({ success: true, data: { has_more: true, cursor: "main-cursor" } }) });
  f.packet({ url: "https://edith.xiaohongshu.com/api/sns/web/v2/comment/sub/page?note_id=test",
    body: JSON.stringify({ success: true, data: { has_more: false, cursor: "reply-cursor" } }) });
  assert.equal(f.api.pagination().apiHasMore, true);
  assert.equal(f.api.pagination().latestApiCursor, "main-cursor");
}

{
  const f = fixture();
  const profile = "https://www.xiaohongshu.com/user/profile/user-id";
  const content = "回复 原作者 : 北京也不错";
  const anchor = { textContent: "test user", getAttribute: () => profile };
  const location = { textContent: "河北" };
  const date = { textContent: "6天前 河北", querySelector: () => location };
  const selectors = {
    ".content": { textContent: content }, ".date": date, ".name": anchor,
    ".like .count": { textContent: "12" }, ".reply .count": { textContent: "3" }
  };
  const item = {
    id: "comment-dom-id", textContent: `test user ${content} 6天前 河北 12 3`,
    querySelector: selector => selectors[selector],
    querySelectorAll: selector => selector === "a[href]" ? [anchor] : []
  };
  f.render([item, item]);
  f.packet({ url: "https://edith.xiaohongshu.com/api/sns/web/v2/comment/sub/page?note_id=test",
    body: JSON.stringify({ rows: [{ commentId: "dom-id", nickname: "test user", text: content, profile }] }) });
  const rows = f.api.loadedComments();
  assert.equal(rows.length, 1, "API and duplicate DOM instances use the same comment ID");
  assert.equal(rows[0].nickname, "test user");
  assert.equal(rows[0].text, content, "do not strip place names from reply text");
  assert.equal(rows[0].ipRegion, "河北");
  assert.equal(rows[0].profile, profile);
  assert.equal(rows[0].likeCount, "12");
  assert.equal(rows[0].replyCount, "3");
}

{
  const f = fixture();
  const rows = [{ commentId: "same-id", nickname: "user", text: "reply", _days: 1 }];
  f.api.overrideRows(() => rows);
  f.thread("final", 1, thread => {
    thread.replies.push({ id: "final-id" });
    thread.button.textContent = "收起回复";
    rows.push({ commentId: "last-id", nickname: "other", text: "final reply", _days: 1 });
  });
  const result = await f.api.scanComments({ scrollLimit: 1, relativeDays: 30, genderFilter: "all" });
  assert.equal(result.rows.length, 1, "do not expand replies when the API template is unavailable");
  assert.equal(result.remainingReplyGroups, 0);
  assert.equal(f.scroller.scrollTop, 600, "scan must not scroll when the API template is unavailable");
}

console.log("XHS reply tests passed: pagination, multi-page replies, retries, slow loading, stop, deduplication, final collection.");
