import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/background/background.js", import.meta.url), "utf8");
const section = (start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
const functions = [
  section("async function stopTask()", "function profileTaskMessage("),
  section("async function startAutomaticProfileEnrichment(", "function buildFilterTaskRecord("),
  section("async function openNextProfile(task)", "async function beginProfileEnrichment("),
  section("async function recoverInterruptedProfileTask()", "async function beginProfileEnrichment("),
  section("chrome.storage.onChanged.addListener((changes, area) => {", "chrome.runtime.onMessage.addListener(")
].join("\n");

let releaseRead;
const read = new Promise(resolve => { releaseRead = resolve; });
const task = {
  id: "profile-test", kind: "profile", status: "running", currentIndex: 0,
  targets: [{ url: "https://www.xiaohongshu.com/user/profile/one" }, { url: "https://www.xiaohongshu.com/user/profile/two" }],
  enrichedCount: 0, removedCount: 0, matchedCount: 0, targetGenderCount: 0,
  genderFilter: "all", errors: [], stopRequested: false
};
const state = { scanTask: null, profileTask: structuredClone(task), stopRequested: false };
let reads = 0;
let aborts = 0;
let storageListener;
const context = vm.createContext({
  chrome: { tabs: { onRemoved: { addListener() {} } }, storage: { onChanged: { addListener(listener) { storageListener = listener; } }, local: {
    async get(defaults) { return { ...defaults, ...state }; },
    async set(values) {
      const oldStop = state.stopRequested;
      Object.assign(state, structuredClone(values));
      if (oldStop !== state.stopRequested) storageListener?.({ stopRequested: { newValue: state.stopRequested } }, "local");
    }
  } } },
  stoppingProfileTaskId: null,
  isScanActive: task => task?.status === "running" || task?.status === "waiting-verification",
  BACKGROUND_STARTED_AT: Date.now(),
  activeProfileRequestController: { abort() { aborts++; } },
  readProfileInBackground: async () => { reads++; return read; },
  autoSyncCloudSnapshot: async () => {},
  platformFromUrl: () => "xhs",
  formatRows: () => "",
  CommentFilterProfileLookup: { needsLookup: () => true },
  beginProfileEnrichment: async (...args) => { context.autoStartRows = args[3]; },
  CommentFilterDatabase: {}
  ,allocateTaskNumber: async () => 1
});
vm.runInContext(`${functions}\n globalThis.testApi = { stopTask, readCurrentProfile, openNextProfile, setProfileTask, startAutomaticProfileEnrichment, recoverInterruptedProfileTask };`, context);

const pending = context.testApi.readCurrentProfile(task);
await Promise.resolve();
assert.equal(reads, 1);
const result = await context.testApi.stopTask();
assert.equal(result.ok, true);
assert.equal(aborts, 1);
assert.equal(state.profileTask.status, "stopped", "stop must finish immediately, even while a read is pending");
assert.equal(state.profileEnrichmentPaused, true);
assert.equal(await context.testApi.startAutomaticProfileEnrichment(), false, "dashboard refresh must not restart a stopped task");
releaseRead({});
await pending;
assert.equal(state.profileTask.status, "stopped", "late read must not revive the task");
assert.equal(state.profileTask.currentIndex, 0);
assert.equal(reads, 1, "late read must not start the next profile");
await context.testApi.setProfileTask({ ...task, currentIndex: 1 });
assert.equal(state.profileTask.status, "stopped", "stale running writes must be rejected");
state.profileTask = structuredClone(task);
state.stopRequested = false;
await context.chrome.storage.local.set({ stopRequested: true });
for (let i = 0; i < 10 && state.profileTask.status !== "stopped"; i++) await new Promise(resolve => setTimeout(resolve, 0));
assert.equal(state.profileTask.status, "stopped", "shared stop flag must stop a task without a runtime message");
state.profileEnrichmentPaused = false;
assert.equal(await context.testApi.startAutomaticProfileEnrichment([]), false, "opening the dashboard must not launch a historical backlog");
const newRows = [{ profile: "https://www.xiaohongshu.com/user/profile/new", postUrl: "https://www.xiaohongshu.com/explore/new" }];
assert.equal(await context.testApi.startAutomaticProfileEnrichment(newRows), true);
assert.equal(context.autoStartRows, newRows, "automatic enrichment must receive only the latest scan rows");
let scopedTask;
const scopeContext = vm.createContext({
  isAndroidRuntime: () => false,
  isScanActive: task => task?.status === "running" || task?.status === "waiting-verification",
  chrome: { storage: { local: { get: async defaults => ({ ...defaults, rows: [
    { profile: "https://www.xiaohongshu.com/user/profile/old", postUrl: "https://www.xiaohongshu.com/explore/old" },
    ...newRows
  ] }) } } },
  CommentFilterProfileLookup: { needsLookup: () => true },
  platformFromUrl: () => "xhs",
  normalizeGenderFilter: value => value,
  uniqueGenderCount: () => 0,
  allocateTaskNumber: async () => 1,
  setProfileTask: async task => { scopedTask = task; },
  openNextProfile: async () => {}
});
vm.runInContext(`${section("async function beginProfileEnrichment(", "chrome.storage.onChanged.addListener(")}\n globalThis.begin = beginProfileEnrichment;`, scopeContext);
await scopeContext.begin("all", 0, true, newRows);
assert.equal(scopedTask.targets.length, 1, "old comments must not enter a new scan's automatic task");
assert.equal(scopedTask.targets[0].url, newRows[0].profile);
assert.equal(scopedTask.sourcePostCount, 1);
assert.equal(scopedTask.sourcePostUrl, newRows[0].postUrl);
assert.equal(scopedTask.automatic, true);

state.profileTask = { ...task, startedAt: context.BACKGROUND_STARTED_AT - 1000 };
await context.testApi.recoverInterruptedProfileTask();
assert.equal(state.profileTask.status, "stopped", "a task from a previous background instance cannot keep showing as running");
assert.equal(state.profileEnrichmentPaused, true);
state.profileTask = { ...task, startedAt: context.BACKGROUND_STARTED_AT + 1000 };
await context.testApi.recoverInterruptedProfileTask();
assert.equal(state.profileTask.status, "running", "a task started in the current background instance must be preserved");
console.log("Profile task regression passed: stop, scan-only auto scope, interrupted-task recovery.");
