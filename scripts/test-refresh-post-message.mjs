import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/background/background.js", import.meta.url), "utf8");
const section = (start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
const state = { scanTask: null, profileTask: null, scanProgress: null, stopRequested: false, nextTaskNumber: 4 };
let listener;
let launchedTask;
let finishedTask;

const context = vm.createContext({
  Date,
  Math,
  URL,
  console,
  structuredClone,
  setTimeout,
  clearTimeout,
  startingScanTask: false,
  isScanActive: task => task?.status === "running" || task?.status === "waiting-verification",
  SCAN_WATCHDOG_MS: 90000,
  SCAN_SETTING_DEFAULTS: {
    keyword: "眼线", excludeKeyword: "", region: "北京", matchMode: "all", relativeDays: 30,
    scrollLimit: 0, genderFilter: "all", targetGenderCount: 0
  },
  chrome: {
    runtime: { onMessage: { addListener(fn) { listener = fn; } } },
    tabs: { get: async () => { throw new Error("tab does not exist"); } },
    storage: {
      local: {
        get: async defaults => ({ ...defaults, ...structuredClone(state) }),
        set: async values => Object.assign(state, structuredClone(values))
      }
    }
  },
  CommentFilterDatabase: { ensureLegacyMigration: async () => {} },
  recoverInterruptedScanTask: async () => {},
  normalizeLegacyStorageRows: async () => [],
  allocateTaskNumber: async () => 5,
  openNextBatchPage: async task => {
    launchedTask = task;
    task.activeTabId = 91;
    await context.chrome.storage.local.set({ scanTask: task });
  },
  startCurrentPage: async () => {},
  finishPage: async (taskId, result) => { finishedTask = { taskId, result }; }
});

vm.runInContext(`
${section("function platformFromUrl(", "function messageType(")}
${section("function normalizePostUrl(", "const directMessageInFlight")}
${section("async function beginTask(", "async function finalizeTask(")}
${section("chrome.runtime.onMessage.addListener(", "\n\nrecoverInterruptedScanTask")}
`, context);

const postUrl = "https://www.douyin.com/jingxuan?modal_id=7656263405896011035";
let response;
const filters = { keyword: "深圳", region: "广东", genderFilter: "female" };
const keepAlive = listener({ type: "REFRESH_POST_DATA", postUrl, filters }, {}, value => { response = value; });

assert.equal(keepAlive, true, "refresh message must keep the runtime event alive");
await new Promise(resolve => setImmediate(resolve));
assert.equal(response?.ok, true, "refresh message must acknowledge a started task");
assert.equal(response.task.status, "running");
assert.equal(response.task.filters.postUrls, postUrl);
assert.equal(response.task.filters.scanMode, "replace");
assert.equal(response.task.replacePostUrl, postUrl);
assert.equal(response.task.urls[0], postUrl);
assert.equal(launchedTask.id, response.task.id, "the refresh task must launch the parsed post URL");
assert.equal(state.scanTask.activeTabId, 91);

response = undefined;
const completionKeepAlive = listener({ type: "COMMENT_SCAN_PAGE_DONE", taskId: response?.task?.id || launchedTask.id, result: { rows: [] } }, {}, value => { response = value; });
assert.equal(completionKeepAlive, true, "scan completion must keep the runtime event alive while saving");
await new Promise(resolve => setImmediate(resolve));
assert.equal(finishedTask.taskId, launchedTask.id);
assert.equal(finishedTask.result.rows.length, 0);
assert.equal(response?.ok, true);
console.log("Refresh post message regression passed: dashboard refresh reaches beginTask and launches the post.");
