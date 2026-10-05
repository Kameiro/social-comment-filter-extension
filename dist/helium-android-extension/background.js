importScripts("post_utils.js", "profile_lookup.js", "douyin_message_sdk.js", "comment_database.js", "cloud_sync.js");

const DEFAULTS = {
  rows: [],
  output: "",
  stopRequested: false,
  scanTask: null,
  profileTask: null,
  scanProgress: null,
  nextTaskNumber: 0
};

const SCAN_SETTING_DEFAULTS = {
  keyword: "眼线",
  excludeKeyword: "",
  region: "北京",
  matchMode: "all",
  relativeDays: 30,
  scrollLimit: 0,
  genderFilter: "all",
  targetGenderCount: 0,
  targetFemaleCount: 0,
  removeConfirmedMen: true
};

const PROFILE_WORKER_ORIGIN = "http://127.0.0.1:38765";
const PROFILE_WORKER_TOKEN = "cfw_7d2f4c9a11b84e6fb0a1d9c3e8f6a2b7";
const NATIVE_HELPER_NAME = "com.commentfilter.helper";
const BACKGROUND_STARTED_AT = Date.now();
const STOP_TASK_FALLBACK_MS = 5000;
const SCAN_WATCHDOG_MS = 90000;
let activeProfileRequestController = null;
let stoppingProfileTaskId = null;
// ponytail: global lock matches the single progress/stop channel; use per-task state only if parallel scans become a real requirement.
let startingScanTask = false;
let taskNumberQueue = Promise.resolve();
const closingScanTabs = new Set();
const scanStopTimers = new Map();
const scanWatchdogTimers = new Map();

function isScanActive(task) {
  return task?.status === "running" || task?.status === "waiting-verification" || task?.status === "waiting-profile";
}

function isScanTerminal(task) {
  return task?.status === "completed" || task?.status === "stopped" || task?.status === "profile-incomplete";
}

function allocateTaskNumber() {
  const next = taskNumberQueue.then(async () => {
    const stored = await chrome.storage.local.get({ nextTaskNumber: 0 });
    const taskNumber = Math.max(0, Math.floor(Number(stored.nextTaskNumber) || 0)) + 1;
    await chrome.storage.local.set({ nextTaskNumber: taskNumber });
    return taskNumber;
  });
  taskNumberQueue = next.catch(() => {});
  return next;
}

function isAndroidRuntime() {
  return /Android/i.test(globalThis.navigator?.userAgent || "");
}

function androidProfileUnsupportedMessage() {
  return "安卓端不支持本地 Playwright 主页核验；评论抓取、筛选和导出仍可正常使用。";
}

async function autoSyncCloudSnapshot() {
  const setSyncState = async patch => {
    const stored = await chrome.storage.local.get({ cloudSyncState: { status: "idle", lastSyncedAt: 0 } });
    await chrome.storage.local.set({ cloudSyncState: { ...stored.cloudSyncState, ...patch } });
  };
  try {
    const config = await CommentFilterCloudSync.getConfig();
    if (config.mode === "local" || !config.accessToken) {
      await setSyncState({ status: "idle" });
      return { skipped: true };
    }
    await setSyncState({ status: "syncing" });
    const snapshot = await CommentFilterDatabase.getDashboardData();
    const result = await CommentFilterCloudSync.syncSnapshot(snapshot);
    await setSyncState({ status: "success", lastSyncedAt: Date.now(), counts: result.counts || {} });
    return result;
  } catch (error) {
    await setSyncState({ status: "error" }).catch(() => {});
    console.warn("云端自动同步失败：", error.message || error);
    return { ok: false, error: error.message || "云端自动同步失败。" };
  }
}

function platformFromUrl(url) {
  if (/xiaohongshu\.com/.test(url || "")) return "xhs";
  if (/douyin\.com/.test(url || "")) return "douyin";
  if (/kuaishou\.com/.test(url || "")) return "kuaishou";
  return "";
}

function messageType(platform, action) {
  const types = {
    xhs: { start: "XHS_START_BACKGROUND_SCAN_V2", stop: "XHS_STOP_SCAN_V2", profile: "XHS_EXTRACT_PROFILE_PUBLIC_INFO_V2", open: "XHS_OPEN_COMMENTS_V1" },
    douyin: { start: "DY_START_BACKGROUND_SCAN_V3", stop: "DY_STOP_SCAN_V27", profile: "DY_EXTRACT_PROFILE_PUBLIC_INFO_V28", open: "DY_PREPARE_POST_V27" },
    kuaishou: { start: "KS_START_BACKGROUND_SCAN_V1", stop: "KS_STOP_SCAN_V1", profile: "KS_EXTRACT_PROFILE_PUBLIC_INFO_V1", open: "KS_OPEN_COMMENTS_V1" }
  };
  return types[platform]?.[action] || "";
}

function normalizePostUrl(value) {
  try {
    return new URL(value).href;
  } catch (error) {
    return "";
  }
}

function parsePostUrls(value) {
  return String(value || "")
    .split(/[\n,，\s]+/)
    .map(item => normalizePostUrl(item.trim()))
    .filter(url => platformFromUrl(url));
}

async function discoverPosts(options = {}) {
  const keyword = String(options.keyword || "").trim();
  if (!keyword) throw new Error("请输入搜索关键词。");
  if (keyword.length > 80) throw new Error("搜索关键词不能超过 80 个字符。");
  const platform = options.platform === "xhs" ? "xhs" : "douyin";
  if (isAndroidRuntime()) throw new Error(`安卓端没有独立 Playwright 后台服务，暂不能无感发现${platform === "xhs" ? "小红书" : "抖音"}帖子。`);
  await ensureProfileWorkerRunning();
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5 * 60 * 1000);
  try {
    const response = await fetch(`${PROFILE_WORKER_ORIGIN}/discover`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-comment-filter-token": PROFILE_WORKER_TOKEN
      },
      body: JSON.stringify({
        platform,
        keyword,
        limit: Math.max(1, Math.min(500, Number(options.limit) || 50)),
        maxPages: Math.max(1, Math.min(100, Number(options.maxPages) || 20)),
        sort: ["relevance", "likes", "latest"].includes(options.sort) ? options.sort : "relevance",
        waitMs: Math.max(900, Math.min(5000, Number(options.waitMs) || 1400))
      }),
      signal: controller.signal
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result?.ok) throw new Error(result?.error || `抖音搜索失败（HTTP ${response.status}）。`);
    if (platform !== "douyin" || (result.results || []).length > 0 || !["risk", "no_search_response"].includes(result.stopReason)) return result;
    // The isolated worker can be challenged because it has a separate profile.
    // Retry inside a background tab so the platform's own page reuses the user's
    // current cookies, request signatures and browser session without activating the tab.
    const browserResult = await discoverDouyinPostsInBrowser({ keyword, limit, maxPages, sort });
    if (browserResult?.ok && ((browserResult.results || []).length > 0 || browserResult.riskMessage)) return browserResult;
    return result;
  } catch (error) {
    if (controller.signal.aborted) throw new Error(`后台发现任务超时，请检查${platform === "xhs" ? "小红书" : "抖音"}网络或稍后重试。`);
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

async function discoverDouyinPostsInBrowser(options = {}) {
  const keyword = String(options.keyword || "").trim();
  const searchUrl = `https://www.douyin.com/search/${encodeURIComponent(keyword)}?aid=6383&type=general`;
  const tab = await chrome.tabs.create({ url: searchUrl, active: false });
  let keepTab = false;
  try {
    if (!await waitForTabLoaded(tab.id, 30000)) throw new Error("抖音搜索页加载超时。");
    let response = null;
    for (let attempt = 0; attempt < 30; attempt += 1) {
      response = await chrome.tabs.sendMessage(tab.id, {
        type: "DY_DISCOVER_SEARCH_V1",
        options
      }).catch(() => null);
      if (response?.ok && ((response.results || []).length > 0 || response.riskMessage || response.pages > 1)) break;
      await delay(500);
    }
    if (!response?.ok) throw new Error(response?.error || "当前浏览器标签页没有返回抖音搜索数据。");
    if (response.riskMessage && !(response.results || []).length) {
      keepTab = true;
      await chrome.tabs.update(tab.id, { active: true }).catch(() => {});
      return {
        ...response,
        platform: "douyin",
        browser: "current-browser-background-tab",
        stopReason: "risk",
        riskMessage: "抖音当前浏览器标签页也触发了验证，请在已打开的搜索页完成验证后重新发现。"
      };
    }
    return {
      ...response,
      platform: "douyin",
      browser: "current-browser-background-tab",
      stopReason: response.results?.length >= Number(options.limit || 50) ? "target_reached" : "max_pages"
    };
  } finally {
    if (!keepTab) await chrome.tabs.remove(tab.id).catch(() => {});
  }
}

function waitForTabLoaded(tabId, timeoutMs = 25000) {
  return new Promise(resolve => {
    let settled = false;
    const finish = value => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      chrome.tabs.onUpdated.removeListener(listener);
      chrome.tabs.onRemoved?.removeListener(removedListener);
      resolve(value);
    };
    const timer = setTimeout(() => finish(false), timeoutMs);
    const listener = (updatedTabId, changeInfo) => {
      if (updatedTabId === tabId && changeInfo.status === "complete") finish(true);
    };
    const removedListener = removedTabId => {
      if (removedTabId === tabId) finish(false);
    };
    chrome.tabs.onUpdated.addListener(listener);
    chrome.tabs.onRemoved?.addListener(removedListener);
    chrome.tabs.get(tabId).then(tab => {
      if (tab.status === "complete") finish(true);
    }).catch(() => finish(false));
  });
}

