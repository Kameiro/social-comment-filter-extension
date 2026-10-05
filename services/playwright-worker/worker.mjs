import http from "node:http";
import { existsSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import "../../extension/content/common/profile_metadata.js";
import { browserConnectionConfig, cdpEndpoints } from "./browser_connection.mjs";
import {
  canonicalXhsPostId,
  mergeSearchItem,
  normalizeDouyinSearchItem,
  normalizeXhsSearchItem
} from "./discovery_utils.mjs";

const root = dirname(fileURLToPath(import.meta.url));
const dataDir = resolve(root, "browser-data");
const discoveryDataDir = resolve(root, "browser-data-discovery");
const port = Number(process.env.COMMENT_FILTER_PORT || 38765);
const host = "127.0.0.1";
const workerToken = process.env.COMMENT_FILTER_TOKEN || "cfw_7d2f4c9a11b84e6fb0a1d9c3e8f6a2b7";
const browserConfig = browserConnectionConfig();
mkdirSync(dataDir, { recursive: true });

function sendJson(response, status, payload) {
  if (response.writableEnded || response.destroyed) return;
  response.statusCode = status;
  response.setHeader("content-type", "application/json; charset=utf-8");
  response.end(JSON.stringify(payload));
}

function platformFromUrl(url) {
  if (/douyin\.com/.test(url || "")) return "douyin";
  if (/xiaohongshu\.com/.test(url || "")) return "xhs";
  return "";
}

function loginUrlFor(platform) {
  return platform === "xhs" ? "https://www.xiaohongshu.com/" : "https://www.douyin.com/";
}

function loginCookieNames(platform) {
  return platform === "xhs"
    ? ["web_session"]
    : ["sessionid", "sessionid_ss"];
}

async function hasLoginCookie(context, platform) {
  const cookies = await context.cookies([loginUrlFor(platform)]);
  return cookies.some(cookie => loginCookieNames(platform).includes(cookie.name) && cookie.value);
}

let browserContext = null;
let browserConnection = null;
let browserMode = "starting";
let loginPromise = null;
let discoveryContext = null;
let verificationContext = null;
let discoveryRuntime = {
  status: "idle",
  runId: "",
  platform: "",
  keyword: "",
  page: 0,
  maxPages: 0,
  responses: 0,
  items: 0,
  startedAt: 0,
  updatedAt: 0,
  stopReason: "",
  error: ""
};

function updateDiscoveryRuntime(patch) {
  discoveryRuntime = { ...discoveryRuntime, ...patch, updatedAt: Date.now() };
}

async function launchBrowser(headless) {
  const context = await chromium.launchPersistentContext(dataDir, {
    headless,
    viewport: { width: 1280, height: 900 },
    locale: "zh-CN"
  });
  context.on("close", () => {
    if (browserContext === context) {
      browserContext = null;
      browserMode = "disconnected";
    }
  });
  return context;
}

async function ensureDiscoveryContext() {
  if (browserMode === "isolated" && browserContext) return browserContext;
  if (discoveryContext) {
    try {
      discoveryContext.pages();
      return discoveryContext;
    } catch (error) {
      discoveryContext = null;
    }
  }
  discoveryContext = await chromium.launchPersistentContext(discoveryDataDir, {
    headless: true,
    viewport: { width: 1280, height: 900 },
    locale: "zh-CN"
  });
  discoveryContext.on("close", () => {
    discoveryContext = null;
  });
  return discoveryContext;
}

function resetBrowserState(context = null) {
  if (context && browserContext !== context) return;
  browserContext = null;
  browserConnection = null;
  browserMode = "disconnected";
}

async function connectExistingBrowser() {
  if (browserConfig.mode === "isolated") return null;
  for (const endpoint of cdpEndpoints()) {
    try {
      const connection = await chromium.connectOverCDP(endpoint, { timeout: 5000 });
      const context = connection.contexts()[0];
      if (!context) throw new Error("已连接浏览器没有可用的浏览器上下文。");
      browserConnection = connection;
      browserContext = context;
      browserMode = "cdp";
      connection.on("disconnected", () => {
        if (browserConnection === connection) resetBrowserState(context);
      });
      console.log(`已通过 CDP 复用现有浏览器登录态：${endpoint}`);
      return context;
    } catch (error) {
      // CDP is optional in auto mode; isolated mode remains the fallback.
    }
  }
  if (browserConfig.mode === "cdp") {
    throw new Error("未能连接到现有浏览器。请开启远程调试，或设置 COMMENT_FILTER_CDP_URL。");
  }
  return null;
}

async function ensureHeadlessContext() {
  if (browserContext) {
    try {
      browserContext.pages();
      return browserContext;
    } catch (error) {
      resetBrowserState(browserContext);
    }
  }
  const sharedContext = await connectExistingBrowser();
  if (sharedContext) return sharedContext;
  browserContext = await launchBrowser(true);
  browserMode = "isolated";
  return browserContext;
}

async function withHeadlessContext(operation) {
  let lastError;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const context = await ensureHeadlessContext();
    try {
      return await operation(context);
    } catch (error) {
      lastError = error;
      if (!/Target page, context or browser has been closed/i.test(error?.message || "")) throw error;
      const shared = browserMode === "cdp";
      resetBrowserState(context);
      if (!shared) await context.close().catch(() => {});
    }
  }
  throw lastError;
}

