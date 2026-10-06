import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/background/background.js", import.meta.url), "utf8");
const section = (start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
let releaseMigration;
const migration = new Promise(resolve => { releaseMigration = resolve; });
const state = { scanTask: null, profileTask: null };
const context = vm.createContext({
  chrome: { storage: { local: {
    get: async defaults => ({ ...defaults, ...state }),
    set: async values => Object.assign(state, structuredClone(values))
  } } },
  CommentFilterDatabase: { ensureLegacyMigration: async () => migration },
  normalizeLegacyStorageRows: async () => [],
  parsePostUrls: value => String(value || "").split(/\s+/).filter(Boolean),
  normalizePostUrl: value => value,
  platformFromUrl: value => /douyin\.com/.test(value || "") ? "douyin" : "",
  openNextBatchPage: async () => {},
  startCurrentPage: async () => {},
  allocateTaskNumber: async () => 1,
  startingScanTask: false,
  isScanActive: task => task?.status === "running" || task?.status === "waiting-verification",
  recoverInterruptedScanTask: async () => {},
  SCAN_WATCHDOG_MS: 90000,
  Date,
  Math
});
vm.runInContext(`${section("async function beginTask(", "async function finalizeTask(")}\n globalThis.beginTask = beginTask;`, context);

const first = context.beginTask({}, { id: 1, url: "https://www.douyin.com/video/1" });
await Promise.resolve();
await assert.rejects(
  context.beginTask({}, { id: 2, url: "https://www.douyin.com/video/2" }),
  /已有评论任务正在启动/
);
releaseMigration();
await first;
assert.equal(state.scanTask.activeTabId, 1);
await assert.rejects(
  context.beginTask({}, { id: 2, url: "https://www.douyin.com/video/2" }),
  /已有评论任务正在运行/
);
console.log("Scan concurrency regression passed: second task is rejected without replacing the first.");
