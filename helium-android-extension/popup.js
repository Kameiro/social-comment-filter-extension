const defaults = {
  keyword: "眼线",
  excludeKeyword: "",
  region: "北京",
  matchMode: "all",
  relativeDays: 30,
  scrollLimit: 0,
  targetFemaleCount: 0,
  scanMode: "append",
  postUrls: "",
  feishuAppId: "",
  feishuAppSecret: "",
  feishuSpreadsheet: "",
  feishuSheetId: "",
  feishuIncludeHeader: true,
  removeConfirmedMen: true,
  rows: [],
  output: ""
};

const $ = selector => document.querySelector(selector);

function isAndroidRuntime() {
  return /Android/i.test(navigator.userAgent || "");
}

function latestTask(scanTask, profileTask) {
  if (scanTask?.status === "running") return scanTask;
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
    scrollLimit: Math.max(0, Math.min(10000, Number($("#scrollLimit").value || 0))),
    targetFemaleCount: android ? 0 : Math.max(0, Math.min(10000, Number($("#targetFemaleCount").value || 0))),
    scanMode: $("#scanMode").value,
    postUrls: $("#postUrls").value.trim(),
    feishuAppId: $("#feishuAppId").value.trim(),
    feishuAppSecret: $("#feishuAppSecret").value.trim(),
    feishuSpreadsheet: $("#feishuSpreadsheet").value.trim(),
    feishuSheetId: $("#feishuSheetId").value.trim(),
    feishuIncludeHeader: $("#feishuIncludeHeader").checked,
    removeConfirmedMen: android ? false : $("#removeConfirmedMen").checked
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

function startProgressPolling() {
  return setInterval(async () => {
    const { scanProgress, scanTask, profileTask, output } = await chrome.storage.local.get({ scanProgress: null, scanTask: null, profileTask: null, output: "" });
    const task = latestTask(scanTask, profileTask);
    const running = task?.status === "running";
    if (task?.message || scanProgress?.message) {
      setStatus(running ? (scanProgress?.message || task?.message) : (task?.message || scanProgress?.message));
    }
    if (output) $("#output").value = output;
    $("#scan").disabled = running;
    $("#enrichProfiles").disabled = running;
    $("#removeMen").disabled = running;
    $("#stop").disabled = !running;
  }, 500);
}

async function saveForm() {
  await chrome.storage.local.set(getFormValues());
}

async function loadForm() {
  const values = await chrome.storage.local.get(defaults);
  $("#keyword").value = values.keyword;
  $("#excludeKeyword").value = values.excludeKeyword;
  $("#region").value = values.region;
  $("#matchMode").value = values.matchMode;
  $("#relativeDays").value = values.relativeDays;
  $("#scrollLimit").value = values.scrollLimit;
  $("#targetFemaleCount").value = values.targetFemaleCount;
  $("#scanMode").value = values.scanMode;
  $("#postUrls").value = values.postUrls;
  $("#feishuAppId").value = values.feishuAppId;
  $("#feishuAppSecret").value = values.feishuAppSecret;
  $("#feishuSpreadsheet").value = values.feishuSpreadsheet;
  $("#feishuSheetId").value = values.feishuSheetId;
  $("#feishuIncludeHeader").checked = Boolean(values.feishuIncludeHeader);
  $("#removeConfirmedMen").checked = Boolean(values.removeConfirmedMen);
  $("#output").value = values.output || formatRows(values.rows || []);
  if (isAndroidRuntime()) {
    document.body.classList.add("android-runtime");
    setStatus("安卓模式：支持评论抓取、筛选、复制和飞书写入；主页资料核验仅支持电脑端。");
  }
}

function parseFeishuSpreadsheet(value) {
  const input = value.trim();
  if (!input) return { token: "", sheetId: "", wikiToken: "" };

  try {
    const url = new URL(input);
    const tokenMatch = url.pathname.match(/\/sheets\/([^/?#]+)/);
    const wikiMatch = url.pathname.match(/\/wiki\/([^/?#]+)/);
    return {
      token: tokenMatch?.[1] || input,
      sheetId: url.searchParams.get("sheet") || "",
      wikiToken: wikiMatch?.[1] || ""
    };
  } catch (error) {
    return { token: input, sheetId: "", wikiToken: "" };
  }
}

async function getFeishuTenantToken(appId, appSecret) {
  if (!appId || !appSecret) throw new Error("请填写飞书 App ID 和 App Secret。");

  const response = await fetch("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      app_id: appId,
      app_secret: appSecret
    })
  });
  const result = await response.json().catch(() => ({}));

  if (!response.ok || result.code !== 0 || !result.tenant_access_token) {
    throw new Error(result.msg || result.message || "获取飞书访问令牌失败。");
  }

  return result.tenant_access_token;
}

function columnName(index) {
  let name = "";
  let value = index + 1;
  while (value > 0) {
    const mod = (value - 1) % 26;
    name = String.fromCharCode(65 + mod) + name;
    value = Math.floor((value - mod) / 26);
  }
  return name;
}

async function feishuApi(path, token, options = {}, operation = "") {
  const response = await fetch(`https://open.feishu.cn/open-apis${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${token}`,
      ...(options.headers || {})
    }
  });
  const result = await response.json().catch(() => ({}));

  if (!response.ok || result.code !== 0) {
    const error = new Error(result.msg || result.message || "飞书接口请求失败。");
    error.code = result.code;
    error.httpStatus = response.status;
    error.operation = operation;
    error.path = path;
    throw error;
  }

  return result;
}

async function readFeishuHeader(spreadsheetToken, sheetId, token) {
  const result = await feishuApi(
    `/sheets/v2/spreadsheets/${encodeURIComponent(spreadsheetToken)}/values/${encodeURIComponent(`${sheetId}!A1:Z1`)}`,
    token
  );
  const values = result.data?.valueRange?.values || result.data?.value_range?.values || [];
  return (values[0] || []).map(value => String(value || "").trim());
}

async function resolveWikiNodeToken(wikiToken, token) {
  if (!wikiToken) return {};

  const result = await feishuApi(
    `/wiki/v2/spaces/get_node?token=${encodeURIComponent(wikiToken)}`,
    token
  );
  const node = result.data?.node || result.data || {};
  return {
    objToken: node.obj_token || node.objToken || "",
    objType: node.obj_type || node.objType || ""
  };
}

async function readSpreadsheetSheets(spreadsheetToken, token) {
  const result = await feishuApi(
    `/sheets/v3/spreadsheets/${encodeURIComponent(spreadsheetToken)}/sheets/query`,
    token
  );
  return result.data?.sheets || result.data?.items || [];
}

function rowsToHeaderMappedValues(rows, headers) {
  return rows.map((row, index) => {
    const valuesByHeader = rowToSheetValues(row, index);
    return headers.map(header => valuesByHeader[header] ?? "");
  });
}

async function appendFeishuSheetRows(config, rows) {
  const parsed = parseFeishuSpreadsheet(config.feishuSpreadsheet);
  if (!rows.length) throw new Error("没有可写入的结果，请先扫描。");

  const token = await getFeishuTenantToken(config.feishuAppId, config.feishuAppSecret);
  let spreadsheetToken = parsed.wikiToken ? "" : parsed.token;
  let sheetId = config.feishuSheetId || parsed.sheetId;

  if (parsed.wikiToken) {
    const node = await resolveWikiNodeToken(parsed.wikiToken, token);
    if (node.objType && node.objType !== "sheet") {
      throw new Error(`这个 wiki 链接不是普通表格，当前类型是 ${node.objType}。`);
    }
    spreadsheetToken = node.objToken;
  }

  if (!spreadsheetToken) throw new Error("没有识别到飞书普通表格 token，请粘贴表格链接。");

  if (!sheetId) {
    const sheets = await readSpreadsheetSheets(spreadsheetToken, token);
    const firstSheet = sheets[0] || {};
    sheetId = firstSheet.sheet_id || firstSheet.sheetId || "";
  }

  if (!sheetId) throw new Error("没有识别到工作表页签，请在飞书里打开具体 Sheet 后复制链接，或手动填写工作表 ID。");

  let headers = await readFeishuHeader(spreadsheetToken, sheetId, token);
  const hasExistingHeader = headers.length > 0 && headers.some(header => header);
  if (!hasExistingHeader) {
    headers = sheetHeader();
  }

  const values = rowsToHeaderMappedValues(rows, headers);
  if (config.feishuIncludeHeader && !hasExistingHeader) values.unshift(headers);

  const endColumn = columnName(Math.max(headers.length - 1, 0));
  const endRow = Math.max(values.length, 1);
  const response = await fetch(`https://open.feishu.cn/open-apis/sheets/v2/spreadsheets/${encodeURIComponent(spreadsheetToken)}/values_append`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${token}`
    },
    body: JSON.stringify({
      valueRange: {
        range: `${sheetId}!A1:${endColumn}${endRow}`,
        values
      }
    })
  });
  const result = await response.json().catch(() => ({}));

  if (!response.ok || result.code !== 0) {
    throw new Error(result.msg || result.message || "写入飞书表格失败，请检查应用权限和表格权限。");
  }

  return result;
}

async function writeFeishuRows(config, rows) {
  await appendFeishuSheetRows(config, rows);
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
      files: [contentFileForPlatform(platform)]
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
    throw new Error("请先切换到抖音或小红书帖子页面。");
  }

  try {
    return await chrome.tabs.sendMessage(tab.id, message);
  } catch (error) {
    await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      files: [contentFileForPlatform(platform)]
    });
    return chrome.tabs.sendMessage(tab.id, message);
  }
}

function platformFromUrl(url) {
  if (/xiaohongshu\.com/.test(url || "")) return "xhs";
  if (/douyin\.com/.test(url || "")) return "douyin";
  return "";
}

function contentFileForPlatform(platform) {
  return platform === "douyin" ? "douyin_content.js" : "xhs_content.js";
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
      files: [contentFileForPlatform(platform)]
    });
    return chrome.tabs.sendMessage(tabId, message);
  }
}

async function scanPostUrl(url, filters, index, total) {
  const platform = platformFromUrl(url);
  if (!platform) throw new Error("不是支持的抖音或小红书链接");
  const tab = await chrome.tabs.create({ url, active: true });
  try {
    await waitForTabLoaded(tab.id, 25000);
    await new Promise(resolve => setTimeout(resolve, platform === "douyin" ? 3000 : 2200));
    setStatus(`批量扫描中：${index}/${total} ${platform === "douyin" ? "抖音" : "小红书"}`);
    const result = await sendToTab(tab.id, { type: messageType(platform, "scan"), filters: { ...filters, postUrl: url } }, platform);
    if (result?.error) throw new Error(result.error);
    return (result?.rows || []).map(row => ({ ...row, postUrl: row.postUrl || url }));
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
    return { rows: (result?.rows || []).map(row => ({ ...row, postUrl: row.postUrl || tab?.url || "" })), stopped: result?.stopped };
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

$("#stop").addEventListener("click", async () => {
  $("#stop").disabled = true;
  setStatus("正在停止，已扫到的结果会保留...");

  try {
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
      removeConfirmedMen: values.removeConfirmedMen,
      targetFemaleCount: values.targetFemaleCount
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

$("#writeFeishuSheet").addEventListener("click", async () => {
  const button = $("#writeFeishuSheet");
  button.disabled = true;
  setStatus("正在写入飞书表格...");

  try {
    const values = getFormValues();
    await saveForm();
    const stored = await chrome.storage.local.get(defaults);
    const rows = stored.rows || [];
    await writeFeishuRows(values, rows);
    setStatus(`已写入飞书，共 ${rows.length} 行。`);
  } catch (error) {
    setStatus(error.message || "写入飞书表格失败。");
  } finally {
    button.disabled = false;
  }
});

$("#clear").addEventListener("click", async () => {
  $("#output").value = "";
  await chrome.storage.local.set({ rows: [], output: "" });
  setStatus("结果已清空。");
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
  "scrollLimit",
  "targetFemaleCount",
  "scanMode",
  "postUrls",
  "feishuAppId",
  "feishuAppSecret",
  "feishuSpreadsheet",
  "feishuSheetId",
  "feishuIncludeHeader",
  "removeConfirmedMen"
].forEach(id => {
  $(`#${id}`).addEventListener("change", saveForm);
});

loadForm();
startProgressPolling();
