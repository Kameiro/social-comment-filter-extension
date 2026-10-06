import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/data/comment_database.js", import.meta.url), "utf8");
const start = source.indexOf("  function normalizeDiscoveredPost(");
const end = source.indexOf("  async function upsertDiscoveredPosts(");
const context = vm.createContext({
  CommentFilterPostUtils: {
    getPostInfo: () => ({ platform: "douyin", id: "123", url: "https://www.douyin.com/video/123" })
  },
  platformFromUrl: () => "douyin",
  postIdentity: () => "douyin:123"
});
vm.runInContext(`${source.slice(start, end)}\nglobalThis.testApi = { normalizeDiscoveredPost, mergeDiscoveredPost };`, context);

const record = context.testApi.normalizeDiscoveredPost({
  id: "douyin:123",
  platform: "douyin",
  url: "https://www.douyin.com/video/123",
  keyword: "北京",
  title: "测试"
});
assert.equal(record.id, "douyin:123");
assert.deepEqual(Array.from(record.keywords), ["北京"]);
assert.equal(record.url, "https://www.douyin.com/video/123");

const older = context.testApi.normalizeDiscoveredPost({
  id: "123",
  platform: "douyin",
  url: "https://www.douyin.com/video/123?from=search",
  title: "旧标题",
  keyword: "北京",
  statsSource: "douyin-search",
  statsCapturedAt: 100,
  diggCount: 10,
  commentCount: 20,
  discoveredAt: 100,
  lastSeenAt: 100
});
const newer = context.testApi.normalizeDiscoveredPost({
  id: "douyin:123",
  platform: "douyin",
  url: "https://www.douyin.com/video/123",
  title: "新标题",
  keyword: "陪拍",
  statsSource: "douyin-post-api",
  statsCapturedAt: 200,
  diggCount: 30,
  commentCount: 40,
  discoveredAt: 200,
  lastSeenAt: 300
});
const merged = context.testApi.mergeDiscoveredPost(older, newer);
assert.equal(merged.id, "douyin:123");
assert.equal(merged.title, "新标题");
assert.deepEqual(Array.from(merged.keywords).sort(), ["北京", "陪拍"]);
assert.equal(merged.diggCount, 30);
assert.equal(merged.commentCount, 40);
assert.equal(merged.discoveredAt, 100);
assert.equal(merged.lastSeenAt, 300);
console.log("Discovered post normalization passed: canonical IDs, duplicate merge and latest snapshot.");