async function waitForLogin(context, page, platform, timeoutMs = 10 * 60 * 1000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await hasLoginCookie(context, platform)) return true;
    await new Promise(resolve => setTimeout(resolve, 1500));
  }
  return false;
}

async function startInteractiveLogin(platform) {
  if (loginPromise) return false;
  loginPromise = (async () => {
    if (browserContext) await browserContext.close().catch(() => {});
    browserContext = await launchBrowser(false);
    const page = browserContext.pages()[0] || await browserContext.newPage();
    await page.goto(loginUrlFor(platform), { waitUntil: "domcontentloaded", timeout: 30000 }).catch(() => {});
    const loggedIn = await waitForLogin(browserContext, page, platform);
    await browserContext.close().catch(() => {});
    browserContext = await launchBrowser(true);
    return loggedIn;
  })().finally(() => {
    loginPromise = null;
  });
  return true;
}

function classifyGender(page) {
  return page.evaluate(globalThis.CommentFilterProfileMetadata.extractDouyinRenderedProfileInfo)
    .then(info => info?.gender || "未知");
}

function isDouyinProfileApi(url) {
  return /https?:\/\/www\.douyin\.com\/aweme\/v(?:1|2)\/web\/user\/profile\/other\/?(?:\?|$)/i.test(String(url || ""));
}

function isDouyinSearchApi(url) {
  return /https?:\/\/www\.douyin\.com\/aweme\/v(?:1|2)\/web\/(?:general\/search|search)/i.test(String(url || ""));
}

function isXhsSearchApi(url) {
  return /https?:\/\/(?:www|so|edith)\.xiaohongshu\.com\/api\/sns\/web\/v(?:1|2)\/search\/notes/i.test(String(url || ""));
}

function walkSearchObjects(value, seen = new Set()) {
  const result = [];
  if (!value || typeof value !== "object" || seen.has(value)) return result;
  seen.add(value);
  if (Array.isArray(value)) {
    for (const child of value) result.push(...walkSearchObjects(child, seen));
    return result;
  }
  result.push(value);
  for (const child of Object.values(value)) {
    if (child && typeof child === "object") result.push(...walkSearchObjects(child, seen));
  }
  return result;
}