async function waitForPostTabReady(tabId, expectedUrl, timeoutMs = 25000) {
  const targetUrl = normalizePostUrl(expectedUrl);
  const targetPlatform = platformFromUrl(expectedUrl);
  const deadline = Date.now() + timeoutMs;
  let firstMatchingUrlAt = 0;
  let lastUrl = "";
  let stableSince = 0;
  while (Date.now() < deadline) {
    let tab;
    try { tab = await chrome.tabs.get(tabId); } catch (error) { return false; }
    const currentUrl = normalizePostUrl(tab?.url || "");
    const routeReady = currentUrl && (
      !targetUrl ||
      currentUrl === targetUrl ||
      (targetPlatform === "douyin" && /douyin\.com\/(?:video|note)\/\d+/.test(currentUrl)) ||
      (targetPlatform === "xhs" && /xiaohongshu\.com\/explore\//.test(currentUrl)) ||
      (targetPlatform === "kuaishou" && /kuaishou\.com\/(?:short-video|profile-video)\//.test(currentUrl))
    );
    if (routeReady) {
      if (!firstMatchingUrlAt) firstMatchingUrlAt = Date.now();
      if (currentUrl !== lastUrl) {
        lastUrl = currentUrl;
        stableSince = Date.now();
      }
      // Douyin keeps long-lived requests open, so status may remain loading even
      // after the document URL is usable and the content script can be injected.
      if (Date.now() - firstMatchingUrlAt >= 1200 && Date.now() - stableSince >= 700) return true;
    }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  return false;
}

async function waitForPostRouteSettled(tabId, url, timeoutMs = 6000) {
  if (platformFromUrl(url) !== "douyin") return;
  const deadline = Date.now() + timeoutMs;
  let lastUrl = "";
  let changedAt = Date.now();
  while (Date.now() < deadline) {
    let tab;
    try { tab = await chrome.tabs.get(tabId); } catch (error) { return; }
    const currentUrl = normalizePostUrl(tab?.url || "");
    const pendingUrl = normalizePostUrl(tab?.pendingUrl || "");
    if (!currentUrl) return;
    // Douyin often commits /video first and redirects to /note later. A
    // pending URL is the earliest reliable signal that the document will be
    // replaced, so keep waiting until that transition has settled.
    const routeUrl = pendingUrl && pendingUrl !== currentUrl ? pendingUrl : currentUrl;
    if (routeUrl !== lastUrl) {
      lastUrl = routeUrl;
      changedAt = Date.now();
    }
    if (Date.now() - changedAt >= 700) return;
    await new Promise(resolve => setTimeout(resolve, 150));
  }
}

async function sendOpenComments(tabId, url) {
  const platform = platformFromUrl(url);
  const type = messageType(platform, "open");
  if (!type) throw new Error("不是支持的帖子链接。");
  const message = { type };
  try {
    const response = await chrome.tabs.sendMessage(tabId, message);
    if (response?.ok !== true) throw new Error(response?.error || "评论区尚未准备好。");
    return response;
  } catch (error) {
    await chrome.scripting.executeScript({
      target: { tabId },
      files: ["post_utils.js", "comment_api.js", "profile_metadata.js", platform === "douyin" ? "douyin_content.js" : platform === "kuaishou" ? "kuaishou_content.js" : "xhs_content.js"]
    });
    const response = await chrome.tabs.sendMessage(tabId, message);
    if (response?.ok !== true) throw new Error(response?.error || "评论区尚未准备好。");
    return response;
  }
}

async function openPostAtComments(url) {
  const normalizedUrl = normalizePostUrl(url);
  const platform = platformFromUrl(normalizedUrl);
  if (!normalizedUrl || !platform) throw new Error("没有识别到支持平台的帖子链接。");
  const tab = await chrome.tabs.create({ url: normalizedUrl, active: true });
  const loaded = platform === "douyin"
    ? await waitForPostTabReady(tab.id, normalizedUrl)
    : await waitForTabLoaded(tab.id);
  if (!loaded) throw new Error("帖子页面加载超时，请确认网络后重试。");
  await sendOpenComments(tab.id, normalizedUrl);
  return { ok: true, tabId: tab.id, message: "帖子已打开，并正在定位评论区。" };
}

async function likeCollectedComment(request = {}) {
  const postUrl = normalizePostUrl(request.postUrl || "");
  const platform = platformFromUrl(postUrl);
  if (!postUrl || platform !== "douyin") throw new Error("目前只支持给抖音评论点赞。");
  if (!String(request.text || "").trim() && !String(request.commentId || request.cid || "").trim()) {
    throw new Error("缺少评论内容或评论编号，无法定位这条评论。");
  }
  const opened = await openPostAtComments(postUrl);
  const response = await chrome.tabs.sendMessage(opened.tabId, {
    type: "DY_LIKE_COMMENT_V1",
    target: {
      commentId: request.commentId || request.cid || "",
      cid: request.cid || request.commentId || "",
      nickname: request.nickname || "",
      text: request.text || ""
    }
  });
  if (!response?.ok) throw new Error(response?.error || "评论点赞失败。");
  return { ...response, tabId: opened.tabId, postUrl };
}

const directMessageInFlight = new Set();

async function sendDirectMessage(request = {}) {
  const profile = normalizePostUrl(request.profile || "");
  if (!profile || !platformFromUrl(profile)) throw new Error("没有识别到支持平台的用户主页链接。");
  if (!/(\/user\/|\/profile\/|\/u\/)/i.test(profile)) throw new Error("用户主页链接格式不正确。");
  const content = String(request.content || "").trim();
  if (!content) throw new Error("私信内容不能为空。");
  if (content.length > 500) throw new Error("私信内容不能超过 500 个字符。");

  let profileKey = profile;
  try {
    const canonical = new URL(profile);
    canonical.search = "";
    canonical.hash = "";
    profileKey = canonical.href.replace(/\/$/, "");
  } catch (error) {
    // Keep the validated URL as the key when the platform uses a nonstandard profile URL.
  }

  if (directMessageInFlight.has(profileKey)) throw new Error("这个用户的私信正在发送，请勿重复操作。");
  directMessageInFlight.add(profileKey);
  let tab;
  let keepTab = false;
  let pendingRecorded = false;
  try {
    const stored = await chrome.storage.local.get({ directMessageLog: {} });
    const log = stored.directMessageLog || {};
    if (log[profileKey]?.status === "sent") throw new Error("这个用户已经有本机私信记录。");
    if (["sending", "unknown"].includes(log[profileKey]?.status)) throw new Error("上次发送结果尚未确认，请先检查平台聊天记录，避免重复私信。");

    tab = await chrome.tabs.create({ url: profile, active: false });
    const loaded = await waitForTabLoaded(tab.id);
    if (!loaded) throw new Error("用户主页加载超时，请确认网络和登录状态。");
    await new Promise(resolve => setTimeout(resolve, 1800));
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["direct_message.js"] });
    const platform = platformFromUrl(profile);
    let response = await chrome.tabs.sendMessage(tab.id, { type: "DIRECT_MESSAGE_SEND_V1", content, prepareOnly: platform === "douyin" });
    if (!response?.ok) throw new Error(response?.error || "私信会话未就绪。");
    if (platform === "douyin") {
      const recipient = new URL(profile).pathname.split("/").filter(Boolean)[1];
      const attemptId = crypto.randomUUID();
      const current = await chrome.storage.local.get({ directMessageLog: {} });
      await chrome.storage.local.set({ directMessageLog: { ...current.directMessageLog, [profileKey]: { status: "sending", content, attemptId, startedAt: Date.now() } } });
      pendingRecorded = true;
      keepTab = true;
      const results = await chrome.scripting.executeScript({
        target: { tabId: tab.id }, world: "MAIN", func: sendDouyinMessageWithSdk,
        args: [recipient, content, attemptId]
      });
      response = results[0]?.result;
      if (response?.attempted === false) {
        const current = await chrome.storage.local.get({ directMessageLog: {} });
        delete current.directMessageLog[profileKey];
        await chrome.storage.local.set({ directMessageLog: current.directMessageLog });
        pendingRecorded = false;
        keepTab = false;
      }
    }
    if (!response?.ok) throw new Error(response?.error || "平台没有完成私信发送。");

    log[profileKey] = {
      status: "sent",
      nickname: String(request.nickname || ""),
      platform: platformFromUrl(profile),
      content,
      confirmation: response.confirmation || "ui",
      serverId: response.serverId || "",
      context: Array.isArray(request.context) ? request.context.slice(0, 3) : [],
      sentAt: Date.now()
    };
    const latest = await chrome.storage.local.get({ directMessageLog: {} });
    await chrome.storage.local.set({ directMessageLog: { ...latest.directMessageLog, [profileKey]: log[profileKey] } });
    pendingRecorded = false;
    keepTab = false;
    return { ok: true, sentAt: log[profileKey].sentAt, confirmation: response.confirmation };
  } catch (error) {
    if (pendingRecorded) {
      const current = await chrome.storage.local.get({ directMessageLog: {} });
      const entry = current.directMessageLog[profileKey];
      if (entry) await chrome.storage.local.set({ directMessageLog: { ...current.directMessageLog, [profileKey]: { ...entry, status: "unknown" } } });
    }
    throw error;
  } finally {
    directMessageInFlight.delete(profileKey);
    if (tab?.id && !keepTab) await chrome.tabs.remove(tab.id).catch(() => {});
  }
}

function rowKey(row) {
  return globalThis.CommentFilterPostUtils?.strictCommentKey?.(row, row.postUrl || "") ||
    [row.postUrl || "", row.profile || row.possibleProfile || "", row.nickname || "", row.text || ""].join("||");
}

function normalizeGenderFilter(value, legacyRemoveConfirmedMen = false) {
  if (["all", "male", "female"].includes(value)) return value;
  return legacyRemoveConfirmedMen ? "female" : "all";
}

function genderMatchesFilter(gender, filter) {
  return filter === "all" || (filter === "male" && gender === "男") || (filter === "female" && gender === "女");
}

function uniqueGenderCount(rows, filter) {
  return new Set((rows || [])
    .filter(row => row.profile && row.gender && row.gender !== "未知" && genderMatchesFilter(row.gender, filter))
    .map(row => row.profile)).size;
}

function mergeRows(existingRows, newRows) {
  const map = new Map();
  for (const row of existingRows || []) map.set(rowKey(row), row);
  for (const row of newRows || []) {
    const key = rowKey(row);
    const existing = map.get(key);
    const filterTaskIds = [...new Set([
      ...(Array.isArray(existing?.filterTaskIds) ? existing.filterTaskIds : []),
      ...(Array.isArray(row?.filterTaskIds) ? row.filterTaskIds : [])
    ].map(value => String(value || "").trim()).filter(Boolean))];
    map.set(key, { ...existing, ...row, ...(filterTaskIds.length ? { filterTaskIds } : {}) });
  }
  return [...map.values()];
}

function sheetHeader() {
  return ["序号", "帖子链接", "昵称", "性别", "年龄线索", "主页所在地", "推算日期", "原始时间", "IP属地", "评论获赞", "回复数量", "主页链接", "疑似主页链接", "评论内容"];
}

function formatRows(rows) {
  const header = sheetHeader().join("\t");
  return rows.length
    ? [
        header,
        ...rows.map((row, index) => [
          index + 1,
          row.postUrl || "",
          row.nickname || "",
          row.gender || "未知",
          row.profileAge || "",
          row.profileLocation || "",
          row.dateText || "",
          row.rawTimeText || "",
          row.ipRegion || "",
          row.likeCount || "0",
          row.replyCount || "0",
          row.profile || "",
          row.profile ? "" : row.possibleProfile || "",
          row.text || ""
        ].join("\t"))
      ].join("\n")
    : header;
}

function normalizeStoredRow(row) {
  const info = globalThis.CommentFilterPostUtils?.getPostInfo(row?.postUrl || "", null);
  if (!info?.url) return row;
  const sourcePostUrl = row.sourcePostUrl || info.sourceUrl || "";
  return {
    ...row,
    postUrl: info.url,
    sourcePostUrl
  };
}

async function normalizeLegacyStorageRows() {
  const values = await chrome.storage.local.get({ rows: [] });
  const rows = mergeRows([], (values.rows || []).map(normalizeStoredRow));
  const changed = rows.some((row, index) => JSON.stringify(row) !== JSON.stringify(values.rows[index]));
  if (changed) {
    await chrome.storage.local.set({ rows, output: formatRows(rows) });
  }
  return rows;
}

function taskMessage(task) {
  const pageText = task.totalPages > 1 ? `第 ${task.currentIndex + 1}/${task.totalPages} 个帖子` : "当前帖子";
  return `爬取中：${pageText}，累计命中 ${task.newRows.length} 条`;
}

async function setTask(task, extra = {}) {
  await chrome.storage.local.set({
    scanTask: task,
    ...extra
  });
}

async function sendStart(tabId, url, task) {
  const platform = platformFromUrl(url);
  if (!platform) throw new Error("不是支持的抖音、小红书或快手帖子链接。");
  const message = {
    type: messageType(platform, "start"),
    taskId: task.id,
    filters: {
      ...task.filters,
      postUrl: url,
      taskId: task.id,
      initialDelay: platform === "douyin" ? 5000 : platform === "kuaishou" ? 2600 : 2200
    }
  };

  try {
    const response = await chrome.tabs.sendMessage(tabId, message);
    if (response?.ok !== true) throw new Error("页面脚本需要更新。");
  } catch (error) {
    await chrome.scripting.executeScript({
      target: { tabId },
      files: ["post_utils.js", "comment_api.js", "profile_metadata.js", platform === "douyin" ? "douyin_content.js" : platform === "kuaishou" ? "kuaishou_content.js" : "xhs_content.js", "task_float.js"]
    });
    await chrome.tabs.sendMessage(tabId, message);
  }
}

async function startCurrentPage(task) {
  const url = task.urls[task.currentIndex];
  const latest = await chrome.storage.local.get({ scanTask: null, stopRequested: false });
  if (latest.scanTask?.id === task.id && (latest.stopRequested || latest.scanTask.stopRequested)) {
    await finishPage(task.id, { stopped: true });
    return;
  }
  task.status = "running";
  task.message = taskMessage(task);
  await setTask(task, {
    stopRequested: false,
    scanProgress: { phase: "preparing", message: task.message, current: 0, total: task.filters.scrollLimit, matched: task.newRows.length, updatedAt: Date.now() }
  });

  try {
    await sendStart(task.activeTabId, url, task);
    scheduleScanWatchdog(task.id);
  } catch (error) {
    await finishPage(task.id, { error: error.message || "无法启动页面扫描。" });
  }
}

async function openNextBatchPage(task) {
  const url = task.urls[task.currentIndex];
  const tab = await chrome.tabs.create({ url, active: !task.openTabsInactive });
  task.activeTabId = tab.id;
  task.closeActiveTab = true;
  task.waitingForLoad = true;
  task.status = "running";
  task.message = taskMessage(task);
  await setTask(task, {
    stopRequested: false,
    scanProgress: { phase: "preparing", message: task.message, current: 0, total: task.filters.scrollLimit, matched: task.newRows.length, updatedAt: Date.now() }
  });
  scheduleScanWatchdog(task.id);

  const loaded = platformFromUrl(url) === "douyin"
    ? await waitForPostTabReady(tab.id, url)
    : await waitForTabLoaded(tab.id);
  const { scanTask: latestTask, stopRequested } = await chrome.storage.local.get({ scanTask: null, stopRequested: false });
  if (!latestTask || latestTask.id !== task.id || latestTask.status !== "running") return;
  if (stopRequested || latestTask.stopRequested) {
    await finishPage(task.id, { stopped: true });
    return;
  }
  if (!loaded) {
    await finishPage(task.id, { error: "帖子页面加载超时。" });
    return;
  }

  await waitForPostRouteSettled(tab.id, url, 10000);

  latestTask.waitingForLoad = false;
  await setTask(latestTask);
  await startCurrentPage(latestTask);
}

async function beginTask(filters, activeTab, options = {}) {
  if (startingScanTask) throw new Error("已有评论任务正在启动，请等待它开始或先停止当前任务。");
  startingScanTask = true;
  try {
    await recoverInterruptedScanTask();
    const { scanTask, profileTask } = await chrome.storage.local.get({ scanTask: null, profileTask: null });
    if (isScanActive(scanTask) && scanTask.activeTabId != null && typeof chrome.tabs?.get === "function") {
      try {
        await chrome.tabs.get(scanTask.activeTabId);
      } catch (error) {
        await finishPage(scanTask.id, { stopped: true });
      }
    }
    let latest = await chrome.storage.local.get({ scanTask: null, scanProgress: null });
    if (isScanActive(latest.scanTask)) {
      const lastProgressAt = Number(latest.scanProgress?.updatedAt || latest.scanTask.startedAt || 0);
      if (lastProgressAt > 0 && Date.now() - lastProgressAt >= SCAN_WATCHDOG_MS) {
        await finishPage(latest.scanTask.id, { error: "旧评论任务长时间没有响应，已自动结束。" });
        latest = await chrome.storage.local.get({ scanTask: null });
      }
    }
    if (isScanActive(latest.scanTask)) {
      const taskLabel = Number(latest.scanTask.taskNumber) > 0 ? `任务 #${Number(latest.scanTask.taskNumber)}` : "当前评论任务";
      throw new Error(`已有评论任务正在运行（${taskLabel}），请等待完成或先停止当前任务。`);
    }
    if (profileTask?.status === "running") throw new Error("主页资料补充正在进行中，请等待完成或先停止当前任务。");

    await CommentFilterDatabase.ensureLegacyMigration();
    const migratedRows = await normalizeLegacyStorageRows();
    const urls = parsePostUrls(filters.postUrls);
    const isBatch = urls.length > 0;
    const currentUrl = normalizePostUrl(activeTab?.url || "");

    if (!isBatch && (!activeTab?.id || !platformFromUrl(currentUrl))) {
      throw new Error("请先打开抖音、小红书或快手帖子页面，或在批量帖子链接中填入任务。");
    }

    const taskNumber = await allocateTaskNumber();
    const task = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      taskNumber,
      status: "running",
      filters,
      urls: isBatch ? urls : [currentUrl],
      totalPages: isBatch ? urls.length : 1,
      currentIndex: 0,
      activeTabId: isBatch ? null : activeTab.id,
      closeActiveTab: false,
      openTabsInactive: Boolean(options.openTabsInactive),
      replacePostUrl: options.replacePostUrl || "",
      replacePostMeta: null,
      replacementSucceeded: false,
      waitingForLoad: false,
      newRows: [],
      baseRows: options.replacePostUrl || filters.scanMode === "append" ? migratedRows : [],
      errors: [],
      startedAt: Date.now(),
      message: "准备开始扫描..."
    };

    await chrome.storage.local.set({ stopRequested: false, profileEnrichmentPaused: false, scanTask: task });
    try {
      // Keep the message event alive until the tab has loaded and the page scan has started.
      await (isBatch ? openNextBatchPage(task) : startCurrentPage(task));
    } catch (error) {
      const { scanTask: latestTask } = await chrome.storage.local.get({ scanTask: null });
      if (latestTask?.id === task.id && latestTask.status === "running") {
        await finishPage(task.id, { error: error.message || "无法启动页面扫描。" });
      }
      throw error;
    }
    return task;
  } finally {
    startingScanTask = false;
  }
}

