import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/background/background.js", import.meta.url), "utf8");
const start = source.indexOf("function scheduleScanWatchdog(");
const end = source.indexOf("function scheduleScanStopFallback(", start);
let finished = 0;
let state = {
  scanTask: { id: "stale", status: "running", startedAt: Date.now() - 100 },
  scanProgress: { updatedAt: Date.now() - 100 }
};
const context = vm.createContext({
  Date,
  setTimeout,
  clearTimeout,
  SCAN_WATCHDOG_MS: 50,
  scanWatchdogTimers: new Map(),
  chrome: { storage: { local: { get: async defaults => ({ ...defaults, ...state }) } } },
  finishPage: async () => { finished += 1; state.scanTask.status = "completed"; }
});
vm.runInContext(`${source.slice(start, end)}\nglobalThis.schedule = scheduleScanWatchdog;`, context);

context.schedule("stale");
await new Promise(resolve => setTimeout(resolve, 60));
assert.equal(finished, 1, "a stalled scan must be finalized");

state = {
  scanTask: { id: "active", status: "running", startedAt: Date.now() },
  scanProgress: { updatedAt: Date.now() }
};
context.schedule("active");
await new Promise(resolve => setTimeout(resolve, 20));
assert.equal(finished, 1, "a scan with recent progress must not be finalized");
state.scanTask.status = "completed";
console.log("Scan watchdog regression passed: stalled scans finalize, active progress survives.");
