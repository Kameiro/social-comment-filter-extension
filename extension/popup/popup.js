const defaults = {
  keyword: "眼线",
  excludeKeyword: "",
  region: "北京",
  matchMode: "all",
  relativeDays: 30,
  pageLimit: 0,
  genderFilter: "all",
  targetGenderCount: 0,
  scanMode: "append",
  postUrls: "",
  removeConfirmedMen: true,
  rows: [],
  output: ""
};

const $ = selector => document.querySelector(selector);

function isAndroidRuntime() {
  return /Android/i.test(navigator.userAgent || "");
}

function latestTask(scanTask, profileTask) {
  if (scanTask?.status === "running" || scanTask?.status === "waiting-verification") return scanTask;
  if (profileTask?.status === "running") return profileTask;
  if (!scanTask) return profileTask;
  if (!profileTask) return scanTask;
  return (profileTask.completedAt || 0) > (scanTask.completedAt || 0) ? profileTask : scanTask;
}

function setStatus(message) {
  const status = $("#status");
  const text = String(message || "");
  const urlPattern = /(https?:\/\/[^\s，。；,;]+)/g;
  let lastIndex = 0;

  status.textContent = "";
  for (const match of text.matchAll(urlPattern)) {
    if (match.index > lastIndex) {
      status.append(document.createTextNode(text.slice(lastIndex, match.index)));
    }

    const link = document.createElement("a");
    link.href = match[0];
    link.target = "_blank";
    link.rel = "noreferrer";
    link.textContent = match[0];
    status.append(link);
    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < text.length) {
    status.append(document.createTextNode(text.slice(lastIndex)));
  }
}

async function getActiveTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab;
}

function getFormValues() {
  const android = isAndroidRuntime();
  return {
    keyword: $("#keyword").value.trim(),
    excludeKeyword: $("#excludeKeyword").value.trim(),
    region: $("#region").value.trim(),
    matchMode: $("#matchMode").value,
    relativeDays: Math.max(1, Math.min(365, Number($("#relativeDays").value || 30))),
    scrollLimit: Math.max(0, Math.min(10000, Number($("#pageLimit").value || 0))),
    genderFilter: android ? "all" : $("#genderFilter").value,
    targetGenderCount: android ? 0 : Math.max(0, Math.min(10000, Number($("#targetGenderCount").value || 0))),
    scanMode: "append",
    postUrls: $("#postUrls").value.trim(),
    removeConfirmedMen: android ? false : $("#genderFilter").value === "female"
  };
}

function rowKey(row) {
  return [row.postUrl || "", row.profile || row.possibleProfile || "", row.nickname || "", row.rawTimeText || "", row.text || ""].join("||");
}

function mergeRows(existingRows, newRows, mode) {
  const map = new Map();

  if (mode === "append") {
    for (const row of existingRows || []) map.set(rowKey(row), row);
  }

  for (const row of newRows || []) map.set(rowKey(row), row);
  return [...map.values()];
}

function sheetHeader() {
  return ["序号", "帖子链接", "昵称", "性别", "年龄线索", "主页所在地", "推算日期", "原始时间", "IP属地", "评论获赞", "回复数量", "主页链接", "疑似主页链接", "评论内容"];
}

function rowToSheetValues(row, index) {
  return {
    "序号": index + 1,
    "帖子链接": row.postUrl || "",
    "昵称": row.nickname || "",
    "性别": row.gender || "未知",
    "年龄线索": row.profileAge || "",
    "主页所在地": row.profileLocation || "",
    "推算日期": row.dateText || "",
    "原始时间": row.rawTimeText || "",
    "IP属地": row.ipRegion || "",
    "评论获赞": row.likeCount || "0",
    "回复数量": row.replyCount || "0",
    "主页链接": row.profile || "",
    "疑似主页链接": row.profile ? "" : row.possibleProfile || "",
    "评论内容": row.text || ""
  };
}

