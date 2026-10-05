import assert from "node:assert/strict";
import {
  canonicalDouyinPostId,
  canonicalXhsPostId,
  mergeSearchItem,
  normalizeDouyinSearchItem,
  normalizeXhsSearchItem
} from "../services/playwright-worker/discovery_utils.mjs";

const douyin = normalizeDouyinSearchItem({
  aweme_info: {
    aweme_id: "123",
    desc: "北京陪拍",
    create_time: 1780000000,
    author: { nickname: "作者", sec_uid: "sec-1" },
    statistics: { digg_count: 12, comment_count: 3 }
  }
});
assert.equal(douyin.id, "");

const douyinItem = normalizeDouyinSearchItem({
  aweme_id: "123",
  desc: "北京陪拍",
  create_time: 1780000000,
  author: { nickname: "作者", sec_uid: "sec-1" },
  statistics: { digg_count: 12, comment_count: 3 }
});
assert.equal(douyinItem.id, "123");
assert.equal(douyinItem.authorSecUid, "sec-1");
assert.equal(canonicalDouyinPostId({ url: "https://www.douyin.com/video/456" }), "456");

const xhs = normalizeXhsSearchItem({
  id: "note-1",
  xsec_token: "token-1",
  xsec_source: "pc_search",
  note_card: {
    display_title: "北京地陪",
    time: 1780000000000,
    user: { user_id: "user-1", nickname: "小红书作者" },
    interact_info: { liked_count: "21", comment_count: "5" }
  }
});
assert.equal(xhs.id, "note-1");
assert.match(xhs.url, /xsec_token=token-1/);
assert.equal(xhs.authorId, "user-1");
assert.equal(xhs.diggCount, 21);
assert.equal(canonicalXhsPostId({ url: "https://www.xiaohongshu.com/explore/note-2?xsec_token=t" }), "note-2");

const merged = mergeSearchItem({ id: "123", title: "旧标题", diggCount: 0 }, { id: "123", title: "新标题", diggCount: 99 });
assert.equal(merged.title, "新标题");
assert.equal(merged.diggCount, 99);
console.log("Discovery worker helpers passed: stable IDs, XHS token retention, author identity, and merge behavior.");
