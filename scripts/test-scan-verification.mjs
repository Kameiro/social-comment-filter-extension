import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const apiSource = await readFile(new URL("../extension/content/common/comment_api.js", import.meta.url), "utf8");
const listeners = new Map();
let pageText = "";
let challengeNodes = [];
const context = vm.createContext({
  location: { href: "https://www.douyin.com/video/1" },
  document: { body: { get innerText() { return pageText; } }, querySelectorAll: () => challengeNodes },
  getComputedStyle: node => node.style || { display: "block", visibility: "visible", opacity: "1" },
  window: {
    addEventListener(type, listener) { listeners.set(type, listener); },
    removeEventListener() {},
    dispatchEvent() {}
  },
  CustomEvent,
  setTimeout,
  clearTimeout,
  Date,
  Math,
  console
});
vm.runInContext(apiSource, context);
const api = context.__commentFilterApiV1;
assert.equal(api.pageCounterText(12, 0), "第 12 页", "unlimited pagination should not expose the internal safety cap");
assert.equal(api.pageCounterText(12, 100), "第 12/100 页", "a configured page limit should remain visible");
assert.equal(api.isVerificationPacket({ status: 403, body: "forbidden" }), false, "bare 403 must not be treated as verification");
assert.equal(api.isVerificationPacket({ status: 200, body: '{"message":"captcha challenge"}' }), true, "captcha body must pause for verification");
pageText = "请完成安全验证";
assert.equal(api.isVerificationPacket({ status: 403, body: "forbidden" }), true, "403 plus visible challenge must pause for verification");
assert.equal(api.isVerificationPage(), true, "visible verification text must be detected");
pageText = "普通帖子页面";
challengeNodes = [{
  getBoundingClientRect: () => ({ width: 12, height: 12 }),
  getAttribute: name => name === "class" ? "author-verify-badge" : "",
  innerText: ""
}];
assert.equal(api.isVerificationPage(), false, "ordinary verification badge must not be treated as captcha");
challengeNodes = [{
  getBoundingClientRect: () => ({ width: 320, height: 180 }),
  getAttribute: name => name === "class" ? "captcha-container" : "",
  innerText: ""
}];
assert.equal(api.isVerificationPage(), true, "visible captcha container must be detected");

const backgroundSource = await readFile(new URL("../extension/background/background.js", import.meta.url), "utf8");
const state = {
  scanTask: {
    id: "verification-task",
    taskNumber: 12,
    status: "running",
    activeTabId: 55,
    closeActiveTab: true,
    urls: ["https://www.douyin.com/video/1"],
    currentIndex: 0,
    totalPages: 1,
    filters: { scrollLimit: 0 },
    newRows: [],
    baseRows: [],
    errors: [],
    startedAt: Date.now()
  },
  stopRequested: false,
  rows: []
};
let removed = 0;
const backgroundContext = vm.createContext({
  Date,
  Math,
  console,
  structuredClone,
  URL,
  setTimeout,
  clearTimeout,
  closingScanTabs: new Set(),
  scanStopTimers: new Map(),
  scanWatchdogTimers: new Map(),
  isScanActive: task => task?.status === "running" || task?.status === "waiting-verification",
  chrome: {
    tabs: {
      get: async () => ({ id: 55, windowId: 9 }),
      update: async () => {},
      remove: async () => { removed += 1; },
      onUpdated: { addListener() {}, removeListener() {} },
      onRemoved: { addListener() {}, removeListener() {} },
      sendMessage: async () => ({ ok: true })
    },
    windows: { update: async () => {} },
    storage: { local: {
      get: async defaults => ({ ...defaults, ...structuredClone(state) }),
      set: async values => Object.assign(state, structuredClone(values))
    } }
  },
  CommentFilterDatabase: {
    ensureLegacyMigration: async () => {},
    upsertRows: async () => {},
    replacePostData: async () => {},
    postIdentity: value => value
  },
  CommentFilterPostUtils: { getPostInfo: value => ({ url: value }) },
  formatRows: () => "",
  mergeRows: (left, right) => [...left, ...right],
  autoSyncCloudSnapshot: async () => {}
});
const section = (start, end) => backgroundSource.slice(backgroundSource.indexOf(start), backgroundSource.indexOf(end, backgroundSource.indexOf(start)));
vm.runInContext(`${section("async function finalizeTask(", "async function startAutomaticProfileEnrichment(")}
${section("function buildFilterTaskRecord(", "async function finishPage(")}
${section("async function finishPage(", "function scheduleScanWatchdog(")}
globalThis.finish = finishPage;`, backgroundContext);

await backgroundContext.finish("verification-task", {
  waitingForVerification: true,
  verificationMessage: "平台触发了人机验证，请完成当前页面验证后继续抓取。",
  rows: [{ nickname: "已抓到", text: "部分评论", profile: "" }],
  postUrl: "https://www.douyin.com/video/1",
  pageCount: 2
});
assert.equal(state.scanTask.status, "waiting-verification", "verification must pause the scan");
assert.equal(state.scanTask.activeTabId, 55, "verification must keep the active tab id");
assert.equal(removed, 0, "verification must never close the task tab");
assert.match(state.scanTask.message, /验证/);
console.log("Scan verification regression passed: verification pauses and preserves the task page.");