function formatRows(rows) {
  const header = sheetHeader().join("\t");
  return rows.length
    ? [
        header,
        ...rows.map((row, index) => [
        index + 1,
        row.postUrl || "",
        row.nickname,
        row.gender || "未知",
        row.profileAge || "",
        row.profileLocation || "",
        row.dateText,
        row.rawTimeText,
        row.ipRegion,
        row.likeCount || "0",
        row.replyCount || "0",
        row.profile || "",
        row.profile ? "" : row.possibleProfile || "",
        row.text
      ].join("\t"))
      ].join("\n")
    : header;
}

function normalizeStoredRow(row, fallbackUrl = "") {
  const info = globalThis.CommentFilterPostUtils?.getPostInfo(row?.postUrl || fallbackUrl, null);
  if (!info?.url) return row;
  return {
    ...row,
    postUrl: info.url,
    sourcePostUrl: row.sourcePostUrl || info.sourceUrl || ""
  };
}

function startProgressPolling() {
  return setInterval(async () => {
    const { scanProgress, scanTask, profileTask, output } = await chrome.storage.local.get({ scanProgress: null, scanTask: null, profileTask: null, output: "" });
    const task = latestTask(scanTask, profileTask);
    const running = task?.status === "running" || task?.status === "waiting-verification";
    if (task?.message || scanProgress?.message) {
      setStatus(running ? (scanProgress?.message || task?.message) : (task?.message || scanProgress?.message));
    }
    if (output) $("#output").value = output;
    $("#scan").disabled = running;
    $("#enrichProfiles").disabled = running;
    $("#removeMen").disabled = running;
    $("#stop").disabled = !running;
    $("#scan").textContent = task?.status === "waiting-verification" ? "等待验证" : "开始扫描";
    $("#resumeVerification").hidden = task?.status !== "waiting-verification";
    $("#resumeVerification").disabled = task?.status !== "waiting-verification";
  }, 500);
}

async function saveForm() {
  await chrome.storage.local.set(getFormValues());
}

async function loadForm() {
  const stored = await chrome.storage.local.get(null);
  const values = { ...defaults, ...stored };
  const genderFilter = values.genderFilter || (values.removeConfirmedMen ? "female" : "all");
  const targetGenderCount = values.targetGenderCount ?? values.targetFemaleCount ?? 0;
  $("#keyword").value = values.keyword;
  $("#excludeKeyword").value = values.excludeKeyword;
  $("#region").value = values.region;
  $("#matchMode").value = values.matchMode;
  $("#relativeDays").value = values.relativeDays;
  $("#pageLimit").value = values.scrollLimit ?? values.pageLimit ?? 0;
  $("#genderFilter").value = genderFilter;
  $("#targetGenderCount").value = targetGenderCount;
  $("#postUrls").value = values.postUrls;
  const rows = (values.rows || []).map(row => normalizeStoredRow(row));
  const output = formatRows(rows);
  $("#output").value = output;
  if (JSON.stringify(rows) !== JSON.stringify(values.rows || []) || output !== (values.output || "")) {
    await chrome.storage.local.set({ rows, output });
  }
  if (isAndroidRuntime()) {
    document.body.classList.add("android-runtime");
    setStatus("安卓模式：支持评论抓取、筛选和复制；主页资料核验仅支持电脑端。");
  }
}

function waitForTabLoaded(tabId, timeoutMs = 15000) {
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      chrome.tabs.onUpdated.removeListener(listener);
      resolve(false);
    }, timeoutMs);

    function listener(updatedTabId, changeInfo) {
      if (updatedTabId !== tabId || changeInfo.status !== "complete") return;
      clearTimeout(timer);
      chrome.tabs.onUpdated.removeListener(listener);
      resolve(true);
    }

    chrome.tabs.onUpdated.addListener(listener);
  });
}

