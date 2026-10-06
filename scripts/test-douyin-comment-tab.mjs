import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/content/platforms/douyin.js", import.meta.url), "utf8");
assert.match(source, /__dyCommentFilterInstalledV29/);
assert.match(source, /DY_START_BACKGROUND_SCAN_V3/);
const cleanSource = source.match(/  const clean = value =>[^;]+;/)[0];
const start = source.indexOf("  function isCommentTabLabel");
const end = source.indexOf("  function findScrollTargets", start);

class FakeElement {
  constructor(text, className = "") {
    this.innerText = text;
    this.textContent = text;
    this.className = className;
    this.tagName = "DIV";
    this.parentElement = null;
    this.attrs = {};
  }

  getAttribute(name) { return this.attrs[name] || null; }
  getBoundingClientRect() { return { width: 80, height: 32 }; }
}

const related = new FakeElement("相关推荐", "tab-item active");
const comments = new FakeElement("评论(723)", "tab-item");
const irrelevant = new FakeElement("评论区内容很多", "comment-row");
const elements = [related, comments, irrelevant];
const context = vm.createContext({
  document: { querySelectorAll: () => elements },
  window: {},
  getComputedStyle: () => ({ color: "rgb(80, 80, 80)", fontWeight: "600", backgroundColor: "", borderBottomColor: "", borderBottom: "0px none" }),
  console
});

vm.runInContext(`${cleanSource}\n${source.slice(start, end)}\nglobalThis.testApi = { findCommentTab, isCommentTabSelected };`, context);

const first = context.testApi.findCommentTab();
assert.equal(first.innerText, "评论(723)", "相关推荐 must not be selected as the comment tab");
assert.equal(context.testApi.isCommentTabSelected(first), false, "the inactive comment tab must be detected as inactive");

comments.className = "tab-item active";
assert.equal(context.testApi.isCommentTabSelected(context.testApi.findCommentTab()), true, "the active comment tab must be detected");
console.log("Douyin comment tab regression passed: related recommendations are excluded and comment tab state is detected.");
