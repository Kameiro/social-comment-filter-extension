import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/content/common/post_utils.js", import.meta.url), "utf8");
const context = vm.createContext({ URL });
vm.runInContext(source, context);

const key = context.CommentFilterPostUtils.strictCommentKey;
const postA = "https://www.douyin.com/jingxuan?modal_id=7656263405896011035&from=search";
const postB = "https://www.douyin.com/video/7656263405896011035";

const first = key({
  profile: "https://www.douyin.com/user/demo?from_tab_name=main",
  nickname: "半绿来个🐻大的",
  rawTimeText: "1周前",
  ipRegion: "北京",
  commentId: "api-id-1",
  text: "来个半绿🐻大的"
}, postA);
const sameComment = key({
  profile: "https://www.douyin.com/user/demo?from_tab_name=search",
  nickname: "半绿来个🐻大的",
  rawTimeText: "7天前",
  ipRegion: "河北",
  commentId: "api-id-2",
  text: "来个半绿🐻大的"
}, postB);
const reorderedComment = key({
  profile: "https://www.douyin.com/user/demo",
  nickname: "半绿来个🐻大的",
  text: "半绿来个🐻大的"
}, postB);
const otherUser = key({
  profile: "https://www.douyin.com/user/other",
  nickname: "半绿来个🐻大的",
  text: "来个半绿🐻大的"
}, postB);

assert.equal(first, sameComment, "same post/user/text must merge despite time, region and API id changes");
assert.notEqual(first, reorderedComment, "different word order must remain a separate comment");
assert.notEqual(first, otherUser, "different users must remain separate comments");

console.log("Strict dedupe tests passed: exact text only, stable across capture metadata changes.");