async function extractProfileInfoFromUrl(url) {
  const platform = platformFromUrl(url);
  const tab = await chrome.tabs.create({ url, active: false });
  try {
    await waitForTabLoaded(tab.id);
    await new Promise(resolve => setTimeout(resolve, 1200));
    await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      files: ["content/common/post_utils.js", "content/common/comment_api.js", contentFileForPlatform(platform)]
    });
    const result = await chrome.tabs.sendMessage(tab.id, { type: messageType(platform, "profile") });
    if (result?.error) throw new Error(result.error);
    return result || {};
  } finally {
    if (tab?.id) chrome.tabs.remove(tab.id);
  }
}

async function enrichRowsFromProfiles(rows, onProgress) {
  const updatedRows = [];

  for (let index = 0; index < rows.length; index += 1) {
    const row = { ...rows[index] };
    const url = row.profile || row.possibleProfile || "";

    if (!url) {
      updatedRows.push(row);
      continue;
    }

    onProgress?.(index + 1, rows.length, row.nickname || "");

    try {
      const info = await extractProfileInfoFromUrl(url);
      row.gender = info.gender && info.gender !== "未知" ? info.gender : row.gender || "未知";
      row.profileLocation = info.profileLocation || row.profileLocation || "";
      row.profileAge = info.profileAge || row.profileAge || "";
    } catch (error) {
      row.gender = row.gender || "未知";
    }

    updatedRows.push(row);
  }

  return updatedRows;
}

async function sendToPage(message) {
  const tab = await getActiveTab();
  const platform = platformFromUrl(tab?.url || "");
  if (!tab?.id || !platform) {
    throw new Error("请先切换到抖音、小红书或快手帖子页面。");
  }

  try {
    return await chrome.tabs.sendMessage(tab.id, message);
  } catch (error) {
    await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      files: ["content/common/post_utils.js", "content/common/comment_api.js", contentFileForPlatform(platform)]
    });
    return chrome.tabs.sendMessage(tab.id, message);
  }
}

function platformFromUrl(url) {
  if (/xiaohongshu\.com/.test(url || "")) return "xhs";
  if (/douyin\.com/.test(url || "")) return "douyin";
  if (/kuaishou\.com/.test(url || "")) return "kuaishou";
  return "";
}

function contentFileForPlatform(platform) {
  if (platform === "douyin") return "content/platforms/douyin.js";
  if (platform === "kuaishou") return "content/platforms/kuaishou.js";
  return "content/platforms/xhs.js";
}

function messageType(platform, action) {
  const types = {
    xhs: {
      scan: "XHS_SCAN_COMMENTS_V2",
      stop: "XHS_STOP_SCAN_V2",
      diagnose: "XHS_DIAGNOSE_CONTAINER_V2",
      profile: "XHS_EXTRACT_PROFILE_PUBLIC_INFO_V2"
    },
    douyin: {
      scan: "DY_SCAN_COMMENTS_V27",
      stop: "DY_STOP_SCAN_V27",
      diagnose: "DY_DIAGNOSE_CONTAINER_V27",
      profile: "DY_EXTRACT_PROFILE_PUBLIC_INFO_V27"
    },
    kuaishou: {
      scan: "KS_SCAN_COMMENTS_V1",
      stop: "KS_STOP_SCAN_V1",
      diagnose: "KS_DIAGNOSE_CONTAINER_V1",
      profile: "KS_EXTRACT_PROFILE_PUBLIC_INFO_V1"
    }
  };
  return types[platform]?.[action] || "";
}

function normalizePostUrl(input) {
  try {
    const url = new URL(input);
    return url.href;
  } catch (error) {
    return "";
  }
}

function parsePostUrls(value) {
  return String(value || "")
    .split(/[\n,，\s]+/)
    .map(item => item.trim())
    .filter(Boolean)
    .map(normalizePostUrl)
    .filter(url => platformFromUrl(url));
}

async function sendToTab(tabId, message, platform) {
  try {
    return await chrome.tabs.sendMessage(tabId, message);
  } catch (error) {
    await chrome.scripting.executeScript({
      target: { tabId },
      files: ["content/common/post_utils.js", "content/common/comment_api.js", contentFileForPlatform(platform)]
    });
    return chrome.tabs.sendMessage(tabId, message);
  }
}

