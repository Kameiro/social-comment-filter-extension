import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/background/background.js", import.meta.url), "utf8");
const section = (start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));

const state = {
  scanTask: { id: "stale", status: "running", activeTabId: 7, urls: ["https://www.douyin.com/video/7"], currentIndex: 0, totalPages: 1, newRows: [], baseRows: [], errors: [], filters: {}, startedAt: Date.now() },
  stopRequested: false,
  rows: [],
  baseRows: []
};
let removedListener;
let finalizeCalls = 0;
const context = vm.createContext({
  Date,
  Math,
  setTimeout,
  clearTimeout,
  console,
  closingScanTabs: new Set(),
  scanStopTimers: new Map(),
  scanWatchdogTimers: new Map(),
  isScanActive: task => task?.status === "running" || task?.status === "waiting-verification",
  STOP_TASK_FALLBACK_MS: 10,
  crypto: { randomUUID: () => "id" },
  chrome: {
    tabs: {
      onUpdated: { addListener() {}, removeListener() {} },
      onRemoved: { addListener(listener) { removedListener = listener; }, removeListener() {} },
      get: async () => { throw new Error("tab missing"); },
      remove: async () => {},
      update: async () => {},
      sendMessage: async () => { throw new Error("page unavailable"); }
    },
    storage: { local: {
      get: async defaults => ({ ...defaults, ...state }),
      set: async values => Object.assign(state, structuredClone(values))
    } }
  },
  CommentFilterDatabase: {
    ensureLegacyMigration: async () => {},
    upsertRows: async () => {},
    recordPostScan: async () => {},
    replacePostData: async () => {},
    postIdentity: value => value
  },
  CommentFilterPostUtils: { getPostInfo: value => ({ url: value }) },
  formatRows: () => "",
  mergeRows: (left, right) => [...left, ...right],
  autoSyncCloudSnapshot: async () => {}
});
vm.runInContext(`${section("async function finalizeTask(", "async function startAutomaticProfileEnrichment(")}
${section("function buildFilterTaskRecord(", "async function finishPage(")}
${section("async function finishPage(", "async function stopTask(")}
${section("function scheduleScanStopFallback(", "async function setProfileTask(")}
globalThis.testApi = { finishPage, scheduleScanStopFallback };`, context);

await removedListener(7);
assert.equal(state.scanTask.status, "stopped", "closing the active scan tab must stop the task");

state.scanTask = { ...state.scanTask, id: "stop-fallback", status: "running", activeTabId: 8, stopRequested: true };
state.stopRequested = true;
context.testApi.scheduleScanStopFallback("stop-fallback");
await new Promise(resolve => setTimeout(resolve, 10));
assert.equal(state.scanTask.status, "stopped", "a non-responsive page must not leave stop stuck forever");
console.log("Scan lifecycle regression passed: closed tabs and stop fallback finalize scan tasks.");
