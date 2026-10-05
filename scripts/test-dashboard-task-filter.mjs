import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/dashboard/dashboard.js", import.meta.url), "utf8");
const section = source.slice(source.indexOf("  function renderTaskFilter()"), source.indexOf("  function renderUsers()"));
const post = {
  id: "douyin:post",
  postNumber: 7,
  title: "测试帖子",
  url: "https://www.douyin.com/video/123",
  filterTasks: [
    { id: "task-1", taskNumber: 11, postId: "douyin:post", postNumber: 7, matchedCount: 1 },
    { id: "task-2", taskNumber: 12, postId: "douyin:post", postNumber: 7, matchedCount: 1 }
  ]
};
const taskSelect = { innerHTML: "", disabled: false, value: "" };
const context = vm.createContext({
  state: {
    postId: post.id,
    taskId: "task-1",
    query: "",
    data: {
      posts: [post],
      comments: [
        { postId: post.id, nickname: "任务一用户", text: "任务一评论", filterTaskIds: ["task-1"] },
        { postId: post.id, nickname: "任务二用户", text: "任务二评论", filterTaskIds: ["task-2"] },
        { postId: post.id, nickname: "历史用户", text: "历史评论" }
      ]
    },
    userByProfile: new Map()
  },
  $: selector => selector === "#taskFilter" ? taskSelect : null,
  matchesQuery: () => true,
  emptyMarkup: () => "EMPTY",
  escapeHtml: value => String(value ?? "").replace(/[&<>]/g, ""),
  genderMarkup: () => "",
  platformName: () => "抖音"
});
vm.runInContext(`${source.slice(source.indexOf("  function postNumberLabel("), source.indexOf("  function formatDate("))}
${section}\nglobalThis.testApi = { renderComments, renderTaskFilter };`, context);

context.testApi.renderTaskFilter();
assert.match(taskSelect.innerHTML, /任务 #11/);
assert.match(taskSelect.innerHTML, /任务 #12/);
assert.match(taskSelect.innerHTML, /帖子 #7/);
assert.equal(taskSelect.value, "task-1");

const taskMarkup = context.testApi.renderComments();
assert.match(taskMarkup, /任务一评论/);
assert.doesNotMatch(taskMarkup, /任务二评论/);
assert.doesNotMatch(taskMarkup, /历史评论/);

context.state.taskId = "__unassigned__";
const historyMarkup = context.testApi.renderComments();
assert.match(historyMarkup, /历史评论/);
assert.doesNotMatch(historyMarkup, /任务一评论/);
console.log("Dashboard task filter passed: comments stay within the selected task.");
