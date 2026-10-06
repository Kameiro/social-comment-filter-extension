import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/dashboard/dashboard.js", import.meta.url), "utf8");
const discoveredPost = {
  id: "douyin:123",
  platform: "douyin",
  url: "https://www.douyin.com/video/123",
  title: "北京陪拍测试",
  nickname: "测试作者",
  keyword: "北京陪拍",
  keywords: ["北京陪拍"],
  diggCount: 321,
  commentCount: 47,
  shareCount: 8,
  collectCount: 12,
  statsSource: "douyin-search",
  statsCapturedAt: Date.now(),
  createTime: 1780000000,
  discoveredAt: Date.now() - 5000,
  lastSeenAt: Date.now()
};
const trackedPost = { id: "douyin:123", commentCount: 4, lastScannedAt: Date.now() };
const legacyPostWithoutStats = {
  id: "douyin:456",
  platform: "douyin",
  url: "https://www.douyin.com/video/456",
  title: "历史记录",
  nickname: "旧作者",
  commentCount: 65,
  diggCount: 0,
  shareCount: 0,
  collectCount: 0,
  discoveredAt: Date.now() - 1000,
  lastSeenAt: Date.now() - 1000
};
const context = vm.createContext({
  state: {
    query: "",
    discoverySelected: new Set(["douyin:123"]),
    discoveryPlatform: "douyin",
    discoveryKeyword: "北京陪拍",
    discoverySort: "latest",
    discoveryStatusFilter: "all",
    discoveryLimit: 50,
    discoveryPages: 20,
    discoveryBusy: false,
    discoveryScanBusy: false,
    discoveryRuntime: {
      status: "completed",
      page: 2,
      maxPages: 20,
      responses: 0,
      items: 0,
      stopReason: "no_search_response"
    },
    data: {
      posts: [trackedPost],
      comments: [{ postId: "douyin:123", profile: "https://www.douyin.com/user/1" }],
      discoveredPosts: [discoveredPost, legacyPostWithoutStats],
      analysisScopes: [{ id: "scope-1", name: "北京目标", filters: { selectedPostIds: ["douyin:123"] } }]
    }
  },
  CommentFilterDatabase: { postIdentity: url => String(url).includes("/456") ? "douyin:456" : "douyin:123" },
  platformName: () => "抖音",
  matchesQuery: () => true,
  formatDate: () => "2026/06/01 10:00",
  escapeHtml: value => String(value ?? "").replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]),
  emptyMarkup: () => "EMPTY"
});
const start = source.indexOf("  function formatCount(");
const end = source.indexOf("  function emptyMarkup(");
vm.runInContext(`${source.slice(start, end)}\nglobalThis.testApi = { renderDiscover, discoveryKey, platformMetricText, filteredDiscoveredPosts };`, context);

const markup = context.testApi.renderDiscover();
assert.match(markup, /北京陪拍测试/);
assert.match(markup, /已获取评论/);
assert.match(markup, /4 条评论/);
assert.match(markup, /赞 321/);
assert.match(markup, /评 47/);
assert.match(markup, /赞 —/);
assert.equal(context.testApi.platformMetricText(legacyPostWithoutStats, "commentCount"), "—");
assert.ok(markup.indexOf("北京陪拍测试") < markup.indexOf("历史记录"), "known publish time should sort before unknown publish time");
assert.match(markup, /data-discover-select="douyin:123" checked/);
assert.match(markup, /分析范围/);
assert.match(markup, /已加入 1 个范围/);
assert.match(markup, /data-discover-status/);
assert.match(markup, /创建用户分析范围/);
assert.match(markup, /最近一次发现已结束/);
assert.match(markup, /未收到平台搜索接口响应/);
context.state.discoveryStatusFilter = "pending";
assert.equal(JSON.stringify(context.testApi.filteredDiscoveredPosts().map(post => post.id)), JSON.stringify(["douyin:456"]));
context.state.discoveryStatusFilter = "done";
assert.equal(JSON.stringify(context.testApi.filteredDiscoveredPosts().map(post => post.id)), JSON.stringify(["douyin:123"]));
assert.equal(context.testApi.discoveryKey({ platform: "douyin", id: "123" }), "douyin:123");
assert.equal(context.testApi.discoveryKey({ platform: "douyin", id: "douyin:123" }), "douyin:123");
console.log("Dashboard discovery passed: aggregate row, fetched status and stable selection key.");