function extractSearchItems(payload) {
  const result = new Map();
  for (const node of walkSearchObjects(payload)) {
    const item = node.aweme_info && typeof node.aweme_info === "object"
      ? node.aweme_info
      : node.aweme_detail && typeof node.aweme_detail === "object"
        ? node.aweme_detail
        : node.aweme_id && (node.author || node.desc) ? node : null;
    const id = String(item?.aweme_id || "");
    if (item && id && !result.has(id)) result.set(id, item);
  }
  return [...result.values()];
}

function extractXhsSearchItems(payload) {
  const result = new Map();
  for (const node of walkSearchObjects(payload)) {
    const card = node.note_card && typeof node.note_card === "object" ? node.note_card : node.noteCard;
    const item = card && typeof card === "object"
      ? { ...node, note_card: card }
      : node.note_id || node.noteId ? node : null;
    const id = canonicalXhsPostId(item || {});
    if (item && id) result.set(id, item);
  }
  return [...result.values()];
}

function extractSearchHasMore(payload) {
  for (const node of walkSearchObjects(payload)) {
    for (const key of ["has_more", "has_more_item", "hasMore", "hasMoreItem"]) {
      if (typeof node[key] === "boolean") return node[key];
      if (node[key] === 0 || node[key] === 1 || node[key] === "0" || node[key] === "1") return Boolean(Number(node[key]));
    }
  }
  return null;
}

function isSearchRisk(response, body) {
  return [401, 403, 429].includes(response.status()) || /captcha|verify|验证码|访问频繁|验证中心|风控|risk/i.test(String(body || ""));
}

async function inspectSearchPage(page) {
  return page.evaluate(() => {
    const title = String(document.title || "");
    const body = String(document.body?.innerText || document.body?.textContent || "");
    const markers = Array.from(document.querySelectorAll("[id], [class]"))
      .slice(0, 300)
      .map(node => `${node.id || ""} ${node.className || ""}`)
      .join(" ");
    const text = `${title}\n${body}\n${markers}`;
    const loginRequired = /登录后即可搜索|扫码登录|验证码登录|请输入手机号|获取验证码/i.test(`${title}\n${body}`);
    return {
      challenge: /验证码中间页|安全验证|访问验证|请完成验证|captcha|verifycenter|sec_verify|verify-challenge/i.test(text),
      loginRequired,
      loading: /加载中|loading|skeleton/i.test(`${title}\n${body}`),
      ready: document.readyState === "complete" && body.trim().length >= 40 && !loginRequired && !/加载中|loading|skeleton/i.test(`${title}\n${body}`)
    };
  }).catch(() => ({ challenge: false, loginRequired: false, loading: true, ready: false }));
}

async function detectSearchChallenge(page) {
  return Boolean((await inspectSearchPage(page)).challenge);
}

async function openSearchVerification(platform, searchUrl, timeoutMs = 4 * 60 * 1000) {
  updateDiscoveryRuntime({ status: "waiting-verification", platform, updatedAt: Date.now() });
  if (discoveryContext) {
    await discoveryContext.close().catch(() => {});
    discoveryContext = null;
  }
  verificationContext = await chromium.launchPersistentContext(discoveryDataDir, {
    headless: false,
    viewport: { width: 1280, height: 900 },
    locale: "zh-CN"
  });
  const page = verificationContext.pages()[0] || await verificationContext.newPage();
  await page.goto(searchUrl, { waitUntil: "domcontentloaded", timeout: 30000 }).catch(() => {});
  await page.bringToFront().catch(() => {});
  // The challenge shell is injected after DOMContentLoaded. Give it time to
  // render before deciding that this visible window is already verified.
  await page.waitForTimeout(2500);
  const startedAt = Date.now();
  const deadline = startedAt + timeoutMs;
  let challengeSeen = false;
  let loginSeen = false;
  try {
    while (Date.now() < deadline) {
      const pageState = await inspectSearchPage(page);
      if (pageState.challenge) challengeSeen = true;
      if (pageState.loginRequired) {
        loginSeen = true;
        updateDiscoveryRuntime({ status: "waiting-login", platform });
      } else if (pageState.challenge) {
        updateDiscoveryRuntime({ status: "waiting-verification", platform });
      }
      // Do not close a visible window while Douyin is still showing its
      // loading skeleton. A normal page also needs a short stability window
      // so the challenge shell has time to appear.
      if (pageState.ready && !pageState.challenge && !pageState.loginRequired && (challengeSeen || loginSeen || Date.now() - startedAt >= 5000)) return { ok: true };
      await page.waitForTimeout(1500);
    }
    return { ok: false, reason: loginSeen ? "login" : challengeSeen ? "verification" : "loading" };
  } finally {
    await verificationContext.close().catch(() => {});
    verificationContext = null;
    discoveryContext = await chromium.launchPersistentContext(discoveryDataDir, {
      headless: true,
      viewport: { width: 1280, height: 900 },
      locale: "zh-CN"
    }).catch(() => null);
  }
}