async function finalizeTask(task, stopped = false) {
  const { profileLookupCacheV2 = {}, profileEnrichmentPaused = false } = await chrome.storage.local.get({ profileLookupCacheV2: {}, profileEnrichmentPaused: false });
  const profileTargets = !stopped && !isAndroidRuntime()
    ? task.newRows.filter(row => row.profile && ["douyin", "xhs"].includes(platformFromUrl(row.profile)) && CommentFilterProfileLookup.needsLookup(row, profileLookupCacheV2))
    : [];
  const profileRequired = profileTargets.length > 0;
  task.profileStatus = profileRequired ? "pending" : (stopped ? "not-required" : "completed");
  task.status = profileRequired ? "waiting-profile" : (stopped ? "stopped" : "completed");
  let replaceSucceeded = Boolean(task.replacePostUrl && task.replacementSucceeded && !stopped);
  if (task.replacePostUrl && !replaceSucceeded) task.replacementSucceeded = false;
  try {
    await CommentFilterDatabase.ensureLegacyMigration();
    if (replaceSucceeded) {
      await CommentFilterDatabase.replacePostData(task.replacePostUrl, task.newRows, {
        ...(task.replacePostMeta || {}),
        task: buildFilterTaskRecord(
          task,
          task.newRows.length,
          task.status,
          task.postAudits?.[CommentFilterDatabase.postIdentity(task.replacePostUrl)]
        )
      });
    } else if (!task.replacePostUrl) {
      const rowsByPost = new Map();
      for (const row of task.newRows) {
        const postUrl = row.postUrl || task.urls[0] || "";
        const postKey = CommentFilterDatabase.postIdentity(postUrl) || postUrl;
        const rows = rowsByPost.get(postKey) || [];
        rows.push(row);
        rowsByPost.set(postKey, rows);
      }
      await Promise.all([...rowsByPost.values()].map(rows => {
        const first = rows[0] || {};
        const postKey = CommentFilterDatabase.postIdentity(first.postUrl || task.urls[0]) || first.postUrl || task.urls[0];
        return CommentFilterDatabase.recordPostScan(
          first.postUrl || task.urls[0],
          first.postTitle || "",
          first.sourcePostUrl || "",
          buildFilterTaskRecord(task, rows.length, task.status, task.postAudits?.[postKey])
        );
      }));
      await CommentFilterDatabase.upsertRows(task.newRows);
    }
  } catch (error) {
    task.errors.push({ url: task.replacePostUrl || task.urls[task.currentIndex] || "", message: error.message || "保存扫描结果失败。" });
    replaceSucceeded = false;
    task.replacementSucceeded = false;
  }
  const baseRows = replaceSucceeded
    ? task.baseRows.filter(row => CommentFilterDatabase.postIdentity(row.postUrl || "") !== CommentFilterDatabase.postIdentity(task.replacePostUrl))
    : task.baseRows;
  const rows = mergeRows(baseRows, replaceSucceeded ? task.newRows : (task.replacePostUrl ? [] : task.newRows));
  const errorText = task.errors.length ? `，失败 ${task.errors.length} 个链接` : "";
  const replyWarning = task.remainingReplyGroups > 0 ? `，${task.remainingReplyGroups} 组回复未加载成功` : "";
  const partialWarning = task.warnings?.length ? `，${task.warnings[task.warnings.length - 1]}` : "";
  const paginationWarning = Object.values(task.postAudits || {}).some(audit => audit && audit.paginationComplete === false && audit.stopReason !== "goal_reached")
    ? "，分页尚未确认到末页，请查看抓取审计"
    : "";
  const message = task.replacePostUrl && !replaceSucceeded
    ? `帖子更新失败或已停止，旧数据已保留${errorText}`
    : stopped
    ? `已停止：本次新增 ${task.newRows.length} 条，当前共 ${rows.length} 条${errorText}`
    : profileRequired
      ? `评论抓取完成，正在补充主页资料：本次命中 ${task.newRows.length} 条，当前共 ${rows.length} 条${errorText}${replyWarning}${partialWarning}${paginationWarning}`
      : `爬取完成：本次命中 ${task.newRows.length} 条，当前共 ${rows.length} 条${errorText}${replyWarning}${partialWarning}${paginationWarning}`;
  task.message = message;
  if (!profileRequired) task.completedAt = Date.now();
  await chrome.storage.local.set({
    rows,
    output: formatRows(rows),
    stopRequested: false,
    scanTask: task,
    scanProgress: { phase: profileRequired ? "profile" : task.status, message, current: task.currentIndex + 1, total: task.totalPages, matched: task.newRows.length, updatedAt: Date.now() }
  });
  if (profileRequired) {
    await startAutomaticProfileEnrichment(task.newRows, task.id, profileEnrichmentPaused);
  }
  await autoSyncCloudSnapshot();
}

