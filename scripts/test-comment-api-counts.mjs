import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/content/common/comment_api.js", import.meta.url), "utf8");
const context = vm.createContext({});
vm.runInContext(source, context);
const api = context.__commentFilterApiV1;

const rows = (platform, object) => api.rowsFromPacket({
  platform,
  url: platform === "douyin"
    ? "https://www.douyin.com/aweme/v1/web/comment/list/?aweme_id=123"
    : "https://edith.xiaohongshu.com/api/sns/web/v2/comment/page?note_id=123",
  body: JSON.stringify({ comments: [object], data: { comments: [object] } })
}, platform, `https://${platform === "douyin" ? "www.douyin.com/note/123" : "www.xiaohongshu.com/explore/123"}`);

{
  const [row] = rows("douyin", {
    cid: "comment-1",
    text: "北京可以",
    create_time: 1791020000,
    digg_count: 0,
    reply_comment_total: "0",
    statistics: { diggCount: 12, replyCount: 3 },
    user: { nickname: "用户一", sec_uid: "sec-1" }
  });
  assert.equal(row.likeCount, "12", "a zero compatibility field must not hide statistics.diggCount");
  assert.equal(row.replyCount, "3", "a zero compatibility field must not hide statistics.replyCount");
}

{
  const [row] = rows("douyin", {
    cid: "comment-2",
    text: "深圳可以",
    create_time: 1791020000,
    digg_count: 0,
    reply_comment_total: 0,
    comment_info: { likeCount: "8", reply_comment_count: "2" },
    user: { nickname: "用户二", sec_uid: "sec-2" }
  });
  assert.equal(row.likeCount, "8");
  assert.equal(row.replyCount, "2");
}

{
  const [row] = rows("xhs", {
    comment_id: "comment-3",
    content: "周末有空",
    create_time: 1791020000,
    liked_count: 0,
    reply_count: 0,
    interact_info: { liked_count: 6, replyCount: 1 },
    user_info: { nickname: "用户三", user_id: "xhs-3" },
    ip_location: "北京"
  });
  assert.equal(row.likeCount, "6");
  assert.equal(row.replyCount, "1");
}

{
  const [row] = rows("douyin", {
    cid: "comment-4",
    text: "确实",
    create_time: 1791020000,
    digg_count: 0,
    reply_comment_total: 0,
    user: { nickname: "用户四", sec_uid: "sec-4" }
  });
  assert.equal(row.likeCount, "0", "real zero likes must remain zero");
  assert.equal(row.replyCount, "0", "real zero replies must remain zero");
}

console.log("Comment API count tests passed: nested fields, zero placeholders, XHS fields, and real zeros.");
