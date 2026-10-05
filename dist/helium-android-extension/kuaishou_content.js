(() => {
  if (window.__kuaishouCommentFilterInstalledV1) return;
  window.__kuaishouCommentFilterInstalledV1 = true;
  window.__kuaishouCommentFilterStopRequested = false;

  const clean = value => String(value ?? "").replace(/\s+/g, " ").trim();
  const apiRows = new Map();
  let apiHasMore = true;
  let lastApiResponseAt = 0;
  const timePattern = /刚刚|\d+\s*(?:秒|分钟|小时|天|周|月|年)前|昨天|今天|20\d{2}[-/.年]\d{1,2}[-/.月]\d{1,2}/;
  const regionPattern = /北京|天津|上海|重庆|河北|山西|辽宁|吉林|黑龙江|江苏|浙江|安徽|福建|江西|山东|河南|湖北|湖南|广东|海南|四川|贵州|云南|陕西|甘肃|青海|台湾|内蒙古|广西|西藏|宁夏|新疆|香港|澳门|美国|英国|日本|韩国|加拿大|澳大利亚|新加坡|马来西亚|泰国|越南|菲律宾|西班牙/;

  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  const safeJson = value => { try { return JSON.parse(value || ""); } catch { return null; } };

  function splitTerms(value) {
    return clean(value).split(/[\n,，、;；|｜\s]+/).map(item => item.trim()).filter(Boolean);
  }

  function parseTime(text) {
    const value = clean(text);
    const now = new Date();
    let match = value.match(/(\d+)\s*分钟前/);
    if (match) now.setMinutes(now.getMinutes() - Number(match[1]));
    else if ((match = value.match(/(\d+)\s*小时前/))) now.setHours(now.getHours() - Number(match[1]));
    else if ((match = value.match(/(\d+)\s*天前/))) now.setDate(now.getDate() - Number(match[1]));
    else if (/昨天/.test(value)) now.setDate(now.getDate() - 1);
    else if (!/刚刚|今天/.test(value)) {
      const absolute = value.match(/(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})/);
      if (absolute) return { date: new Date(`${absolute[1]}-${absolute[2]}-${absolute[3]}T12:00:00+08:00`), text: absolute[0] };
      return { date: null, text: "" };
    }
    return { date: now, text: value.match(/刚刚|今天|昨天|\d+\s*(?:分钟|小时|天)前/)?.[0] || "" };
  }

  function rowKey(row) {
    return globalThis.CommentFilterPostUtils?.strictCommentKey?.(row, row.postUrl || location.href) ||
      (row.commentId || row.cid ? `kuaishou:comment:${row.commentId || row.cid}` : "") ||
      [row.nickname, row.text].join("||");
  }

  function installApiListener() {
    const api = globalThis.__commentFilterApiV1;
    if (!api?.listen || window.__kuaishouCommentFilterApiListenerV1) return;
    window.__kuaishouCommentFilterApiListenerV1 = true;
    api.listen(packet => {
      const pagination = api.extractPagination?.(safeJson(packet.body));
      if (pagination?.hasMore !== null && pagination?.hasMore !== undefined) apiHasMore = pagination.hasMore;
      lastApiResponseAt = Date.now();
      for (const row of api.rowsFromPacket(packet, "kuaishou", location.href)) apiRows.set(rowKey(row), row);
    });
  }

  function findCommentItems() {
    const selectors = [
      "[class*='comment-item']", "[class*='commentItem']", "[class*='comment-list'] > div",
      "[data-testid*='comment']", "[data-e2e*='comment']"
    ];
    const items = selectors.flatMap(selector => [...document.querySelectorAll(selector)]);
    return [...new Set(items)].filter(element => {
      const text = clean(element.innerText || element.textContent);
      return text.length > 5 && text.length < 1600 && timePattern.test(text);
    });
  }

  function parseDomItem(element, postUrl) {
    const value = clean(element.innerText || element.textContent);
    const time = parseTime(value);
    if (!time.text) return null;
    const lines = value.split(/\s+/).filter(Boolean);
    const timeIndex = lines.findIndex(item => timePattern.test(item));
    const nickname = lines[0] || "未知昵称";
    const beforeTime = lines.slice(1, timeIndex > 1 ? timeIndex : lines.length).join(" ");
    const text = clean(beforeTime.replace(/(?:赞|回复|展开\d+条回复|点赞)\s*\d*/g, ""));
    if (!text) return null;
    const profile = [...element.querySelectorAll("a[href]")].map(a => a.href).find(href => /\/profile\//i.test(href)) || "";
    const likes = value.match(/(?:赞|点赞)\s*(\d+(?:\.\d+)?(?:万|w)?)/i)?.[1] || "0";
    const replies = value.match(/(?:展开\s*)?(\d+)\s*条回复/)?.[1] || "0";
    return {
      nickname, postUrl, gender: "未知", profile, possibleProfile: "", ipRegion: value.match(regionPattern)?.[0] || "",
      dateText: time.date ? time.date.toISOString().slice(0, 10) : "日期未识别", rawTimeText: time.text,
      likeCount: likes, replyCount: replies, text, _days: time.date ? Math.floor((Date.now() - time.date.getTime()) / 86400000) : Infinity
    };
  }

  function loadedComments(postUrl) {
    const map = new Map();
    for (const row of apiRows.values()) map.set(rowKey(row), { ...row, postUrl });
    for (const item of findCommentItems()) {
      const row = parseDomItem(item, postUrl);
      if (!row) continue;
      const old = map.get(rowKey(row));
      map.set(rowKey(row), { ...old, ...row, likeCount: row.likeCount !== "0" ? row.likeCount : old?.likeCount || "0", replyCount: row.replyCount !== "0" ? row.replyCount : old?.replyCount || "0" });
    }
    return [...map.values()];
  }

  function passFilters(row, filters) {
    const keywords = splitTerms(filters.keyword);
    const excluded = splitTerms(filters.excludeKeyword);
    const regions = splitTerms(filters.region);
    const text = `${row.nickname} ${row.text} ${row.ipRegion}`;
    if (excluded.some(term => text.includes(term))) return false;
    const keywordOk = !keywords.length || keywords.some(term => text.includes(term));
    const regionOk = !regions.length || regions.some(term => row.ipRegion.includes(term));
    if (filters.matchMode === "any" && (keywords.length || regions.length) && !((keywords.length && keywordOk) || (regions.length && regionOk))) return false;
    if (filters.matchMode !== "any" && (!keywordOk || !regionOk)) return false;
    return !Number.isFinite(row._days) || row._days <= Number(filters.relativeDays || 30);
  }

  function findScrollTarget() {
    const candidates = [...document.querySelectorAll("[class*='comment'], [class*='Comment'], [class*='drawer'], main")]
      .filter(element => element.scrollHeight > element.clientHeight + 40 && getComputedStyle(element).overflowY !== "visible");
    return candidates.sort((a, b) => b.clientHeight - a.clientHeight)[0] || document.scrollingElement;
  }

  async function shouldStop() {
    if (window.__kuaishouCommentFilterStopRequested) return true;
    return Boolean((await chrome.storage.local.get({ stopRequested: false })).stopRequested);
  }

  async function scanComments(filters) {
    installApiListener();
    const postInfo = globalThis.CommentFilterPostUtils.getPostInfo(filters.postUrl || location.href);
    const postUrl = postInfo.url || filters.postUrl || location.href;
    const rows = new Map();
    const stopped = await shouldStop();
    // Kuaishou does not yet have a replayable cursor contract in this
    // extension. Keep it deterministic: collect only what is already loaded
    // and never move the user's page as a fallback.
    const loaded = loadedComments(postUrl);
    for (const row of loaded) if (passFilters(row, filters)) rows.set(rowKey(row), row);
    const resultRows = [...rows.values()].map(({ _days, ...row }) => ({ ...row, postUrl, sourcePostUrl: postInfo.sourceUrl || "", postTitle: postInfo.title || "" }));
    chrome.storage.local.set({ scanProgress: { phase: stopped ? "stopped" : "completed", message: stopped ? `已停止：当前已加载内容命中 ${resultRows.length} 条` : `爬取完成：快手暂未支持接口分页，仅处理当前已加载内容，共 ${resultRows.length} 条，未继续滚动。`, current: 0, total: 0, matched: resultRows.length, updatedAt: Date.now() } });
    return { rows: resultRows, postUrl, sourceUrl: postInfo.sourceUrl || "", title: postInfo.title || "", stopped, paginationWarning: "快手暂未支持接口分页，仅处理当前已加载内容。" };
  }

  function diagnoseContainer() {
    const target = findScrollTarget();
    return { url: location.href, loadedCommentCount: loadedComments(location.href).length, scrollTarget: { tag: target?.tagName?.toLowerCase() || "window", cls: String(target?.className || ""), scrollTop: Math.round(target?.scrollTop || window.scrollY), clientHeight: target?.clientHeight || window.innerHeight, scrollHeight: target?.scrollHeight || document.documentElement.scrollHeight } };
  }

  async function openCommentsView() {
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline) {
      const items = findCommentItems();
      const target = findScrollTarget();
      if (items.length || target?.scrollHeight > target?.clientHeight + 40) {
        if (target && target !== document.scrollingElement) target.scrollTop = 0;
        items[0]?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
        return { ok: true };
      }
      await sleep(250);
    }
    return { ok: false, error: "评论区加载超时，请确认帖子页面已打开后重试。" };
  }

  chrome.storage.onChanged.addListener((changes, area) => { if (area === "local" && changes.stopRequested?.newValue) window.__kuaishouCommentFilterStopRequested = true; });
  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === "KS_OPEN_COMMENTS_V1") { openCommentsView().then(sendResponse).catch(error => sendResponse({ ok: false, error: error.message || "无法定位评论区。" })); return true; }
    if (message?.type === "KS_STOP_SCAN_V1") { window.__kuaishouCommentFilterStopRequested = true; chrome.storage.local.set({ stopRequested: true }); sendResponse({ ok: true }); return false; }
    if (message?.type === "KS_DIAGNOSE_CONTAINER_V1") { sendResponse(diagnoseContainer()); return false; }
    if (message?.type === "KS_START_BACKGROUND_SCAN_V1") {
      window.__kuaishouCommentFilterStopRequested = false;
      sleep(Number(message.filters?.initialDelay || 0)).then(() => scanComments({ ...message.filters, backgroundTask: true })).then(result => chrome.runtime.sendMessage({ type: "COMMENT_SCAN_PAGE_DONE", taskId: message.taskId, result })).catch(error => chrome.runtime.sendMessage({ type: "COMMENT_SCAN_PAGE_DONE", taskId: message.taskId, result: { error: error.message || "扫描失败" } }));
      sendResponse({ ok: true, started: true }); return false;
    }
    if (message?.type === "KS_SCAN_COMMENTS_V1") { scanComments(message.filters || {}).then(sendResponse).catch(error => sendResponse({ error: error.message || "扫描失败" })); return true; }
    return false;
  });
  installApiListener();
})();