async function completeScanAfterProfile(parentTaskId, profileState = {}) {
  const { scanTask } = await chrome.storage.local.get({ scanTask: null });
  if (!scanTask || scanTask.id !== parentTaskId) return null;
  const stopped = profileState.status === "stopped";
  const complete = !stopped && (profileState.goalReached === true || (
    Number(profileState.enrichedCount || 0) >= Number(profileState.targetsCount || 0) &&
    !(profileState.errors || []).length
  ));
  const nextStatus = stopped ? "stopped" : complete ? "completed" : "profile-incomplete";
  const profileErrorText = (profileState.errors || []).find(error => error?.message)?.message || "";
  const profileMessage = stopped
    ? `已停止：主页资料补充 ${Number(profileState.enrichedCount || 0)}/${Number(profileState.targetsCount || 0)} 条`
    : complete
      ? `任务完成：评论抓取和主页资料补充均已完成（${Number(profileState.enrichedCount || 0)}/${Number(profileState.targetsCount || 0)}）`
      : `评论已抓取，但主页资料补充未完成（${Number(profileState.enrichedCount || 0)}/${Number(profileState.targetsCount || 0)}）${profileErrorText ? `：${profileErrorText}` : "，请稍后重试"}`;
  const nextTask = {
    ...scanTask,
    status: nextStatus,
    profileStatus: complete ? "completed" : stopped ? "stopped" : "incomplete",
    profileEnrichedCount: Number(profileState.enrichedCount || 0),
    profileTargetCount: Number(profileState.targetsCount || 0),
    profileErrors: profileState.errors || [],
    message: profileMessage,
    completedAt: Date.now()
  };
  const postUrls = [...new Set([
    ...(nextTask.urls || []),
    ...Object.values(nextTask.postAudits || {}).map(audit => audit?.postUrl).filter(Boolean)
  ])];
  await Promise.all(postUrls.map(url => CommentFilterDatabase.recordPostScan(
    url,
    "",
    "",
    buildFilterTaskRecord(nextTask, nextTask.newRows?.length || 0, nextStatus, nextTask.postAudits?.[CommentFilterDatabase.postIdentity(url)])
  )));
  await chrome.storage.local.set({
    scanTask: nextTask,
    scanProgress: { phase: nextStatus, message: profileMessage, current: Number(profileState.enrichedCount || 0), total: Number(profileState.targetsCount || 0), matched: nextTask.newRows?.length || 0, updatedAt: Date.now() }
  });
  return nextTask;
}

async function startAutomaticProfileEnrichment(scanRows = [], parentTaskId = "", paused = false) {
  const { scanTask, profileTask, profileLookupCacheV2, profileEnrichmentPaused } = await chrome.storage.local.get({ scanTask: null, profileTask: null, profileLookupCacheV2: {}, profileEnrichmentPaused: false });
  const shouldPause = paused || profileEnrichmentPaused;
  if (profileTask?.status === "running") return false;
  const hasTargets = scanRows.some(row => row.profile && ["douyin", "xhs"].includes(platformFromUrl(row.profile)) && CommentFilterProfileLookup.needsLookup(row, profileLookupCacheV2));
  if (!hasTargets) {
    if (parentTaskId) await completeScanAfterProfile(parentTaskId, { enrichedCount: 0, targetsCount: 0, errors: [] });
    return false;
  }
  if (shouldPause) {
    if (parentTaskId) await completeScanAfterProfile(parentTaskId, { enrichedCount: 0, targetsCount: scanRows.length, errors: [{ message: "自动主页资料补充已暂停" }] });
    return false;
  }
  if (isScanActive(scanTask) && scanTask.id !== parentTaskId) return false;
  try {
    await beginProfileEnrichment("all", 0, true, scanRows, { parentScanTaskId: parentTaskId });
    return true;
  } catch (error) {
    if (parentTaskId) await completeScanAfterProfile(parentTaskId, { enrichedCount: 0, targetsCount: scanRows.length, errors: [{ message: error.message || "自动主页核验未启动" }] });
    else await chrome.storage.local.set({ scanProgress: { phase: "profile", message: error.message || "自动主页核验未启动，稍后可在高级功能中重试。", updatedAt: Date.now() } });
    return false;
  }
}

function buildFilterTaskRecord(task, matchedCount = 0, status = "completed", audit = null) {
  return {
    id: task.id,
    taskNumber: Math.max(0, Number(task.taskNumber) || 0),
    filters: task.filters || {},
    matchedCount,
    status,
    completionState: task.status === "profile-incomplete" || task.completionState === "partial" ? "partial" : "complete",
    profileStatus: task.profileStatus || "not-required",
    pagination: audit || task.pagination || null,
    startedAt: task.startedAt,
    completedAt: task.completedAt || Date.now()
  };
}

async function savePostStatsSnapshot(url, title, stats) {
  if (!url || !stats || typeof stats !== "object") return;
  const info = globalThis.CommentFilterPostUtils?.getPostInfo?.(url, null) || {};
  const capturedAt = Number(stats.statsCapturedAt || 0) || Date.now();
  await CommentFilterDatabase.upsertDiscoveredPosts([{
    platform: info.platform || "",
    url: info.url || url,
    title: title || "",
    diggCount: stats.diggCount,
    commentCount: stats.commentCount,
    shareCount: stats.shareCount,
    collectCount: stats.collectCount,
    statsSource: stats.statsSource || `${info.platform || "post"}-api`,
    statsCapturedAt: capturedAt
  }]);
}

