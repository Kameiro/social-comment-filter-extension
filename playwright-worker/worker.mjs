import http from "node:http";
import { existsSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const root = dirname(fileURLToPath(import.meta.url));
const dataDir = resolve(root, "browser-data");
const port = Number(process.env.COMMENT_FILTER_PORT || 38765);
const host = "127.0.0.1";
const workerToken = process.env.COMMENT_FILTER_TOKEN || "cfw_7d2f4c9a11b84e6fb0a1d9c3e8f6a2b7";
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
let loginPromise = null;

async function launchBrowser(headless) {
  return chromium.launchPersistentContext(dataDir, {
    headless,
    viewport: { width: 1280, height: 900 },
    locale: "zh-CN"
  });
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
  return page.evaluate(() => {
    const female = [...document.querySelectorAll("svg")].some(svg =>
      [...svg.querySelectorAll("mask")].some(mask => String(mask.id || "").includes("woman_svg__a"))
    );
    if (female) return "女";

    const male = [...document.querySelectorAll("svg")].some(svg => {
      const viewBox = String(svg.getAttribute("viewBox") || "").replace(/\s+/g, " ").trim();
      if (viewBox !== "0 0 12 12") return false;
      return [...svg.querySelectorAll("path")].some(path => {
        const d = String(path.getAttribute("d") || "");
        const fill = String(path.getAttribute("fill") || "").toLowerCase();
        return /M8\s*1\.25/.test(d) && /M5\s*10/.test(d) && fill === "#168ef9";
      });
    });
    if (male) return "男";

    const xhsGender = [...document.querySelectorAll("use")].map(use =>
      String(use.getAttribute("href") || use.getAttribute("xlink:href") || "").toLowerCase()
    );
    if (xhsGender.includes("#female")) return "女";
    if (xhsGender.includes("#male")) return "男";
    return "未知";
  });
}

async function extractProfile(context, url) {
  const platform = platformFromUrl(url);
  if (!platform) throw new Error("不支持的主页链接。");
  const page = await context.newPage();
  try {
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: 30000 });
    await page.waitForTimeout(1200);
    await page.waitForFunction(() => document.querySelector("svg, use"), null, { timeout: 7000 }).catch(() => {});
    const gender = await classifyGender(page);
    const text = await page.locator("body").innerText({ timeout: 5000 }).catch(() => "");
    const age = text.match(/(\d{1,3})\s*岁/);
    const location = text.match(/(?:IP属地|所在地)[:：\s]*([\u4e00-\u9fa5A-Za-z]{2,20}(?:[·・][\u4e00-\u9fa5A-Za-z]{1,20})?)/);
    return {
      gender,
      profileAge: age ? `${age[1]}岁` : "",
      profileLocation: location?.[1] || "",
      rendered: true
    };
  } finally {
    await page.close();
  }
}

async function main() {
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

  browserContext = await launchBrowser(true);
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
      sendJson(response, 200, { ok: true, browser: "playwright", headless: true, loginInProgress: Boolean(loginPromise) });
      return;
    }
    if (request.method !== "POST" || !["/profile", "/login"].includes(request.url)) {
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
          const url = String(payload.url || "");
          const platform = platformFromUrl(url);
          if (!platform) throw new Error("不支持的主页链接。");
          if (loginPromise) {
            sendJson(response, 200, { ok: false, requiresLogin: true, loginInProgress: true, platform });
            return;
          }
          if (!(await hasLoginCookie(browserContext, platform))) {
            sendJson(response, 200, { ok: false, requiresLogin: true, platform });
            return;
          }
          const info = await extractProfile(browserContext, url);
          sendJson(response, 200, { ok: true, info });
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
