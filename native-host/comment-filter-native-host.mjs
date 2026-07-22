import http from "node:http";
import { openSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const workerDir = resolve(root, "playwright-worker");
const workerEntry = resolve(workerDir, "worker.mjs");
const workerPort = 38765;

function send(message) {
  const payload = Buffer.from(JSON.stringify(message), "utf8");
  const header = Buffer.alloc(4);
  header.writeUInt32LE(payload.length, 0);
  process.stdout.write(Buffer.concat([header, payload]));
}

function healthCheck() {
  return new Promise(resolve => {
    const request = http.get(`http://127.0.0.1:${workerPort}/health`, response => {
      response.resume();
      resolve(response.statusCode === 200);
    });
    request.setTimeout(800, () => request.destroy());
    request.on("error", () => resolve(false));
  });
}

function wait(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function startWorker() {
  if (await healthCheck()) return { ok: true, started: false };
  const log = openSync(resolve(workerDir, "native-worker.log"), "a");
  const child = spawn(process.execPath, [workerEntry], {
    cwd: workerDir,
    detached: true,
    stdio: ["ignore", log, log]
  });
  child.unref();

  for (let attempt = 0; attempt < 20; attempt += 1) {
    await wait(400);
    if (await healthCheck()) return { ok: true, started: true };
  }
  return { ok: false, error: "后台服务未能启动。请先运行一次安装脚本，确认 Node.js 和 Playwright 已安装。" };
}

let input = Buffer.alloc(0);
process.stdin.on("data", chunk => {
  input = Buffer.concat([input, chunk]);
  while (input.length >= 4) {
    const length = input.readUInt32LE(0);
    if (input.length < length + 4) return;
    const raw = input.subarray(4, length + 4).toString("utf8");
    input = input.subarray(length + 4);
    Promise.resolve().then(async () => {
      const message = JSON.parse(raw || "{}");
      if (message.action === "start-worker") return startWorker();
      if (message.action === "health") return { ok: await healthCheck() };
      return { ok: false, error: "不支持的本地助手指令。" };
    }).then(send).catch(error => send({ ok: false, error: error.message || "本地助手执行失败。" }));
  }
});