async function scanPostUrl(url, filters, index, total) {
  const platform = platformFromUrl(url);
  if (!platform) throw new Error("不是支持的抖音、小红书或快手链接");
  const tab = await chrome.tabs.create({ url, active: true });
  try {
    await waitForTabLoaded(tab.id, 25000);
    await new Promise(resolve => setTimeout(resolve, platform === "douyin" ? 3000 : 2200));
    const platformName = platform === "douyin" ? "抖音" : platform === "xhs" ? "小红书" : "快手";
    setStatus(`批量扫描中：${index}/${total} ${platformName}`);
    const result = await sendToTab(tab.id, { type: messageType(platform, "scan"), filters: { ...filters, postUrl: url } }, platform);
    if (result?.error) throw new Error(result.error);
    const postInfo = globalThis.CommentFilterPostUtils?.getPostInfo(result?.postUrl || url, null) || {};
    const postUrl = postInfo.url || result?.postUrl || url;
    return (result?.rows || []).map(row => ({
      ...row,
      postUrl,
      sourcePostUrl: row.sourcePostUrl || result?.sourceUrl || postInfo.sourceUrl || "",
      postTitle: row.postTitle || result?.title || ""
    }));
  } finally {
    if (tab?.id) chrome.tabs.remove(tab.id);
  }
}

async function scanByFilters(filters) {
  const urls = parsePostUrls(filters.postUrls);
  if (!urls.length) {
    const tab = await getActiveTab();
    const platform = platformFromUrl(tab?.url || "");
    const result = await sendToPage({ type: messageType(platform, "scan"), filters: { ...filters, postUrl: tab?.url || "" } });
    if (result?.error) throw new Error(result.error);
    const postInfo = globalThis.CommentFilterPostUtils?.getPostInfo(result?.postUrl || tab?.url || "", null) || {};
    const postUrl = postInfo.url || result?.postUrl || tab?.url || "";
    return {
      rows: (result?.rows || []).map(row => ({
        ...row,
        postUrl,
        sourcePostUrl: row.sourcePostUrl || result?.sourceUrl || postInfo.sourceUrl || "",
        postTitle: row.postTitle || result?.title || ""
      })),
      stopped: result?.stopped
    };
  }

  const rows = [];
  const errors = [];
  for (let index = 0; index < urls.length; index += 1) {
    const stopped = (await chrome.storage.local.get({ stopRequested: false })).stopRequested;
    if (stopped) return { rows, stopped: true, errors };
    try {
      const postRows = await scanPostUrl(urls[index], filters, index + 1, urls.length);
      rows.push(...postRows);
    } catch (error) {
      errors.push({
        url: urls[index],
        message: error.message || "扫描失败"
      });
    }
  }
  return { rows, stopped: false, errors };
}

$("#scan").addEventListener("click", async () => {
  const scanButton = $("#scan");
  const stopButton = $("#stop");
  scanButton.disabled = true;
  stopButton.disabled = false;
  setStatus("准备中：任务将继续在后台运行...");

  try {
    const filters = getFormValues();
    await saveForm();
    const activeTab = await getActiveTab();
    const response = await chrome.runtime.sendMessage({ type: "START_SCAN_TASK", filters, activeTab });
    if (!response?.ok) throw new Error(response?.error || "无法开始扫描。");
    setStatus("任务已开始。现在可以关闭插件面板，网页右侧会保留任务浮窗。");
  } catch (error) {
    setStatus(error.message || "扫描失败。");
    scanButton.disabled = false;
    stopButton.disabled = true;
  }
});

