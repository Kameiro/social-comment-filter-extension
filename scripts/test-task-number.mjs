import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/background/background.js", import.meta.url), "utf8");
const section = (start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
const state = { nextTaskNumber: 0, scanTask: null, profileTask: null };
const context = vm.createContext({
  chrome: { storage: { local: {
    get: async defaults => ({ ...defaults, ...state }),
    set: async values => Object.assign(state, structuredClone(values))
  } } },
  Date,
  Math
});
vm.runInContext(`${section("let taskNumberQueue =", "function isAndroidRuntime(")}\nglobalThis.allocateTaskNumber = allocateTaskNumber;`, context);
assert.equal(await context.allocateTaskNumber(), 1);
assert.equal(await context.allocateTaskNumber(), 2);

const beginContext = vm.createContext({
  chrome: { storage: { local: {
    get: async defaults => ({ ...defaults, ...state }),
    set: async values => Object.assign(state, structuredClone(values))
  } } },
  CommentFilterDatabase: { ensureLegacyMigration: async () => {}, },
  normalizeLegacyStorageRows: async () => [],
  parsePostUrls: value => String(value || "").split(/\s+/).filter(Boolean),
  normalizePostUrl: value => value,
  platformFromUrl: value => /douyin\.com/.test(value || "") ? "douyin" : "",
  startingScanTask: false,
  isScanActive: task => task?.status === "running" || task?.status === "waiting-verification",
  openNextBatchPage: async () => {},
  startCurrentPage: async () => {},
  recoverInterruptedScanTask: async () => {},
  allocateTaskNumber: async () => 3,
  Date,
  Math
});
vm.runInContext(`${section("async function beginTask(", "async function finalizeTask(")}\nglobalThis.beginTask = beginTask;`, beginContext);
const scanTask = await beginContext.beginTask({}, { id: 1, url: "https://www.douyin.com/video/1" });
assert.equal(scanTask.taskNumber, 3);
assert.equal(state.scanTask.taskNumber, 3);

const databaseSource = await readFile(new URL("../extension/data/comment_database.js", import.meta.url), "utf8");
const databaseContext = vm.createContext({ Date, Math });
const normalizeTaskStart = databaseSource.indexOf("function positiveInteger(");
const normalizeTaskEnd = databaseSource.indexOf("\n  function withFilterTask", normalizeTaskStart);
vm.runInContext(`${databaseSource.slice(normalizeTaskStart, normalizeTaskEnd)}\nglobalThis.normalizeFilterTask = normalizeFilterTask;`, databaseContext);
assert.equal(databaseContext.normalizeFilterTask({ id: "legacy" }).taskNumber, 0);
assert.equal(databaseContext.normalizeFilterTask({ id: "numbered", taskNumber: 3 }).taskNumber, 3);
assert.equal(databaseContext.normalizeFilterTask({ id: "owned", taskNumber: 4, postId: "douyin:post", postNumber: 7 }).postId, "douyin:post");
assert.equal(databaseContext.normalizeFilterTask({ id: "owned", taskNumber: 4, postId: "douyin:post", postNumber: 7 }).postNumber, 7);
console.log("Task numbering regression passed: allocation is sequential and legacy records remain readable.");
