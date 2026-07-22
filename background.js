const DEFAULTS = {
  rows: [],
  output: "",
  stopRequested: false,
  scanTask: null,
  profileTask: null,
  scanProgress: null
};

const PROFILE_WORKER_ORIGIN = "http://127.0.0.1:38765";
const PROFILE_WORKER_TOKEN = "cfw_7d2f4c9a11b84e6fb0a1d9c3e8f6a2b7";
const NATIVE_HELPER_NAME = "com.commentfilter.helper";

function platformFromUrl(url) {
  if (/xiaohongshu\.com/.test(url || "")) return "xhs";
  if (/douyin\.com/.test(url || "")) return "douyin";
  return "";
}

function messageType(platform, action) {
  const types = {
    xhs: { start: "XHS_START_BACKGROUND_SCAN_V2", stop: "XHS_STOP_SCAN_V2", profile: "XHS_EXTRACT_PROFILE_PUBLIC_INFO_V2" },
    douyin: { start: "DY_START_BACKGROUND_SCAN_V1", stop: "DY_STOP_SCAN_V27", profile: "DY_EXTRACT_PROFILE_PUBLIC_INFO_V28" }
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

function rowKey(row) {
  return [row.postUrl || "", row.profile || row.possibleProfile || "", row.nickname || "", row.rawTimeText || "", row.text || ""].join("||");
}

function mergeRows(existingRows, newRows) {
  const map = new Map();
  for (const row of existingRows || []) map.set(rowKey(row), row);
  for (const row of newRows || []) map.set(rowKey(row), row);
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
  if (!platform) throw new Error("不是支持的抖音或小红书帖子链接。");
  const message = {
    type: messageType(platform, "start"),
    taskId: task.id,
    filters: {
      ...task.filters,
      postUrl: url,
      taskId: task.id,
      initialDelay: platform === "douyin" ? 2800 : 2200
    }
  };

  try {
    const response = await chrome.tabs.sendMessage(tabId, message);
    if (response?.ok !== true) throw new Error("页面脚本需要更新。");
  } catch (error) {
    await chrome.scripting.executeScript({
      target: { tabId },
      files: [platform === "douyin" ? "douyin_content.js" : "xhs_content.js", "task_float.js"]
    });
    await chrome.tabs.sendMessage(tabId, message);
  }
}

async function startCurrentPage(task) {
  const url = task.urls[task.currentIndex];
  task.status = "running";
  task.message = taskMessage(task);
  await setTask(task, {
    stopRequested: false,
    scanProgress: { phase: "preparing", message: task.message, current: 0, total: task.filters.scrollLimit, matched: task.newRows.length, updatedAt: Date.now() }
  });

  try {
    await sendStart(task.activeTabId, url, task);
  } catch (error) {
    await finishPage(task.id, { error: error.message || "无法启动页面扫描。" });
  }
}

async function openNextBatchPage(task) {
  const url = task.urls[task.currentIndex];
  const tab = await chrome.tabs.create({ url, active: true });
  task.activeTabId = tab.id;
  task.closeActiveTab = true;
  task.waitingForLoad = true;
  task.status = "running";
  task.message = taskMessage(task);
  await setTask(task, {
    stopRequested: false,
    scanProgress: { phase: "preparing", message: task.message, current: 0, total: task.filters.scrollLimit, matched: task.newRows.length, updatedAt: Date.now() }
  });

  if (tab.status === "complete") {
    task.waitingForLoad = false;
    await setTask(task);
    await startCurrentPage(task);
  }
}

async function beginTask(filters, activeTab) {
  const urls = parsePostUrls(filters.postUrls);
  const isBatch = urls.length > 0;
  const currentUrl = normalizePostUrl(activeTab?.url || "");

  if (!isBatch && (!activeTab?.id || !platformFromUrl(currentUrl))) {
    throw new Error("请先打开抖音或小红书帖子页面，或在批量帖子链接中填入任务。");
  }

  const stored = await chrome.storage.local.get(DEFAULTS);
  const task = {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    status: "running",
    filters,
    urls: isBatch ? urls : [currentUrl],
    totalPages: isBatch ? urls.length : 1,
    currentIndex: 0,
    activeTabId: isBatch ? null : activeTab.id,
    closeActiveTab: false,
    waitingForLoad: false,
    newRows: [],
    baseRows: filters.scanMode === "append" ? stored.rows || [] : [],
    errors: [],
    startedAt: Date.now(),
    message: "准备开始扫描..."
  };

  await chrome.storage.local.set({ stopRequested: false, scanTask: task });
  if (isBatch) await openNextBatchPage(task);
  else await startCurrentPage(task);
  return task;
}

async function finalizeTask(task, stopped = false) {
  const rows = mergeRows(task.baseRows, task.newRows);
  const errorText = task.errors.length ? `，失败 ${task.errors.length} 个链接` : "";
  const message = stopped
    ? `已停止：本次新增 ${task.newRows.length} 条，当前共 ${rows.length} 条${errorText}`
    : `爬取完成：本次命中 ${task.newRows.length} 条，当前共 ${rows.length} 条${errorText}`;
  task.status = stopped ? "stopped" : "completed";
  task.message = message;
  task.completedAt = Date.now();
  await chrome.storage.local.set({
    rows,
    output: formatRows(rows),
    stopRequested: false,
    scanTask: task,
    scanProgress: { phase: task.status, message, current: task.currentIndex + 1, total: task.totalPages, matched: task.newRows.length, updatedAt: Date.now() }
  });
}

async function finishPage(taskId, result = {}) {
  const { scanTask: task, stopRequested } = await chrome.storage.local.get({ scanTask: null, stopRequested: false });
  if (!task || task.id !== taskId || task.status !== "running") return;

  const currentUrl = task.urls[task.currentIndex];
  if (result.error) task.errors.push({ url: currentUrl, message: result.error });
  else task.newRows = mergeRows(task.newRows, (result.rows || []).map(row => ({ ...row, postUrl: row.postUrl || currentUrl })));

  if (task.closeActiveTab && task.activeTabId) {
    try { await chrome.tabs.remove(task.activeTabId); } catch (error) {}
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

async function stopTask() {
  const { scanTask: task, profileTask } = await chrome.storage.local.get({ scanTask: null, profileTask: null });
  if (task?.status === "running") {
    task.message = "正在停止，已扫到的结果会保留...";
    await setTask(task, { stopRequested: true, scanProgress: { phase: "stopping", message: task.message, current: task.currentIndex + 1, total: task.totalPages, matched: task.newRows.length, updatedAt: Date.now() } });
    const platform = platformFromUrl(task.urls[task.currentIndex]);
    try {
      await chrome.tabs.sendMessage(task.activeTabId, { type: messageType(platform, "stop") });
    } catch (error) {}
    return { ok: true, message: task.message };
  }

  if (profileTask?.status === "running") {
    profileTask.stopRequested = true;
    profileTask.message = "正在停止主页资料补充，已读取的资料会保留...";
    await chrome.storage.local.set({
      profileTask,
      scanProgress: { phase: "stopping", message: profileTask.message, current: profileTask.currentIndex + 1, total: profileTask.targets.length, matched: profileTask.enrichedCount, updatedAt: Date.now() }
    });
    return { ok: true, message: profileTask.message };
  }

  return { ok: true, message: "当前没有进行中的任务。" };
}

async function setProfileTask(task, extra = {}) {
  await chrome.storage.local.set({ profileTask: task, ...extra });
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
    if (/(?:xlink:href|href)=["']#female["']/i.test(normalizedSource)) gender = "女";
    else if (/(?:xlink:href|href)=["']#male["']/i.test(normalizedSource)) gender = "男";
  }

  const ageMatch = text.match(/(?:男|女)\s*(\d{1,3})\s*岁|(?:年龄|岁数)[:：\s]*(\d{1,3})\s*岁?/);
  const locationMatch = text.match(/(?:IP属地|所在地|地区)[:：\s]*([\u4e00-\u9fa5A-Za-z]{2,20}(?:[·・][\u4e00-\u9fa5A-Za-z]{1,20})?)/);
  return {
    gender,
    profileAge: ageMatch?.[1] || ageMatch?.[2] ? `${ageMatch[1] || ageMatch[2]}岁` : "",
    profileLocation: locationMatch?.[1] || ""
  };
}

async function readProfileInBackground(url) {
  const platform = platformFromUrl(url);
  if (!platform) throw new Error("不支持的主页链接。");
  await ensureProfileWorkerRunning();
  const workerResponse = await fetch(`${PROFILE_WORKER_ORIGIN}/profile`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-comment-filter-token": PROFILE_WORKER_TOKEN
    },
    body: JSON.stringify({ url })
  }).catch(error => {
    throw new Error("Playwright 后台服务未能连接。请确认已安装自动后台服务。");
  });
  const workerResult = await workerResponse.json().catch(() => ({}));
  if (workerResult.ok && workerResult.info) return workerResult.info;
  if (workerResult.requiresLogin) {
    const error = new Error(workerResult.loginInProgress
      ? `正在等待${platform === "xhs" ? "小红书" : "抖音"}登录完成，评论抓取会继续进行。`
      : `独立后台浏览器尚未登录${platform === "xhs" ? "小红书" : "抖音"}，已跳过主页核验，评论抓取会继续进行。`);
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
  if (await profileWorkerHealthy()) return;
  let result;
  try {
    result = await requestNativeWorkerStart();
  } catch (error) {
    throw new Error("自动后台服务尚未安装。请双击扩展目录中的“安装自动后台服务.command”，然后重新加载扩展。\n" + (error.message || ""));
  }
  if (!result?.ok) throw new Error(result?.error || "自动后台服务启动失败。");
  if (!(await profileWorkerHealthy())) throw new Error("自动后台服务启动后仍无法连接。");
}

async function verifyProfilesDuringScan(taskId, targets) {
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
      const info = await readProfileInBackground(target.url);
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
  const goalReached = task.targetFemaleCount > 0 && task.femaleCount >= task.targetFemaleCount;
  if (task.stopRequested || goalReached || task.currentIndex >= task.targets.length) {
    await finalizeProfileTask(task, Boolean(task.stopRequested), goalReached);
    return;
  }

  const target = task.targets[task.currentIndex];
  task.activeWindowId = null;
  task.activeTabId = null;
  task.waitingForLoad = false;
  task.message = profileTaskMessage(task);
  await setProfileTask(task, {
    scanProgress: { phase: "profile", message: task.message, current: task.currentIndex + 1, total: task.targets.length, matched: task.enrichedCount, updatedAt: Date.now() }
  });

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

  const hasPublicInfo = (info?.gender && info.gender !== "未知") || info?.profileAge || info?.profileLocation;
  if (hasPublicInfo) {
    const stored = await chrome.storage.local.get({ rows: [] });
    const removeThisProfile = task.removeConfirmedMen && info?.gender === "男";
    const rows = removeThisProfile
      ? (stored.rows || []).filter(row => row.profile !== target.url)
      : (stored.rows || []).map(row => row.profile === target.url
        ? {
            ...row,
            gender: info.gender && info.gender !== "未知" ? info.gender : row.gender || "未知",
            profileAge: info.profileAge || row.profileAge || "",
            profileLocation: info.profileLocation || row.profileLocation || ""
          }
        : row);
    if (removeThisProfile) task.removedCount += (stored.rows || []).length - rows.length;
    if (info?.gender === "女") task.femaleCount += 1;
    task.enrichedCount += 1;
    await chrome.storage.local.set({ rows, output: formatRows(rows) });
  }

  task.currentIndex += 1;
  task.activeTabId = null;
  task.activeWindowId = null;
  task.waitingForLoad = false;
  await openNextProfile(task);
}

async function finalizeProfileTask(task, stopped, goalReached = false) {
  task.status = stopped ? "stopped" : "completed";
  const removedText = task.removedCount ? `，已删除 ${task.removedCount} 条男性记录` : "";
  task.message = stopped
    ? `已停止主页资料补充：已补充 ${task.enrichedCount} 条${removedText}`
    : goalReached
      ? `达到目标：已确认 ${task.femaleCount} 位女性用户，停止补充主页资料${removedText}`
      : `主页资料补充完成：已补充 ${task.enrichedCount}/${task.targets.length} 条${removedText}`;
  task.completedAt = Date.now();
  await setProfileTask(task, {
    scanProgress: { phase: task.status, message: task.message, current: task.currentIndex, total: task.targets.length, matched: task.enrichedCount, updatedAt: Date.now() }
  });
}

async function beginProfileEnrichment(removeConfirmedMen = true, targetFemaleCount = 0) {
  const { rows, scanTask, profileTask } = await chrome.storage.local.get({ rows: [], scanTask: null, profileTask: null });
  if (scanTask?.status === "running") throw new Error("请等待评论扫描结束后，再补充主页资料。");
  if (profileTask?.status === "running") throw new Error("主页资料补充正在进行中。");
  const targetMap = new Map();
  for (const row of rows || []) {
    if (!row.profile || targetMap.has(row.profile)) continue;
    if (row.gender && row.gender !== "未知" && row.profileAge && row.profileLocation) continue;
    if (!["douyin", "xhs"].includes(platformFromUrl(row.profile))) continue;
    targetMap.set(row.profile, { url: row.profile, nickname: row.nickname || "" });
  }
  const targets = [...targetMap.values()];
  if (!targets.length) throw new Error("没有可补充的主页链接。请先完成评论扫描。");

  const task = {
    id: `profile-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    kind: "profile",
    status: "running",
    targets,
    currentIndex: 0,
    activeTabId: null,
    activeWindowId: null,
    waitingForLoad: false,
    enrichedCount: 0,
    removedCount: 0,
    femaleCount: new Set((rows || []).filter(row => row.gender === "女").map(row => row.profile).filter(Boolean)).size,
    targetFemaleCount: Math.max(0, Number(targetFemaleCount || 0)),
    removeConfirmedMen: removeConfirmedMen !== false,
    errors: [],
    stopRequested: false,
    startedAt: Date.now(),
    message: "准备补充主页公开资料..."
  };
  await setProfileTask(task);
  await openNextProfile(task);
  return task;
}

chrome.tabs.onUpdated.addListener(async (tabId, changeInfo) => {
  if (changeInfo.status !== "complete") return;
  const { scanTask: task, profileTask } = await chrome.storage.local.get({ scanTask: null, profileTask: null });
  if (task?.status === "running" && task.waitingForLoad && task.activeTabId === tabId) {
    task.waitingForLoad = false;
    await setTask(task);
    await startCurrentPage(task);
  }
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "COMMENT_SCAN_PAGE_DONE") {
    finishPage(message.taskId, message.result || {}).catch(() => {});
    sendResponse({ ok: true });
    return false;
  }

  if (message?.type === "VERIFY_PROFILES_DURING_SCAN") {
    verifyProfilesDuringScan(message.taskId, message.targets)
      .then(result => sendResponse({ ok: true, ...result }))
      .catch(error => sendResponse({ ok: false, error: error.message || "主页验证失败。" }));
    return true;
  }

  if (message?.type === "START_SCAN_TASK") {
    beginTask(message.filters || {}, message.activeTab || null)
      .then(task => sendResponse({ ok: true, task }))
      .catch(error => sendResponse({ ok: false, error: error.message || "无法开始扫描。" }));
    return true;
  }

  if (message?.type === "STOP_SCAN_TASK") {
    stopTask().then(sendResponse).catch(error => sendResponse({ ok: false, error: error.message || "停止失败。" }));
    return true;
  }

  if (message?.type === "START_PROFILE_ENRICHMENT") {
    beginProfileEnrichment(message.removeConfirmedMen, message.targetFemaleCount)
      .then(task => sendResponse({ ok: true, task }))
      .catch(error => sendResponse({ ok: false, error: error.message || "无法补充主页资料。" }));
    return true;
  }

  if (message?.type === "REMOVE_CONFIRMED_MALE_ROWS") {
    chrome.storage.local.get({ rows: [] })
      .then(async ({ rows }) => {
        const nextRows = (rows || []).filter(row => row.gender !== "男");
        const removed = (rows || []).length - nextRows.length;
        await chrome.storage.local.set({ rows: nextRows, output: formatRows(nextRows) });
        sendResponse({ ok: true, removed });
      })
      .catch(error => sendResponse({ ok: false, error: error.message || "删除失败。" }));
    return true;
  }

  return false;
});