async function discoverPostsInWorker(options = {}, canRetryAfterVerification = true) {
  const keyword = String(options.keyword || "").trim();
  if (!keyword) throw new Error("请输入搜索关键词。");
  const limit = Math.max(1, Math.min(500, Number(options.limit) || 50));
  const maxPages = Math.max(1, Math.min(100, Number(options.maxPages) || 20));
  const sort = ["relevance", "likes", "latest"].includes(options.sort) ? options.sort : "relevance";
  const platform = options.platform === "xhs" ? "xhs" : "douyin";
  // Keep discovery in the isolated persistent context so the active browser tab is untouched.
  const searchUrl = platform === "xhs"
    ? `https://www.xiaohongshu.com/search_result?keyword=${encodeURIComponent(keyword)}&source=web_explore_feed`
    : `https://www.douyin.com/search/${encodeURIComponent(keyword)}?aid=6383&type=general`;
  const runId = globalThis.crypto?.randomUUID?.() || `discovery-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  updateDiscoveryRuntime({
    status: "running",
    runId,
    platform,
    keyword,
    page: 0,
    maxPages,
    responses: 0,
    items: 0,
    startedAt: Date.now(),
    stopReason: "",
    error: ""
  });
  const context = await ensureDiscoveryContext();
  const page = await context.newPage();
  const items = new Map();
  let responseCount = 0;
  let pageCount = 0;
  let lastPacketAt = Date.now();
  let lastNewItemAt = Date.now();
  let stalledCycles = 0;
  let stopReason = "max_pages";
  let hasMore = null;
  let riskMessage = "";
  let handedOffForVerification = false;
  const onResponse = async response => {
    const isSearchResponse = platform === "xhs" ? isXhsSearchApi(response.url()) : isDouyinSearchApi(response.url());
    if (!isSearchResponse) return;
    lastPacketAt = Date.now();
    responseCount += 1;
    updateDiscoveryRuntime({ responses: responseCount, items: items.size });
    try {
      const body = await response.text();
      if (isSearchRisk(response, body)) riskMessage = "搜索接口触发了验证或风控。";
      const payload = JSON.parse(body);
      const responseHasMore = extractSearchHasMore(payload);
      if (responseHasMore !== null) hasMore = responseHasMore;
      const rawItems = platform === "xhs" ? extractXhsSearchItems(payload) : extractSearchItems(payload);
      let added = 0;
      for (const item of rawItems) {
        const normalized = platform === "xhs" ? normalizeXhsSearchItem(item) : normalizeDouyinSearchItem(item);
        if (!normalized.id) continue;
        const previous = items.get(normalized.id);
        items.set(normalized.id, previous ? mergeSearchItem(previous, normalized) : normalized);
        if (!previous) added += 1;
      }
      if (added) lastNewItemAt = Date.now();
      updateDiscoveryRuntime({ responses: responseCount, items: items.size });
    } catch (error) {
      // Search telemetry may not be JSON; the page remains the source of truth.
    }
  };
  page.on("response", onResponse);
  let runError = "";
  try {
    await page.goto(searchUrl, { waitUntil: "domcontentloaded", timeout: 30000 });
    await page.waitForTimeout(1800);
    const gatewayError = await page.evaluate(() => /502\s+Bad\s+Gateway|504\s+Gateway|nginx\//i.test(document.body?.innerText || ""));
    if (gatewayError) throw new Error(`${platform === "xhs" ? "小红书" : "抖音"}搜索页返回 502/网关错误，请稍后重试；本次没有使用你的主浏览器页面。`);
    const initialPageState = await inspectSearchPage(page);
    if (initialPageState.challenge || initialPageState.loginRequired) {
      const verification = canRetryAfterVerification
        ? await openSearchVerification(platform, searchUrl)
        : { ok: false, reason: initialPageState.loginRequired ? "login" : "verification" };
      if (verification.ok && canRetryAfterVerification) {
        handedOffForVerification = true;
        return await discoverPostsInWorker(options, false);
      }
      riskMessage = verification.reason === "login"
        ? "抖音搜索需要登录，请在弹出的窗口中完成扫码/验证码登录。"
        : verification.ok
          ? "验证已完成，但搜索页仍未恢复，请重新发现。"
          : "搜索页进入验证码/安全验证中间页，请在弹出的窗口中完成验证。";
      stopReason = "risk";
      updateDiscoveryRuntime({ status: "completed", page: 1, stopReason, responses: responseCount, items: items.size });
    }
    for (let pageIndex = 0; pageIndex < maxPages && !riskMessage; pageIndex += 1) {
      pageCount = pageIndex + 1;
      updateDiscoveryRuntime({ page: pageCount, responses: responseCount, items: items.size });
      if (riskMessage || items.size >= limit) {
        stopReason = riskMessage ? "risk" : "target_reached";
        break;
      }
      const before = items.size;
      await page.evaluate(() => window.scrollBy(0, Math.max(480, Math.floor(window.innerHeight * 0.85))));
      await page.waitForTimeout(Math.max(900, Math.min(5000, Number(options.waitMs) || 1400)));
      if (hasMore === false) {
        stopReason = "no_more_results";
        break;
      }
      if (items.size > before) stalledCycles = 0;
      else if (responseCount > 0 && Date.now() - lastPacketAt > 3500 && Date.now() - lastNewItemAt > 3500) stalledCycles += 1;
      if (stalledCycles >= 2) {
        stopReason = "no_more_results";
        break;
      }
    }
  } catch (error) {
    runError = error.message || "发现帖子失败";
    updateDiscoveryRuntime({ status: "failed", error: runError, page: pageCount, responses: responseCount, items: items.size });
    throw error;
  } finally {
    page.off("response", onResponse);
    await page.close().catch(() => {});
    if (!runError && !handedOffForVerification) {
      updateDiscoveryRuntime({
        status: "completed",
        page: pageCount || (riskMessage ? 1 : 0),
        responses: responseCount,
        items: items.size,
        stopReason: riskMessage ? "risk" : stopReason
      });
    }
  }
  const results = [...items.values()].sort((left, right) => {
    if (sort === "likes") return Number(right.diggCount || 0) - Number(left.diggCount || 0);
    if (sort === "latest") return Number(right.createTime || 0) - Number(left.createTime || 0);
    return 0;
  });
  if (!riskMessage && !responseCount && !items.size) {
    stopReason = "no_search_response";
    updateDiscoveryRuntime({ stopReason, page: pageCount, responses: responseCount, items: items.size });
  }
  if (riskMessage) riskMessage = `${platform === "xhs" ? "小红书" : "抖音"}${riskMessage}`;
  return {
    ok: true,
    platform,
    keyword,
    sort,
    results: results.slice(0, limit),
    pages: pageCount || 1,
    responses: responseCount,
    stopReason,
    riskMessage,
    searchUrl,
    browser: "isolated-playwright",
    runId
  };
}

function createDouyinProfileApiCapture(page, expectedSecUid) {
  let settled = false;
  let resolveCapture;
  const result = new Promise(resolve => { resolveCapture = resolve; });
  const finish = value => {
    if (settled) return;
    settled = true;
    resolveCapture(value || null);
  };
  const onResponse = response => {
    if (!isDouyinProfileApi(response.url())) return;
    response.json()
      .then(payload => finish(globalThis.CommentFilterProfileMetadata.extractDouyinApiProfileInfo(payload, expectedSecUid)))
      .catch(() => {});
  };
  page.on("response", onResponse);
  return {
    result,
    stop() {
      page.off("response", onResponse);
    },
    finish
  };
}

async function extractProfile(context, url, options = {}) {
  const genderOnly = options.genderOnly === true;
  const platform = platformFromUrl(url);
  if (!platform) throw new Error("不支持的主页链接。");
  const page = await context.newPage();
  const secUid = platform === "douyin"
    ? new URL(url).pathname.match(/^\/user\/(MS4[\w-]+)\/?$/)?.[1]
    : "";
  const apiCapture = secUid ? createDouyinProfileApiCapture(page, secUid) : null;
  try {
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: 30000 });
    if (platform === "xhs") {
      await page.waitForSelector(".user-info .user-name", { timeout: 12000 });
      await page.waitForSelector(".user-info .user-tags", { timeout: 12000 });
      const info = await page.evaluate(globalThis.CommentFilterProfileMetadata.extractXhsProfilePublicInfo);
      return { ...info, rendered: true };
    }
    const readRendered = async () => {
      await page.waitForSelector('[data-e2e="user-info"]', { timeout: 8000 }).catch(() => {});
      // The profile container appears before the age/gender row. Wait briefly
      // for the dynamic SVG or profile facts so an API-only partial render is
      // not returned as a final unknown gender.
      await page.waitForFunction(() => {
        const root = document.querySelector('[data-e2e="user-info"]') || document.body;
        const html = String(root?.innerHTML || '').toLowerCase();
        const text = String(root?.innerText || root?.textContent || '');
        return /woman_svg__a|man_svg__a|male_svg__a|#female|#male|#f5588e|#168ef9/.test(html) || /\d{1,3}\s*岁/.test(text);
      }, undefined, { timeout: 8000 }).catch(() => {});
      return page.evaluate(globalThis.CommentFilterProfileMetadata.extractDouyinRenderedProfileInfo);
    };
    const mergeDouyinInfo = (structured, rendered) => ({
      ...(structured || {}),
      gender: rendered?.gender && rendered.gender !== "未知" ? rendered.gender : structured?.gender || "未知",
      profileAge: structured?.profileAge || rendered?.profileAge || "",
      profileLocation: rendered?.profileLocation || structured?.profileLocation || "",
      profileReadSource: structured?.profileReadSource || rendered?.profileReadSource || "douyin-rendered",
      rendered: true
    });
    // The page already requests this endpoint with its current login/session
    // state. Reuse that response instead of rebuilding signed query params.
    if (apiCapture) {
      const apiInfo = await Promise.race([
        apiCapture.result,
        page.waitForTimeout(2500).then(() => null)
      ]);
      if (apiInfo) {
        // Gender is the only field needed while filtering a scan. The API
        // already carries the explicit platform value, so do not wait for
        // the slower rendered SVG/metadata pass in this mode.
        if (genderOnly && apiInfo.gender && apiInfo.gender !== "未知") {
          return { ...apiInfo, rendered: false };
        }
        return mergeDouyinInfo(apiInfo, await readRendered());
      }
    }
    if (secUid) {
      const structured = await page.evaluate(globalThis.CommentFilterProfileMetadata.extractDouyinProfilePublicInfo, { expectedSecUid: secUid });
      if (structured) {
        if (genderOnly && structured.gender && structured.gender !== "未知") {
          return { ...structured, rendered: false };
        }
        return mergeDouyinInfo(structured, await readRendered());
      }
    }
    return await readRendered();
  } finally {
    apiCapture?.stop();
    await page.close();
  }
}

async function main() {
  const browserExecutable = chromium.executablePath();
  if (!existsSync(browserExecutable)) {
    throw new Error("缺少 Playwright Chromium。请先运行：npx playwright install chromium");
  }
  if (process.argv.includes("--login")) {
    const platform = process.argv.find(arg => arg.startsWith("--platform="))?.split("=")[1] || "douyin";
    browserContext = await launchBrowser(false);
    const page = browserContext.pages()[0] || await browserContext.newPage();
    await page.goto(loginUrlFor(platform), { waitUntil: "domcontentloaded", timeout: 30000 }).catch(() => {});
    console.log(`请在打开的浏览器中完成${platform === "xhs" ? "小红书" : "抖音"}登录，登录成功后会自动保存并关闭。`);
    await waitForLogin(browserContext, page, platform);
    await browserContext.close();
    return;
  }

  browserContext = await ensureHeadlessContext();
  let queue = Promise.resolve();
  const server = http.createServer((request, response) => {
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader("Access-Control-Allow-Headers", "content-type,x-comment-filter-token");
    response.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
    if (request.method === "OPTIONS") {
      response.writeHead(204);
      response.end();
      return;
    }
    if (request.method === "GET" && request.url === "/health") {
      sendJson(response, 200, {
        ok: true,
        browser: "playwright",
        browserMode,
        cdpConnected: browserMode === "cdp",
        headless: browserMode === "isolated",
        loginInProgress: Boolean(loginPromise),
        discovery: { ...discoveryRuntime }
      });
      return;
    }
    if (request.method !== "POST" || !["/profile", "/login", "/discover"].includes(request.url)) {
      response.writeHead(404);
      response.end();
      return;
    }
    if (request.headers["x-comment-filter-token"] !== workerToken) {
      sendJson(response, 403, { ok: false, error: "本机服务令牌无效" });
      return;
    }

    let body = "";
    request.on("data", chunk => { body += chunk; });
    request.on("end", () => {
      queue = queue.then(async () => {
        try {
          const payload = JSON.parse(body || "{}");
          if (request.url === "/login") {
            const platform = payload.platform === "xhs" ? "xhs" : "douyin";
            // 缺少登录态不等于安全验证；避免每个主页请求都抢占前台。
            sendJson(response, 200, { ok: true, started: false, platform });
            return;
          }
          if (request.url === "/discover") {
            sendJson(response, 200, await discoverPostsInWorker(payload));
            return;
          }
          const url = String(payload.url || "");
          const platform = platformFromUrl(url);
          if (!platform) throw new Error("不支持的主页链接。");
          if (loginPromise) {
            sendJson(response, 200, { ok: false, requiresLogin: true, loginInProgress: true, platform });
            return;
          }
          const result = await withHeadlessContext(async context => {
            const loggedIn = await hasLoginCookie(context, platform);
            try {
              // XHS exposes some profile metadata without an account session.
              if (platform !== "xhs" && !loggedIn) {
                // Profile verification is the first workflow that may need the
                // isolated browser's own session. Start the visible login
                // flow once instead of silently returning unknown genders.
                const loginStarted = await startInteractiveLogin(platform);
                return { ok: false, requiresLogin: true, loginInProgress: true, loginStarted, platform };
              }
              const info = await extractProfile(context, url, payload);
              return { ok: true, info };
            } catch (error) {
              if (!loggedIn && error.name === "TimeoutError") return { ok: false, requiresLogin: true, platform };
              throw error;
            }
          });
          sendJson(response, 200, result);
        } catch (error) {
          sendJson(response, 500, { ok: false, error: error.message || "主页读取失败" });
        }
      });
    });
  });
  server.listen(port, host, () => console.log(`Playwright 后台服务已启动：http://${host}:${port}`));
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