async function finishPage(taskId, result = {}) {
  const { scanTask: task, stopRequested } = await chrome.storage.local.get({ scanTask: null, stopRequested: false });
  if (!task || task.id !== taskId || !isScanActive(task)) return;
  const stopTimer = scanStopTimers.get(taskId);
  if (stopTimer) {
    clearTimeout(stopTimer);
    scanStopTimers.delete(taskId);
  }
  const watchdogTimer = scanWatchdogTimers.get(taskId);
  if (watchdogTimer) {
    clearTimeout(watchdogTimer);
    scanWatchdogTimers.delete(taskId);
  }

  const currentUrl = task.urls[task.currentIndex];

  // A verification response is a pause point, not a failed or completed page.
  // Save the partial page result, keep the tab open, and wait for an explicit
  // resume after the user completes the platform challenge.
  if (result.waitingForVerification) {
    const resultInfo = globalThis.CommentFilterPostUtils?.getPostInfo(result.postUrl || currentUrl, null) || {};
    const postUrl = resultInfo.url || normalizePostUrl(result.postUrl || currentUrl) || currentUrl;
    const sourcePostUrl = result.sourceUrl || resultInfo.sourceUrl || "";
    const postTitle = result.title || "";
    recordScanAudit(task, postUrl, result);
    await savePostStatsSnapshot(postUrl, postTitle, result.postStats);
    const pageRows = (result.rows || []).map(row => ({
      ...row,
      postUrl,
      sourcePostUrl: row.sourcePostUrl || sourcePostUrl,
      postTitle: row.postTitle || postTitle,
      filterTaskIds: [...new Set([
        ...(Array.isArray(row.filterTaskIds) ? row.filterTaskIds : []),
        task.id
      ])]
    }));
    task.newRows = mergeRows(task.newRows, pageRows);
    if (!task.replacePostUrl && pageRows.length) {
      await CommentFilterDatabase.ensureLegacyMigration();
      await CommentFilterDatabase.upsertRows(pageRows);
    }
    task.status = "waiting-verification";
    task.waitingForVerification = true;
    task.stopRequested = false;
    task.message = result.verificationMessage || "平台触发了人机验证，请完成当前页面验证后继续抓取。";
    await chrome.storage.local.set({
      stopRequested: false,
      scanTask: task,
      scanProgress: {
        phase: "waiting-verification",
        message: task.message,
        current: Number(result.pageCount || 0),
        total: task.filters?.scrollLimit || 0,
        matched: task.newRows.length,
        updatedAt: Date.now()
      }
    });
    if (task.activeTabId != null) {
      try {
        const tab = await chrome.tabs.get(task.activeTabId);
        if (tab?.windowId != null) await chrome.windows?.update?.(tab.windowId, { focused: true });
        await chrome.tabs.update(task.activeTabId, { active: true });
      } catch (error) {
        // A user-closed tab is handled by tabs.onRemoved; do not turn a
        // verification pause into an automatic close/retry loop here.
      }
    }
    return;
  }

  if (task.status === "waiting-verification") return;
  task.remainingReplyGroups = (task.remainingReplyGroups || 0) + Math.max(0, Number(result.remainingReplyGroups) || 0);
  if (result.paginationWarning || result.replyWarning) {
    task.warnings = [...new Set([...(task.warnings || []), result.paginationWarning || result.replyWarning])];
  }
  if (result.error) task.errors.push({ url: currentUrl, message: result.error });
  else {
    const resultInfo = globalThis.CommentFilterPostUtils?.getPostInfo(result.postUrl || currentUrl, null) || {};
    const postUrl = resultInfo.url || normalizePostUrl(result.postUrl || currentUrl) || currentUrl;
    const sourcePostUrl = result.sourceUrl || resultInfo.sourceUrl || (
      resultInfo.platform === "douyin" &&
      resultInfo.id &&
      !/\/\/(?:www\.)?douyin\.com\/(?:video|note)\/\d+/.test(currentUrl)
        ? currentUrl
        : ""
    );
    const postTitle = result.title || "";
    recordScanAudit(task, postUrl, result);
    await savePostStatsSnapshot(postUrl, postTitle, result.postStats);
    const pageRows = (result.rows || []).map(row => ({
      ...row,
      postUrl,
      sourcePostUrl: row.sourcePostUrl || sourcePostUrl,
      postTitle: row.postTitle || postTitle,
      filterTaskIds: [...new Set([
        ...(Array.isArray(row.filterTaskIds) ? row.filterTaskIds : []),
        task.id
      ])]
    }));
    task.newRows = mergeRows(task.newRows, pageRows);
    if (task.replacePostUrl) {
      task.replacementSucceeded = true;
      task.replacePostMeta = { title: postTitle, sourceUrl: sourcePostUrl };
    } else {
      await CommentFilterDatabase.ensureLegacyMigration();
      await CommentFilterDatabase.upsertRows(pageRows);
    }
  }

  if (task.closeActiveTab && task.activeTabId) {
    closingScanTabs.add(task.activeTabId);
    try { await chrome.tabs.remove(task.activeTabId); } catch (error) {}
    finally { closingScanTabs.delete(task.activeTabId); }
  }

  if (stopRequested || result.stopped) {
    await finalizeTask(task, true);
    return;
  }

  task.currentIndex += 1;
  if (task.currentIndex >= task.totalPages) {
    await finalizeTask(task, false);
    return;
  }

  await openNextBatchPage(task);
}

function recordScanAudit(task, postUrl, result = {}) {
  if (!task || !postUrl) return;
  const hasPaginationAudit = ["paginationComplete", "rawRowsRead", "paginationStopReason", "hasMoreAtEnd"]
    .some(key => Object.prototype.hasOwnProperty.call(result, key));
  if (!hasPaginationAudit) return;
  const postId = CommentFilterDatabase.postIdentity(postUrl);
  task.postAudits = task.postAudits || {};
  const previous = task.postAudits[postId] || {};
  const paginationComplete = result.paginationComplete === true;
  task.postAudits[postId] = {
    ...previous,
    postUrl,
    platformCommentCount: Number(result.postStats?.commentCount || previous.platformCommentCount || 0),
    apiPagesRead: Math.max(Number(previous.apiPagesRead || 0), Number(result.pageCount || 0)),
    rawRowsRead: Math.max(Number(previous.rawRowsRead || 0), Number(result.rawRowsRead || 0)),
    uniqueRowsRead: Math.max(Number(previous.uniqueRowsRead || 0), Number(result.rows?.length || 0)),
    hasMoreAtEnd: result.hasMoreAtEnd ?? previous.hasMoreAtEnd ?? null,
    paginationComplete: result.paginationComplete === undefined ? (previous.paginationComplete ?? false) : paginationComplete,
    stopReason: result.paginationStopReason || previous.stopReason || "unknown",
    lastCursor: result.lastCursor || previous.lastCursor || "",
    replyGroupsTotal: Math.max(Number(previous.replyGroupsTotal || 0), Number(result.replyGroupsTotal || 0)),
    replyGroupsCompleted: Math.max(Number(previous.replyGroupsCompleted || 0), Number(result.replyGroupsCompleted || 0)),
    replyGroupsFailed: Math.max(Number(previous.replyGroupsFailed || 0), Number(result.replyGroupsFailed || 0)),
    capturedAt: Date.now()
  };
  if (task.postAudits[postId].paginationComplete === false && task.postAudits[postId].stopReason !== "goal_reached") {
    task.warnings = [...new Set([...(task.warnings || []), `帖子评论分页未确认到末页（已读取 ${task.postAudits[postId].apiPagesRead} 页）`])];
  }
}

function scheduleScanWatchdog(taskId) {
  const previousTimer = scanWatchdogTimers.get(taskId);
  if (previousTimer) clearTimeout(previousTimer);
  const timer = setTimeout(async () => {
    scanWatchdogTimers.delete(taskId);
    const { scanTask, scanProgress } = await chrome.storage.local.get({ scanTask: null, scanProgress: null });
    if (scanTask?.id !== taskId || scanTask.status !== "running") return;
    const lastProgressAt = Number(scanProgress?.updatedAt || scanTask.startedAt || 0);
    const quietFor = Date.now() - lastProgressAt;
    if (quietFor < SCAN_WATCHDOG_MS) {
      scheduleScanWatchdog(taskId);
      return;
    }
    await finishPage(taskId, { error: "页面扫描长时间没有响应，任务已结束。" });
  }, SCAN_WATCHDOG_MS);
  scanWatchdogTimers.set(taskId, timer);
}

function scheduleScanStopFallback(taskId) {
  if (scanStopTimers.has(taskId)) return;
  const timer = setTimeout(async () => {
    scanStopTimers.delete(taskId);
    const { scanTask, stopRequested } = await chrome.storage.local.get({ scanTask: null, stopRequested: false });
    if (scanTask?.id === taskId && scanTask.status === "running" && (stopRequested || scanTask.stopRequested)) {
      await finishPage(taskId, { stopped: true });
    }
  }, STOP_TASK_FALLBACK_MS);
  scanStopTimers.set(taskId, timer);
}

async function stopTask() {
  const { scanTask: task, profileTask } = await chrome.storage.local.get({ scanTask: null, profileTask: null });
  // While a scan is waiting for profile enrichment, the profile task owns the
  // stop operation. Sending a page-stop message to the already-closed post
  // tab would otherwise leave the parent task stuck in waiting-profile.
  if (isScanActive(task) && task.status !== "waiting-profile") {
    task.status = "running";
    task.message = "正在停止，已扫到的结果会保留...";
    await setTask(task, { stopRequested: true, scanProgress: { phase: "stopping", message: task.message, current: task.currentIndex + 1, total: task.totalPages, matched: task.newRows.length, updatedAt: Date.now() } });
    const platform = platformFromUrl(task.urls[task.currentIndex]);
    try {
      await chrome.tabs.sendMessage(task.activeTabId, { type: messageType(platform, "stop") });
    } catch (error) {}
    scheduleScanStopFallback(task.id);
    return { ok: true, message: task.message };
  }

  if (profileTask?.status === "running") {
    if (stoppingProfileTaskId === profileTask.id) return { ok: true, message: "正在停止主页资料补充..." };
    stoppingProfileTaskId = profileTask.id;
    try {
      profileTask.stopRequested = true;
      await chrome.storage.local.set({ profileTask, stopRequested: true, profileEnrichmentPaused: true });
      activeProfileRequestController?.abort();
      await finalizeProfileTask(profileTask, true);
      return { ok: true, message: profileTask.message };
    } finally {
      stoppingProfileTaskId = null;
    }
  }

  if (task?.status === "waiting-profile") {
    const stopped = await completeScanAfterProfile(task.id, {
      status: "stopped",
      enrichedCount: task.profileEnrichedCount || 0,
      targetsCount: task.profileTargetCount || 0,
      errors: task.profileErrors || []
    });
    return { ok: true, message: stopped?.message || "已停止主页资料补充。" };
  }

  return { ok: true, message: "当前没有进行中的任务。" };
}

