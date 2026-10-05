import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/background/background.js", import.meta.url), "utf8");
const section = (start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
const state = { scanTask: null, stopRequested: false };
let startedTask;
const context = vm.createContext({
  chrome: {
    tabs: {
      onUpdated: { addListener() {}, removeListener() {} },
      create: async () => ({ id: 42, status: "loading" }),
      get: async () => ({ id: 42, status: "complete", url: "https://www.douyin.com/note/1" })
    },
    storage: { local: {
      get: async defaults => ({ ...defaults, ...state }),
      set: async values => Object.assign(state, structuredClone(values))
    } }
  },
  Date,
  Math,
  platformFromUrl: url => /douyin\.com/.test(url || "") ? "douyin" : "",
  normalizePostUrl: url => url || "",
  setTimeout,
  clearTimeout,
  taskMessage: task => `第 ${task.currentIndex + 1}/${task.totalPages} 个帖子`,
  finishPage: async () => { throw new Error("finishPage should not run for a loaded tab"); },
  scheduleScanWatchdog: () => {},
  startCurrentPage: async task => { startedTask = task; }
});
vm.runInContext(`${section("function waitForTabLoaded(", "const directMessageInFlight")}
${section("async function waitForPostRouteSettled(", "const directMessageInFlight")}
${section("async function setTask(", "async function sendStart(")}
${section("async function openNextBatchPage(", "async function beginTask(")}
globalThis.testApi = { openNextBatchPage };`, context);

await context.testApi.openNextBatchPage({
  id: "task-1", urls: ["https://www.douyin.com/video/1"], currentIndex: 0,
  totalPages: 1, openTabsInactive: true, filters: { scrollLimit: 0 }, newRows: []
});
assert.equal(startedTask.activeTabId, 42, "a tab that finishes before the update event must still start scanning");
assert.equal(startedTask.waitingForLoad, false);
console.log("Batch page load regression passed: fast tab loads start scanning without tabs.onUpdated races.");
