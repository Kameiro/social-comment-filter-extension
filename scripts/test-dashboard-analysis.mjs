import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/dashboard/dashboard.js", import.meta.url), "utf8");
const post = (id, title) => ({
  id: "douyin:" + id,
  platform: "douyin",
  url: "https://www.douyin.com/video/" + id,
  title,
  keyword: title.includes("北京") ? "北京地陪" : "上海地陪",
  keywords: [title.includes("北京") ? "北京地陪" : "上海地陪"],
  createTime: 1780000000
});
const alice = "https://www.douyin.com/user/alice";
const bob = "https://www.douyin.com/user/bob";
const charlie = "https://www.douyin.com/user/charlie";
const context = vm.createContext({
  state: {
    discoverySelected: new Set(),
    discoveryKeyword: "",
    data: {
      posts: [],
      discoveredPosts: [post("1", "北京女大地陪"), post("2", "上海地陪")],
      analysisScopes: [],
      users: [
        { profile: alice, nickname: "Alice", gender: "女", profileLocation: "北京·朝阳" },
        { profile: bob, nickname: "Bob", gender: "女", profileLocation: "北京" },
        { profile: charlie, nickname: "Charlie", gender: "男", profileLocation: "北京" }
      ],
      comments: [
        { id: "c1", postId: "douyin:1", profile: alice, nickname: "Alice", gender: "女", text: "北京可以，欢迎交流", likeCount: "8", replyCount: "2", collectedAt: 1000 },
        { id: "c2", postId: "douyin:1", profile: alice, nickname: "Alice", gender: "女", text: "周末有空", likeCount: "3", replyCount: "1", collectedAt: 2000 },
        { id: "c3", postId: "douyin:1", profile: bob, nickname: "Bob", gender: "女", text: "我只接纯绿地陪", likeCount: "5", replyCount: "0", collectedAt: 3000 },
        { id: "c4", postId: "douyin:1", profile: charlie, nickname: "Charlie", gender: "男", text: "北京可以", likeCount: "99", replyCount: "9", collectedAt: 4000 }
      ]
    }
  },
  formatCount: value => String(value || 0),
  formatDate: value => value ? "2026-10-02 10:00" : "",
  matchesQuery: () => true,
  escapeHtml: value => String(value ?? "").replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]),
  genderMarkup: value => "<span>" + value + "</span>",
  messageActionMarkup: () => '<button data-message-profile>私信</button>',
  emptyMarkup: () => "EMPTY"
});
const start = source.indexOf("  function normalizeListInput(");
const end = source.indexOf("  function emptyMarkup(");
vm.runInContext(source.slice(start, end) + "\nglobalThis.testApi = { analysisPostRecords, analysisUserRows, renderAnalysis };", context);

const scope = {
  id: "scope-1",
  filters: {
    includeKeywords: ["北京", "地陪"],
    excludeKeywords: [],
    region: "北京",
    platform: "douyin",
    genderFilter: "female",
    relativeDays: 0,
    excludePhrases: ["纯绿地陪"],
    selectedPostIds: []
  },
  userDecisions: {}
};
context.state.analysisScopeId = scope.id;
context.state.data.analysisScopes = [scope];
context.state.userByProfile = new Map(context.state.data.users.map(user => [user.profile, user]));
const posts = context.testApi.analysisPostRecords(scope);
assert.equal([...posts].map(item => item.id).join(","), "douyin:1", "analysis scope must keep only matching posts");
const users = context.testApi.analysisUserRows(scope);
const aliceRow = users.find(item => item.profile === alice);
const bobRow = users.find(item => item.profile === bob);
assert.equal(aliceRow.commentCount, 2);
assert.equal(aliceRow.postCount, 1);
assert.equal(aliceRow.totalLikeCount, 11);
assert.equal(aliceRow.totalReplyCount, 3);
assert.equal(aliceRow.status, "candidate");
assert.equal(bobRow.status, "excluded");
assert.equal(bobRow.matchedExcludePhrase, "纯绿地陪");
assert.equal(users.some(item => item.profile === charlie), false, "gender filter must exclude male users");
const markup = context.testApi.renderAnalysis();
assert.match(markup, /候选用户/);
assert.match(markup, /明确排除/);
assert.match(markup, /data-analysis-open-comments/);
assert.match(markup, /data-message-profile/);
console.log("Dashboard analysis passed: post scope, gender filter, cross-post aggregation and explicit exclusion.");