chrome.tabs.onRemoved?.addListener(tabId => {
  if (closingScanTabs.has(tabId)) return Promise.resolve();
  return chrome.storage.local.get({ scanTask: null }).then(({ scanTask }) => {
    if (isScanActive(scanTask) && scanTask.activeTabId === tabId) {
      scanTask.status = "running";
      scanTask.stopRequested = true;
      return finishPage(scanTask.id, { stopped: true });
    }
    return null;
  }).catch(error => {
    console.warn("处理评论任务标签页关闭失败：", error);
    return null;
  });
});

async function resumeScanAfterVerification() {
  const { scanTask: task } = await chrome.storage.local.get({ scanTask: null });
  if (!task || task.status !== "waiting-verification") {
    return { ok: false, error: "当前没有等待验证的评论任务。" };
  }
  if (task.activeTabId == null) {
    return { ok: false, error: "验证页面已关闭，请重新按此条件更新。" };
  }
  try {
    await chrome.tabs.get(task.activeTabId);
  } catch (error) {
    task.status = "running";
    task.stopRequested = true;
    await chrome.storage.local.set({ scanTask: task, stopRequested: true });
    await finishPage(task.id, { stopped: true });
    return { ok: false, error: "验证页面已关闭，任务已停止。" };
  }
  task.status = "running";
  task.waitingForVerification = false;
  task.stopRequested = false;
  task.message = taskMessage(task);
  await setTask(task, {
    stopRequested: false,
    scanProgress: {
      phase: "preparing",
      message: "验证已完成，正在继续抓取当前帖子...",
      current: task.currentIndex + 1,
      total: task.totalPages,
      matched: task.newRows.length,
      updatedAt: Date.now()
    }
  });
  try {
    await sendStart(task.activeTabId, task.urls[task.currentIndex], task);
    scheduleScanWatchdog(task.id);
    return { ok: true, message: "已继续当前评论任务。" };
  } catch (error) {
    await finishPage(task.id, { error: error.message || "验证后无法继续扫描。" });
    return { ok: false, error: error.message || "验证后无法继续扫描。" };
  }
}

async function setProfileTask(task, extra = {}) {
  const current = await chrome.storage.local.get({ profileTask: null, stopRequested: false });
  const staleRunningWrite = task.status === "running" &&
    current.profileTask?.id === task.id &&
    (current.profileTask.status !== "running" || current.profileTask.stopRequested || current.stopRequested) &&
    extra.stopRequested !== false;
  if (staleRunningWrite) return false;
  await chrome.storage.local.set({ profileTask: task, ...extra });
  return true;
}

function profileTaskMessage(task) {
  const current = Math.min(task.currentIndex + 1, task.targets.length);
  return `补充主页资料：${current}/${task.targets.length}，已补充 ${task.enrichedCount} 条`;
}

function publicTextFromHtml(html) {
  return String(html || "")
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;|&#160;/gi, " ")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&amp;/gi, "&")
    .replace(/\s+/g, " ")
    .trim();
}

