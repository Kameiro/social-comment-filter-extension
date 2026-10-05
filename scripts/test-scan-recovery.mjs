import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/background/background.js", import.meta.url), "utf8");
const start = source.indexOf("async function recoverInterruptedScanTask()");
const end = source.indexOf("async function beginProfileEnrichment(");
const state = {
  scanTask: { id: "old", status: "running", startedAt: 1, currentIndex: 2, totalPages: 3, newRows: [{ id: 1 }] },
  stopRequested: true
};
const context = vm.createContext({
  Date,
  BACKGROUND_STARTED_AT: 100,
  chrome: { storage: { local: {
    async get(defaults) { return { ...defaults, ...state }; },
    async set(values) { Object.assign(state, structuredClone(values)); }
  } } }
});
vm.runInContext(`${source.slice(start, end)}\nglobalThis.recover = recoverInterruptedScanTask;`, context);
await context.recover();
assert.equal(state.scanTask.status, "stopped");
assert.equal(state.stopRequested, false);
assert.match(state.scanTask.message, /后台服务已重启/);
assert.equal(state.scanProgress.phase, "stopped");

state.scanTask = { id: "current", status: "running", startedAt: 101 };
await context.recover();
assert.equal(state.scanTask.status, "running", "current task must not be interrupted");
console.log("Scan task recovery passed: stale tasks unlock, current tasks survive.");