$("#resumeVerification").addEventListener("click", async () => {
  const button = $("#resumeVerification");
  button.disabled = true;
  setStatus("正在检查验证状态并继续...");
  try {
    const result = await chrome.runtime.sendMessage({ type: "RESUME_SCAN_AFTER_VERIFICATION" });
    if (!result?.ok) throw new Error(result?.error || "验证后继续失败。");
    setStatus(result.message || "已继续当前评论任务。");
  } catch (error) {
    button.disabled = false;
    setStatus(`继续失败：${error.message || error}`);
  }
});

$("#stop").addEventListener("click", async () => {
  $("#stop").disabled = true;
  setStatus("正在停止，已扫到的结果会保留...");

  try {
    // Write the shared stop flag first so the page can stop even if Android delays the service worker message.
    await chrome.storage.local.set({ stopRequested: true });
    const tab = await getActiveTab();
    const platform = platformFromUrl(tab?.url || "");
    if (tab?.id && platform) {
      await chrome.tabs.sendMessage(tab.id, { type: messageType(platform, "stop") }).catch(() => {});
    }
    const result = await chrome.runtime.sendMessage({ type: "STOP_SCAN_TASK" });
    if (!result?.ok) throw new Error(result?.error || "停止失败。");
  } catch (error) {
    setStatus("已发送停止请求。");
  }
});

$("#enrichProfiles").addEventListener("click", async () => {
  const button = $("#enrichProfiles");
  button.disabled = true;
  setStatus("准备在后台补充主页公开资料...");
  try {
    const values = getFormValues();
    await saveForm();
    const response = await chrome.runtime.sendMessage({
      type: "START_PROFILE_ENRICHMENT",
      genderFilter: values.genderFilter,
      targetGenderCount: values.targetGenderCount
    });
    if (!response?.ok) throw new Error(response?.error || "无法开始补充。");
    setStatus("主页资料补充已开始。可以关闭插件面板，网页右侧浮窗会显示进度。");
  } catch (error) {
    setStatus(error.message || "补充主页资料失败。");
    button.disabled = false;
  }
});

$("#removeMen").addEventListener("click", async () => {
  const button = $("#removeMen");
  button.disabled = true;
  try {
    const response = await chrome.runtime.sendMessage({ type: "REMOVE_CONFIRMED_MALE_ROWS" });
    if (!response?.ok) throw new Error(response?.error || "删除失败。");
    const stored = await chrome.storage.local.get({ output: "" });
    $("#output").value = stored.output || "";
    setStatus(`已删除 ${response.removed || 0} 条已确认男性记录。`);
  } catch (error) {
    setStatus(error.message || "删除男性记录失败。");
  } finally {
    button.disabled = false;
  }
});

$("#copy").addEventListener("click", async () => {
  const text = $("#output").value;
  await navigator.clipboard.writeText(text);
  setStatus("已复制结果。");
});

$("#openDashboard").addEventListener("click", async () => {
  try {
    await chrome.tabs.create({ url: chrome.runtime.getURL("dashboard/dashboard.html") });
    setStatus("评论数据库已打开。");
  } catch (error) {
    setStatus(error.message || "打开评论数据库失败。");
  }
});

$("#diagnose").addEventListener("click", async () => {
  const button = $("#diagnose");
  button.disabled = true;
  setStatus("正在诊断评论容器...");

  try {
    const tab = await getActiveTab();
    const platform = platformFromUrl(tab?.url || "");
    const result = await sendToPage({ type: messageType(platform, "diagnose") });
    if (result?.error) throw new Error(result.error);
    const output = JSON.stringify(result, null, 2);
    $("#output").value = output;
    await chrome.storage.local.set({ output });
    setStatus("诊断完成，请把结果贴给我。");
  } catch (error) {
    setStatus(error.message || "诊断失败。");
  } finally {
    button.disabled = false;
  }
});

[
  "keyword",
  "excludeKeyword",
  "region",
  "matchMode",
  "relativeDays",
  "pageLimit",
  "genderFilter",
  "targetGenderCount",
  "postUrls"
].forEach(id => {
  $(`#${id}`).addEventListener("change", saveForm);
});

loadForm();
startProgressPolling();