function normalizeEmbeddedHtml(source) {
  return String(source || "")
    .replace(/\\u003[cC]/g, "<")
    .replace(/\\u003[eE]/g, ">")
    .replace(/\\u0026/g, "&")
    .replace(/\\u0022/g, '"')
    .replace(/\\u0027/g, "'")
    .replace(/\\x3[cC]/g, "<")
    .replace(/\\x3[eE]/g, ">")
    .replace(/&lt;|&#60;/gi, "<")
    .replace(/&gt;|&#62;/gi, ">")
    .replace(/&quot;|&#34;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&amp;/gi, "&")
    .replace(/\\(["'])/g, "$1");
}

function publicProfileInfoFromHtml(html, platform) {
  const source = String(html || "");
  const normalizedSource = normalizeEmbeddedHtml(source);
  const text = publicTextFromHtml(normalizedSource);
  let gender = "未知";
  if (platform === "douyin") {
    if (/woman_svg__a/i.test(normalizedSource)) gender = "女";
    else {
      const malePath = /M8\s*1\.25[\s\S]{0,1200}M5\s*10/i.test(normalizedSource);
      const maleBlue = /(?:#|u0023)168ef9|rgb\s*\(\s*22\s*,\s*142\s*,\s*249\s*\)/i.test(normalizedSource);
      if (malePath && maleBlue) gender = "男";
    }
  } else if (platform === "xhs") {
    if (/(?:xlink:href|href)=["'][^"']*#female["']/i.test(normalizedSource)) gender = "女";
    else if (/(?:xlink:href|href)=["'][^"']*#male["']/i.test(normalizedSource)) gender = "男";
  }

  const ageMatch = text.match(/(?:男|女)\s*(\d{1,3})\s*岁|(?:年龄|岁数)[:：\s]*(\d{1,3})\s*岁?/);
  const locationMatch = text.match(/(?:IP属地|所在地|地区)[:：\s]*([\u4e00-\u9fa5A-Za-z]{2,20}(?:[·・][\u4e00-\u9fa5A-Za-z]{1,20})?)/);
  return {
    gender,
    profileAge: ageMatch?.[1] || ageMatch?.[2] ? `${ageMatch[1] || ageMatch[2]}岁` : "",
    profileLocation: locationMatch?.[1] || ""
  };
}

const profileLookup = CommentFilterProfileLookup.create({
  storage: chrome.storage.local,
  // Both the primary read and the retry stay inside the isolated Playwright
  // worker. Do not open a profile tab in the user's browser for verification.
  readDirect: readProfileWithWorker,
  readRendered: readProfileWithWorker
});

async function readProfileInBackground(url, options = {}) {
  if (isAndroidRuntime()) throw new Error(androidProfileUnsupportedMessage());
  return profileLookup.read(url, options);
}

async function readProfileWithWorker(url, options = {}) {
  if (isAndroidRuntime()) throw new Error(androidProfileUnsupportedMessage());
  const platform = platformFromUrl(url);
  if (!platform) throw new Error("不支持的主页链接。");
  await ensureProfileWorkerRunning();
  const controller = new AbortController();
  activeProfileRequestController = controller;
  const timeout = setTimeout(() => controller.abort(), 15000);
  let workerResponse;
  try {
    workerResponse = await fetch(`${PROFILE_WORKER_ORIGIN}/profile`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-comment-filter-token": PROFILE_WORKER_TOKEN
      },
      body: JSON.stringify({ url, genderOnly: options.genderOnly === true }),
      signal: controller.signal
    });
  } catch (error) {
    if (controller.signal.aborted) throw new Error("主页资料请求已停止或超时。");
    throw new Error("Playwright 后台服务未能连接。请确认已安装自动后台服务。");
  } finally {
    clearTimeout(timeout);
    if (activeProfileRequestController === controller) activeProfileRequestController = null;
  }
  const workerResult = await workerResponse.json().catch(() => ({}));
  if (workerResult.ok && workerResult.info) return workerResult.info;
  if (workerResult.requiresLogin) {
    const error = new Error(workerResult.loginStarted
      ? `已弹出独立${platform === "xhs" ? "小红书" : "抖音"}登录窗口，请完成登录后重新执行主页资料核验。`
      : workerResult.loginInProgress
        ? `正在等待${platform === "xhs" ? "小红书" : "抖音"}登录完成，请登录完成后重新执行主页资料核验。`
      : `独立后台浏览器尚未登录${platform === "xhs" ? "小红书" : platform === "kuaishou" ? "快手" : "抖音"}，已跳过主页核验，评论抓取会继续进行。`);
    error.code = "LOGIN_REQUIRED";
    throw error;
  }
  if (workerResult.error) throw new Error(workerResult.error);
  throw new Error("Playwright 后台服务没有返回主页资料。");
}

async function profileWorkerHealthy() {
  const response = await fetch(`${PROFILE_WORKER_ORIGIN}/health`).catch(() => null);
  return Boolean(response?.ok);
}

async function profileWorkerStatus() {
  const response = await fetch(`${PROFILE_WORKER_ORIGIN}/health`, { cache: "no-store" }).catch(() => null);
  if (!response?.ok) return { ok: false, available: false, discovery: { status: "offline" } };
  const result = await response.json().catch(() => ({}));
  return { ...result, available: true };
}

function requestNativeWorkerStart() {
  return new Promise((resolve, reject) => {
    let nativePort;
    let settled = false;
    const finish = (callback, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      try { nativePort?.disconnect(); } catch (error) {}
      callback(value);
    };
    const timeout = setTimeout(() => finish(reject, new Error("本地自动启动助手响应超时。")), 12000);
    try {
      nativePort = chrome.runtime.connectNative(NATIVE_HELPER_NAME);
      nativePort.onMessage.addListener(message => finish(resolve, message || {}));
      nativePort.onDisconnect.addListener(() => {
        const message = chrome.runtime.lastError?.message || "未安装本地自动启动助手。";
        finish(reject, new Error(message));
      });
      nativePort.postMessage({ action: "start-worker" });
    } catch (error) {
      finish(reject, error);
    }
  });
}

async function ensureProfileWorkerRunning() {
  if (isAndroidRuntime()) throw new Error(androidProfileUnsupportedMessage());
  if (await profileWorkerHealthy()) return;
  let result;
  try {
    result = await requestNativeWorkerStart();
  } catch (error) {
    throw new Error("自动后台服务尚未安装。请双击 scripts/mac/安装自动后台服务.command，然后重新加载扩展。\n" + (error.message || ""));
  }
  if (!result?.ok) throw new Error(result?.error || "自动后台服务启动失败。");
  if (!(await profileWorkerHealthy())) throw new Error("自动后台服务启动后仍无法连接。");
}

async function verifyProfilesDuringScan(taskId, targets) {
  if (isAndroidRuntime()) {
    return {
      updates: [],
      errors: [],
      stopped: false,
      unsupported: true,
      message: androidProfileUnsupportedMessage()
    };
  }
  const uniqueTargets = [...new Map((targets || [])
    .filter(target => target?.url && ["douyin", "xhs"].includes(platformFromUrl(target.url)))
    .map(target => [target.url, target])).values()].slice(0, 4);
  const updates = [];
  const errors = [];

  for (let index = 0; index < uniqueTargets.length; index += 1) {
    const { scanTask, stopRequested } = await chrome.storage.local.get({ scanTask: null, stopRequested: false });
    if (stopRequested || scanTask?.id !== taskId || scanTask?.status !== "running") {
      return { updates, errors, stopped: true };
    }
    const target = uniqueTargets[index];
    await chrome.storage.local.set({
      scanProgress: {
        phase: "verifying",
        message: `正在验证主页：${index + 1}/${uniqueTargets.length}`,
        current: 0,
        total: 0,
        matched: 0,
        updatedAt: Date.now()
      }
    });
    try {
      const info = await readProfileInBackground(target.url, { genderOnly: true });
      updates.push({ url: target.url, info: info || {} });
    } catch (error) {
      errors.push({ url: target.url, message: error.message || "读取主页资料失败" });
      if (error.code === "LOGIN_REQUIRED") {
        return {
          updates,
          errors,
          stopped: false,
          loginRequired: true,
          loginInProgress: true,
          message: error.message
        };
      }
    }
  }
  return { updates, errors, stopped: false };
}

async function openNextProfile(task) {
  // The current profile read may have started before the user pressed Stop.
  // Re-read the shared task before writing the next step, otherwise this stale
  // in-memory object can overwrite the stop request.
  const latest = await chrome.storage.local.get({ profileTask: null, stopRequested: false });
  if (latest.profileTask?.id !== task.id || latest.profileTask.status !== "running") return;
  if (latest.profileTask?.id === task.id && (latest.stopRequested || latest.profileTask.stopRequested)) {
    const stoppedTask = {
      ...task,
      ...latest.profileTask,
      stopRequested: true,
      currentIndex: Math.max(task.currentIndex || 0, latest.profileTask.currentIndex || 0),
      enrichedCount: Math.max(task.enrichedCount || 0, latest.profileTask.enrichedCount || 0),
      removedCount: Math.max(task.removedCount || 0, latest.profileTask.removedCount || 0),
      matchedCount: Math.max(task.matchedCount || 0, latest.profileTask.matchedCount || 0),
      errors: task.errors || latest.profileTask.errors || []
    };
    await finalizeProfileTask(stoppedTask, true);
    return;
  }
  const goalReached = task.targetGenderCount > 0 && task.matchedCount >= task.targetGenderCount;
  if (task.stopRequested || goalReached || task.currentIndex >= task.targets.length) {
    await finalizeProfileTask(task, Boolean(task.stopRequested), goalReached);
    return;
  }

  const target = task.targets[task.currentIndex];
  task.activeWindowId = null;
  task.activeTabId = null;
  task.waitingForLoad = false;
  task.message = profileTaskMessage(task);
  const saved = await setProfileTask(task, {
    scanProgress: { phase: "profile", message: task.message, current: task.currentIndex + 1, total: task.targets.length, matched: task.enrichedCount, updatedAt: Date.now() }
  });
  if (!saved) return;

  await readCurrentProfile(task);
}

async function readCurrentProfile(task) {
  if (task.stopRequested) {
    await finalizeProfileTask(task, true);
    return;
  }

  const target = task.targets[task.currentIndex];
  let info = {};
  try {
    info = await readProfileInBackground(target.url);
    if (info?.error) throw new Error(info.error);
  } catch (error) {
    task.errors.push({ url: target.url, message: error.message || "读取主页资料失败" });
  }

  const current = await chrome.storage.local.get({ profileTask: null, stopRequested: false });
  if (current.profileTask?.id !== task.id || current.profileTask.status !== "running") return;
  if (current.stopRequested || current.profileTask.stopRequested) {
    await finalizeProfileTask({ ...task, stopRequested: true }, true);
    return;
  }

  const hasPublicInfo = info?.profileReadSource === "douyin-profile-api" ||
    info?.profileReadSource === "douyin-ssr" ||
    (info?.gender && info.gender !== "未知") || info?.profileAge || info?.profileLocation || info?.profileNickname;
  if (!hasPublicInfo && !task.errors.some(item => item.url === target.url)) {
    task.errors.push({ url: target.url, message: "未读取到公开主页资料" });
  }
  if (hasPublicInfo) {
    const stored = await chrome.storage.local.get({ rows: [] });
    const removeThisProfile = task.genderFilter !== "all" && info?.gender && info.gender !== "未知" && !genderMatchesFilter(info.gender, task.genderFilter);
    const rows = removeThisProfile
      ? (stored.rows || []).filter(row => row.profile !== target.url)
      : (stored.rows || []).map(row => row.profile === target.url
        ? {
            ...row,
            nickname: info.profileNickname || row.nickname || "未知昵称",
            gender: info.gender && info.gender !== "未知" ? info.gender : row.gender || "未知",
            profileAge: info.profileAge || row.profileAge || "",
            profileLocation: info.profileLocation || row.profileLocation || ""
          }
        : row);
    if (removeThisProfile) task.removedCount += (stored.rows || []).length - rows.length;
    task.enrichedCount += 1;
    await chrome.storage.local.set({ rows, output: formatRows(rows) });
    task.matchedCount = uniqueGenderCount(rows, task.genderFilter);
    if (removeThisProfile) await CommentFilterDatabase.removeProfiles([target.url]);
    else await CommentFilterDatabase.updateUserProfile(target.url, info);
  }

  task.currentIndex += 1;
  task.activeTabId = null;
  task.activeWindowId = null;
  task.waitingForLoad = false;
  await openNextProfile(task);
}

async function finalizeProfileTask(task, stopped, goalReached = false) {
  task.status = stopped ? "stopped" : "completed";
  const removedText = task.removedCount ? `，已删除 ${task.removedCount} 条不符合性别记录` : "";
  const errorText = task.errors?.length ? `，失败 ${task.errors.length} 条` : "";
  const genderLabel = task.genderFilter === "male" ? "男性" : task.genderFilter === "female" ? "女性" : "符合条件的";
  task.message = stopped
    ? `已停止主页资料补充：已补充 ${task.enrichedCount} 条${errorText}${removedText}`
    : goalReached
      ? `达到目标：已确认 ${task.matchedCount} 位${genderLabel}用户，停止补充主页资料${errorText}${removedText}`
      : `主页资料补充完成：已补充 ${task.enrichedCount}/${task.targets.length} 条${errorText}${removedText}`;
  task.completedAt = Date.now();
  await setProfileTask(task, {
    stopRequested: false,
    scanProgress: { phase: task.status, message: task.message, current: task.currentIndex, total: task.targets.length, matched: task.enrichedCount, updatedAt: Date.now() }
  });
  if (task.parentScanTaskId) {
    await completeScanAfterProfile(task.parentScanTaskId, {
      status: task.status,
      goalReached,
      enrichedCount: task.enrichedCount,
      targetsCount: task.targets.length,
      errors: task.errors
    });
  }
  await autoSyncCloudSnapshot();
}

async function recoverInterruptedProfileTask() {
  const { profileTask } = await chrome.storage.local.get({ profileTask: null });
  if (profileTask?.status !== "running" || Number(profileTask.startedAt || 0) >= BACKGROUND_STARTED_AT) return;
  const current = await chrome.storage.local.get({ profileTask: null });
  if (current.profileTask?.id !== profileTask.id || current.profileTask.status !== "running") return;
  const interrupted = {
    ...current.profileTask,
    status: "stopped",
    stopRequested: true,
    completedAt: Date.now(),
    message: "后台服务已重启，主页资料补充已中断；可手动重新开始。"
  };
  await chrome.storage.local.set({
    profileTask: interrupted,
    profileEnrichmentPaused: true,
    stopRequested: false,
    scanProgress: { phase: "stopped", message: interrupted.message, updatedAt: Date.now() }
  });
}

async function recoverInterruptedScanTask() {
  const { scanTask } = await chrome.storage.local.get({ scanTask: null });
  if (scanTask?.status !== "running" || Number(scanTask.startedAt || 0) >= BACKGROUND_STARTED_AT) return;
  const current = await chrome.storage.local.get({ scanTask: null });
  if (current.scanTask?.id !== scanTask.id || current.scanTask.status !== "running") return;
  const interrupted = {
    ...current.scanTask,
    status: "stopped",
    stopRequested: true,
    completedAt: Date.now(),
    message: "后台服务已重启，评论抓取已中断；可重新按此条件更新。"
  };
  await chrome.storage.local.set({
    scanTask: interrupted,
    stopRequested: false,
    scanProgress: { phase: "stopped", message: interrupted.message, current: interrupted.currentIndex || 0, total: interrupted.totalPages || 0, matched: interrupted.newRows?.length || 0, updatedAt: Date.now() }
  });
}

async function beginProfileEnrichment(genderFilter = "all", targetGenderCount = 0, automatic = false, sourceRows = null, options = {}) {
  if (isAndroidRuntime()) throw new Error(androidProfileUnsupportedMessage());
  const { rows, scanTask, profileTask, profileLookupCacheV2 } = await chrome.storage.local.get({ rows: [], scanTask: null, profileTask: null, profileLookupCacheV2: {} });
  const parentScanTaskId = String(options.parentScanTaskId || "");
  if (isScanActive(scanTask) && scanTask.id !== parentScanTaskId) throw new Error("请等待评论扫描结束后，再补充主页资料。");
  if (profileTask?.status === "running") throw new Error("主页资料补充正在进行中。");
  const targetMap = new Map();
  for (const row of sourceRows || rows || []) {
    if (!row.profile || targetMap.has(row.profile)) continue;
    if (row.gender && row.gender !== "未知" && row.profileAge && row.profileLocation) continue;
    if (automatic && !CommentFilterProfileLookup.needsLookup(row, profileLookupCacheV2)) continue;
    if (!["douyin", "xhs"].includes(platformFromUrl(row.profile))) continue;
    targetMap.set(row.profile, { url: row.profile, nickname: row.nickname || "" });
  }
  const targets = [...targetMap.values()];
  if (!targets.length) throw new Error("没有可补充的主页链接。请先完成评论扫描。");
  const sourcePosts = [...new Set((sourceRows || rows || []).filter(row => targetMap.has(row.profile) && row.postUrl).map(row => row.postUrl))];

  const normalizedFilter = normalizeGenderFilter(genderFilter);
  const taskNumber = await allocateTaskNumber();
  const task = {
    id: `profile-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    taskNumber,
    kind: "profile",
    status: "running",
    automatic,
    sourcePostCount: sourcePosts.length,
    sourcePostUrl: sourcePosts[0] || "",
    targets,
    currentIndex: 0,
    activeTabId: null,
    activeWindowId: null,
    waitingForLoad: false,
    enrichedCount: 0,
    removedCount: 0,
    genderFilter: normalizedFilter,
    matchedCount: uniqueGenderCount(rows, normalizedFilter),
    targetGenderCount: Math.max(0, Number(targetGenderCount || 0)),
    parentScanTaskId,
    errors: [],
    stopRequested: false,
    startedAt: Date.now(),
    message: "准备补充主页公开资料..."
  };
  await setProfileTask(task, { stopRequested: false, ...(automatic ? {} : { profileEnrichmentPaused: false }) });
  await openNextProfile(task);
  return task;
}

chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== "local" || changes.stopRequested?.newValue !== true) return;
  chrome.storage.local.get({ profileTask: null }).then(({ profileTask }) => {
    if (profileTask?.status === "running") return stopTask();
  }).catch(error => console.warn("停止主页资料任务失败：", error));
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "OPEN_COMMENT_DATABASE") {
    chrome.tabs.create({ url: chrome.runtime.getURL("dashboard.html") })
      .then(tab => sendResponse({ ok: true, tabId: tab?.id }))
      .catch(error => sendResponse({ ok: false, error: error.message || "无法打开评论数据库。" }));
    return true;
  }

  if (message?.type === "OPEN_POST_COMMENTS") {
    openPostAtComments(message.postUrl || "")
      .then(sendResponse)
      .catch(error => sendResponse({ ok: false, error: error.message || "无法打开评论区。" }));
    return true;
  }

  if (message?.type === "SEND_DIRECT_MESSAGE") {
    sendDirectMessage(message)
      .then(result => sendResponse(result))
      .catch(error => sendResponse({ ok: false, error: error.message || "私信发送失败。" }));
    return true;
  }

  if (message?.type === "LIKE_COLLECTED_COMMENT") {
    likeCollectedComment(message)
      .then(sendResponse)
      .catch(error => sendResponse({ ok: false, error: error.message || "评论点赞失败。" }));
    return true;
  }

  if (message?.type === "UPDATE_USER_GENDER") {
    CommentFilterDatabase.updateUserGender(message.profile, message.gender)
      .then(async result => {
        const stored = await chrome.storage.local.get({ rows: [] });
        const rows = (stored.rows || []).map(row => row.profile === message.profile ? { ...row, gender: result.gender } : row);
        await chrome.storage.local.set({ rows, output: formatRows(rows) });
        await autoSyncCloudSnapshot();
        sendResponse({ ok: true, ...result });
      })
      .catch(error => sendResponse({ ok: false, error: error.message || "更新用户性别失败。" }));
    return true;
  }

  if (message?.type === "COMMENT_SCAN_PAGE_DONE") {
    finishPage(message.taskId, message.result || {})
      .then(() => sendResponse({ ok: true }))
      .catch(error => sendResponse({ ok: false, error: error.message || "保存扫描结果失败。" }));
    return true;
  }

  if (message?.type === "VERIFY_PROFILES_DURING_SCAN") {
    verifyProfilesDuringScan(message.taskId, message.targets)
      .then(result => sendResponse({ ok: true, ...result }))
      .catch(error => sendResponse({ ok: false, error: error.message || "主页验证失败。" }));
    return true;
  }

  if (message?.type === "START_SCAN_TASK") {
    beginTask(message.filters || {}, message.activeTab || null, message.options || {})
      .then(task => sendResponse({ ok: true, task }))
      .catch(error => sendResponse({ ok: false, error: error.message || "无法开始扫描。" }));
    return true;
  }

  if (message?.type === "STOP_SCAN_TASK") {
    stopTask().then(sendResponse).catch(error => sendResponse({ ok: false, error: error.message || "停止失败。" }));
    return true;
  }

  if (message?.type === "RESUME_SCAN_AFTER_VERIFICATION") {
    resumeScanAfterVerification()
      .then(sendResponse)
      .catch(error => sendResponse({ ok: false, error: error.message || "验证后继续失败。" }));
    return true;
  }

  if (message?.type === "DELETE_POST_DATA") {
    chrome.storage.local.get({ scanTask: null, profileTask: null })
      .then(({ scanTask, profileTask }) => {
        if (isScanActive(scanTask) || profileTask?.status === "running") throw new Error("当前已有任务在运行，请完成后再删除帖子数据。");
        return CommentFilterDatabase.ensureLegacyMigration();
      })
      .then(() => CommentFilterDatabase.deletePostData(message.postUrl || ""))
      .then(async result => {
        const rows = await normalizeLegacyStorageRows();
        const postId = CommentFilterDatabase.postIdentity(message.postUrl || "");
        const nextRows = rows.filter(row => CommentFilterDatabase.postIdentity(row.postUrl || "") !== postId);
        await chrome.storage.local.set({ rows: nextRows, output: formatRows(nextRows) });
        sendResponse({ ok: true, ...result });
      })
      .catch(error => sendResponse({ ok: false, error: error.message || "删除帖子数据失败。" }));
    return true;
  }

  if (message?.type === "DELETE_TASK_DATA") {
    chrome.storage.local.get({ scanTask: null, profileTask: null })
      .then(({ scanTask, profileTask }) => {
        if (scanTask?.id === message.taskId && isScanActive(scanTask)) throw new Error("这条任务正在运行，请先停止任务后再删除。");
        if (profileTask?.status === "running") throw new Error("主页资料补充正在进行中，请等待完成或先停止当前任务。");
        return CommentFilterDatabase.ensureLegacyMigration();
      })
      .then(() => CommentFilterDatabase.deleteTaskData(message.postUrl || "", message.taskId || ""))
      .then(async result => {
        const postId = CommentFilterDatabase.postIdentity(message.postUrl || "");
        const rows = await normalizeLegacyStorageRows();
        const nextRows = rows.flatMap(row => {
          if (CommentFilterDatabase.postIdentity(row.postUrl || "") !== postId) return [row];
          const taskIds = Array.isArray(row.filterTaskIds) ? row.filterTaskIds.map(id => String(id)) : [];
          if (!taskIds.includes(String(message.taskId || ""))) return [row];
          const remaining = taskIds.filter(id => id !== String(message.taskId || ""));
          return remaining.length ? [{ ...row, filterTaskIds: remaining }] : [];
        });
        await chrome.storage.local.set({ rows: nextRows, output: formatRows(nextRows) });
        sendResponse({ ok: true, ...result });
      })
      .catch(error => sendResponse({ ok: false, error: error.message || "删除任务数据失败。" }));
    return true;
  }

  if (message?.type === "REFRESH_POST_DATA") {
    const filters = { ...SCAN_SETTING_DEFAULTS, ...(message.filters || {}), postUrls: message.postUrl || "", scanMode: "replace" };
    beginTask(filters, null, { openTabsInactive: false, replacePostUrl: message.postUrl || "" })
      .then(task => sendResponse({ ok: true, task }))
      .catch(error => sendResponse({ ok: false, error: error.message || "无法更新帖子数据。" }));
    return true;
  }

  if (message?.type === "START_PROFILE_ENRICHMENT") {
    beginProfileEnrichment(message.genderFilter, message.targetGenderCount)
      .then(task => sendResponse({ ok: true, task }))
      .catch(error => sendResponse({ ok: false, error: error.message || "无法补充主页资料。" }));
    return true;
  }

  if (message?.type === "REMOVE_CONFIRMED_MALE_ROWS") {
    chrome.storage.local.get({ rows: [] })
      .then(async ({ rows }) => {
        const nextRows = (rows || []).filter(row => row.gender !== "男");
        const removed = (rows || []).length - nextRows.length;
        await CommentFilterDatabase.removeConfirmedMale();
        await chrome.storage.local.set({ rows: nextRows, output: formatRows(nextRows) });
        sendResponse({ ok: true, removed });
      })
      .catch(error => sendResponse({ ok: false, error: error.message || "删除失败。" }));
    return true;
  }

  if (message?.type === "DISCOVER_DOUYIN_POSTS") {
    discoverPosts({ ...(message.options || {}), platform: "douyin" })
      .then(result => sendResponse({ ok: true, ...result }))
      .catch(error => sendResponse({ ok: false, error: error.message || "发现抖音帖子失败。" }));
    return true;
  }

  if (message?.type === "DISCOVER_POSTS") {
    discoverPosts(message.options || {})
      .then(result => sendResponse({ ok: true, ...result }))
      .catch(error => sendResponse({ ok: false, error: error.message || "发现帖子失败。" }));
    return true;
  }

  if (message?.type === "GET_DISCOVERY_STATUS") {
    profileWorkerStatus()
      .then(status => sendResponse({ ok: true, ...status }))
      .catch(error => sendResponse({ ok: false, available: false, error: error.message || "无法读取本地发现服务状态。" }));
    return true;
  }

  return false;
});

recoverInterruptedScanTask().catch(error => console.warn("恢复中断的评论任务失败：", error));
recoverInterruptedProfileTask().catch(error => console.warn("恢复中断的主页任务失败：", error));
