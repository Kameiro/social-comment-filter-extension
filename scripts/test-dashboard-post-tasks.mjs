import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/dashboard/dashboard.js", import.meta.url), "utf8");
const section = (start, end) => source.slice(source.indexOf(start), source.indexOf(end));
const filters = { keyword: "北京", region: "北京,河北", genderFilter: "female", matchMode: "all" };
const post = {
  id: "douyin:post", postNumber: 7, url: "https://www.douyin.com/video/123", title: "测试帖子", platform: "douyin",
  commentCount: 12, filterTasks: [{ id: "task-1", status: "completed", matchedCount: 4, completedAt: Date.now(), filters }]
};
let message;
const context = vm.createContext({
  $: () => ({ textContent: "" }),
  state: { data: { posts: [post] }, openTaskPosts: new Set([post.id]) },
  matchesQuery: () => true,
  emptyMarkup: () => "",
  escapeHtml: value => String(value ?? "").replaceAll('"', "&quot;"),
  platformName: () => "抖音",
  formatDate: () => "今天",
  chrome: { storage: { local: { get: async () => ({ scanTask: null, profileTask: null }) } }, runtime: { sendMessage: async value => { message = value; return { ok: true, task: { id: "new-task" } }; } } },
  getLatestScanSettings: async () => ({ keyword: "其他" }),
  waitForScanTask: async () => ({ replacementSucceeded: true }),
  refresh: async () => {},
  setNotice: () => {}
});
vm.runInContext(`${source.slice(source.indexOf("  function postNumberLabel("), source.indexOf("  function genderMarkup("))}
${section("  async function refreshPost(", "  async function deletePost(")}
globalThis.testApi = { renderPosts, refreshPost };`, context);

const markup = context.testApi.renderPosts();
assert.match(markup, /data-task-post="douyin:post" open/);
assert.match(markup, /帖子 #7/);
assert.match(markup, /data-refresh-task="task-1"/);
assert.match(markup, /data-view-task="task-1"/);
assert.match(markup, /data-delete-task="task-1"/);
assert.match(markup, /最近：关键词 北京 · 地区 北京,河北 · 女性 · 命中 4 条/);
await context.testApi.refreshPost(post.url, filters);
assert.equal(message.type, "REFRESH_POST_DATA");
assert.equal(message.filters, filters);
assert.equal(message.postUrl, post.url);
console.log("Dashboard post tasks passed: expanded history, latest summary, saved-filter refresh.");
