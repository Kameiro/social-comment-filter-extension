import http from "node:http";
import crypto from "node:crypto";
import process from "node:process";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import nodemailer from "nodemailer";
import pg from "pg";

const { Pool } = pg;
const port = Number(process.env.PORT || 8787);
const jwtSecret = process.env.JWT_SECRET;
if (!process.env.DATABASE_URL || !jwtSecret) throw new Error("请设置 DATABASE_URL 和 JWT_SECRET。");
const adminSetupToken = String(process.env.ADMIN_SETUP_TOKEN || "");
const adminDirectory = join(dirname(fileURLToPath(import.meta.url)), "admin");
const pool = new Pool({ connectionString: process.env.DATABASE_URL });

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function send(res, status, body) {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Access-Control-Allow-Origin": process.env.CORS_ORIGIN || "*",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS"
  });
  res.end(JSON.stringify(body));
}

async function body(req) {
  let text = "";
  for await (const chunk of req) text += chunk;
  if (text.length > 25 * 1024 * 1024) throw new Error("请求体过大。");
  return text ? JSON.parse(text) : {};
}

function auth(req) {
  const token = String(req.headers.authorization || "").replace(/^Bearer\s+/i, "");
  if (!token) throw new HttpError(401, "未登录。");
  return jwt.verify(token, jwtSecret);
}

function adminAuth(req) {
  const claims = auth(req);
  if (claims.role !== "admin") throw new HttpError(403, "需要管理员权限。");
  return claims;
}

function smtpEncryptionKey() {
  return crypto.createHash("sha256").update(`${jwtSecret}:smtp-settings`).digest();
}

function encryptSmtpSecret(value) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", smtpEncryptionKey(), iv);
  const encrypted = Buffer.concat([cipher.update(String(value), "utf8"), cipher.final()]);
  return `${iv.toString("base64url")}.${cipher.getAuthTag().toString("base64url")}.${encrypted.toString("base64url")}`;
}

function decryptSmtpSecret(value) {
  if (!value) return "";
  const [ivValue, tagValue, encryptedValue] = String(value).split(".");
  if (!ivValue || !tagValue || !encryptedValue) throw new Error("SMTP 密钥配置损坏。");
  const decipher = crypto.createDecipheriv("aes-256-gcm", smtpEncryptionKey(), Buffer.from(ivValue, "base64url"));
  decipher.setAuthTag(Buffer.from(tagValue, "base64url"));
  return Buffer.concat([
    decipher.update(Buffer.from(encryptedValue, "base64url")),
    decipher.final()
  ]).toString("utf8");
}

function envSmtpConfig() {
  const port = Number(process.env.SMTP_PORT || 465);
  return {
    host: String(process.env.SMTP_HOST || "").trim(),
    port: Number.isInteger(port) && port > 0 ? port : 465,
    secure: String(process.env.SMTP_SECURE || (port === 465)).toLowerCase() === "true",
    user: String(process.env.SMTP_USER || "").trim(),
    pass: String(process.env.SMTP_PASS || ""),
    from: String(process.env.SMTP_FROM || process.env.SMTP_USER || "").trim()
  };
}

async function getSmtpConfig() {
  const fallback = envSmtpConfig();
  const result = await pool.query("select setting_value from app_settings where setting_key = 'smtp'");
  const stored = result.rows[0]?.setting_value;
  if (!stored) return { ...fallback, source: "environment" };
  let pass = fallback.pass;
  try {
    if (stored.passCiphertext) pass = decryptSmtpSecret(stored.passCiphertext);
  } catch (error) {
    console.error("Unable to decrypt SMTP settings:", error.message);
    pass = "";
  }
  return {
    host: String(stored.host || fallback.host).trim(),
    port: Number(stored.port) || fallback.port,
    secure: Boolean(stored.secure),
    user: String(stored.user || fallback.user).trim(),
    pass,
    from: String(stored.from || fallback.from).trim(),
    source: "admin"
  };
}

function createMailer(config) {
  if (!config.host || !config.user || !config.pass) return null;
  return nodemailer.createTransport({
    host: config.host,
    port: config.port,
    secure: config.secure,
    auth: { user: config.user, pass: config.pass }
  });
}

function smtpStatus(config) {
  return {
    configured: Boolean(config.host && config.user && config.pass && config.from),
    host: config.host,
    port: config.port,
    secure: config.secure,
    user: config.user,
    from: config.from,
    source: config.source
  };
}

function accountView(row) {
  return {
    id: row.id,
    email: row.email,
    role: row.role,
    tenantId: row.tenant_id,
    tenantName: row.tenant_name || "",
    subscriptionStatus: row.subscription_status,
    subscriptionExpiresAt: row.subscription_expires_at,
    createdAt: row.created_at || ""
  };
}

function normalizeEmail(value) {
  const email = String(value || "").trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new HttpError(400, "请输入有效的邮箱地址。");
  return email;
}

function validateAccountInput(input = {}) {
  const email = normalizeEmail(input.email);
  const password = String(input.password || "");
  const tenantName = String(input.tenantName || email).trim().slice(0, 120);
  if (password.length < 12) throw new HttpError(400, "密码至少需要 12 个字符。");
  if (!tenantName) throw new HttpError(400, "租户名称不能为空。");
  return { email, password, tenantName };
}

function loginResponse(account) {
  const user = accountView(account);
  const accessToken = jwt.sign({ accountId: user.id, tenantId: user.tenantId, role: user.role }, jwtSecret, { expiresIn: "30d" });
  return { accessToken, user };
}

async function createAccount(input, role = "user") {
  const { email, password, tenantName } = validateAccountInput(input);
  const client = await pool.connect();
  try {
    await client.query("begin");
    const tenant = await client.query("insert into tenants (name) values ($1) returning id", [tenantName]);
    const hash = await bcrypt.hash(password, 12);
    const account = await client.query(
      "insert into accounts (tenant_id,email,password_hash,role) values ($1,$2,$3,$4) returning *",
      [tenant.rows[0].id, email, hash, role]
    );
    await client.query("commit");
    return accountView(account.rows[0]);
  } catch (error) {
    await client.query("rollback").catch(() => {});
    if (error.code === "23505") throw new HttpError(409, "这个邮箱已经注册。");
    throw error;
  } finally {
    client.release();
  }
}

async function login(req, res) {
  const input = await body(req);
  const email = normalizeEmail(input.email);
  const password = String(input.password || "");
  if (!email || !password) return send(res, 400, { error: "邮箱和密码不能为空。" });
  const result = await pool.query("select * from accounts where email = $1", [email]);
  const account = result.rows[0];
  if (!account || !(await bcrypt.compare(password, account.password_hash))) {
    return send(res, 401, { error: "邮箱或密码错误。" });
  }
  send(res, 200, loginResponse(account));
}

function verificationHash(email, purpose, code) {
  return crypto.createHmac("sha256", jwtSecret).update(`${email}:${purpose}:${code}`).digest("hex");
}

async function requestEmailCode(req, res) {
  const input = await body(req);
  const email = normalizeEmail(input.email);
  const purpose = String(input.purpose || "login");
  if (!["login", "register"].includes(purpose)) throw new HttpError(400, "验证码用途不正确。");
  const accountResult = await pool.query("select 1 from accounts where email = $1", [email]);
  if (purpose === "register" && accountResult.rowCount) throw new HttpError(409, "这个邮箱已经注册，请直接登录。");
  if (purpose === "login" && !accountResult.rowCount) throw new HttpError(404, "这个邮箱还没有账户，请先注册。");
  const latest = await pool.query(
    "select created_at from email_verification_codes where email = $1 and purpose = $2",
    [email, purpose]
  );
  if (latest.rows[0] && Date.now() - new Date(latest.rows[0].created_at).getTime() < 60_000) {
    throw new HttpError(429, "验证码发送过于频繁，请稍后再试。");
  }
  const smtp = await getSmtpConfig();
  const mailer = createMailer(smtp);
  if (!mailer) throw new HttpError(503, "服务器尚未配置邮箱服务，请联系管理员。");
  const code = String(crypto.randomInt(100000, 1000000));
  await mailer.sendMail({
    from: smtp.from,
    to: email,
    subject: "评论筛选助手验证码",
    text: `你的验证码是 ${code}，10 分钟内有效。若不是本人操作，请忽略这封邮件。`,
    html: `<p>你的验证码是：</p><p style="font-size:28px;font-weight:700;letter-spacing:6px">${code}</p><p>验证码 10 分钟内有效。若不是本人操作，请忽略这封邮件。</p>`
  });
  await pool.query(
    "insert into email_verification_codes (email,purpose,code_hash,expires_at,attempts,created_at) values ($1,$2,$3,now() + interval '10 minutes',0,now()) on conflict (email,purpose) do update set code_hash=excluded.code_hash,expires_at=excluded.expires_at,attempts=0,created_at=excluded.created_at",
    [email, purpose, verificationHash(email, purpose, code)]
  );
  send(res, 200, { ok: true, expiresIn: 600 });
}

async function consumeEmailCode(email, purpose, code) {
  const client = await pool.connect();
  try {
    await client.query("begin");
    const result = await client.query(
      "select * from email_verification_codes where email = $1 and purpose = $2 for update",
      [email, purpose]
    );
    const record = result.rows[0];
    if (!record || new Date(record.expires_at).getTime() <= Date.now()) {
      await client.query("rollback");
      throw new HttpError(400, "验证码不存在或已过期，请重新获取。");
    }
    if (record.attempts >= 5) {
      await client.query("delete from email_verification_codes where email = $1 and purpose = $2", [email, purpose]);
      await client.query("commit");
      throw new HttpError(400, "验证码尝试次数过多，请重新获取。");
    }
    const supplied = Buffer.from(verificationHash(email, purpose, String(code || "").trim()));
    const expected = Buffer.from(record.code_hash);
    if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) {
      await client.query("update email_verification_codes set attempts = attempts + 1 where email = $1 and purpose = $2", [email, purpose]);
      await client.query("commit");
      throw new HttpError(400, "验证码不正确。");
    }
    await client.query("delete from email_verification_codes where email = $1 and purpose = $2", [email, purpose]);
    await client.query("commit");
  } catch (error) {
    if (error.status) throw error;
    await client.query("rollback").catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

async function loginWithCode(req, res) {
  const input = await body(req);
  const email = normalizeEmail(input.email);
  await consumeEmailCode(email, "login", input.code);
  const result = await pool.query("select * from accounts where email = $1", [email]);
  if (!result.rows[0]) throw new HttpError(404, "这个邮箱还没有账户，请先注册。");
  send(res, 200, loginResponse(result.rows[0]));
}

async function register(req, res) {
  const input = await body(req);
  const email = normalizeEmail(input.email);
  await consumeEmailCode(email, "register", input.code);
  const user = await createAccount({ ...input, email }, "user");
  send(res, 201, loginResponse({
    ...user,
    tenant_id: user.tenantId,
    tenant_name: user.tenantName,
    subscription_status: user.subscriptionStatus,
    subscription_expires_at: user.subscriptionExpiresAt,
    created_at: user.createdAt
  }));
}

async function bootstrapAdmin(req, res) {
  if (!adminSetupToken) throw new HttpError(503, "服务器尚未配置管理员初始化令牌。");
  const input = await body(req);
  const suppliedToken = Buffer.from(String(input.setupToken || "").trim());
  const expectedToken = Buffer.from(adminSetupToken);
  if (suppliedToken.length !== expectedToken.length || !crypto.timingSafeEqual(suppliedToken, expectedToken)) {
    throw new HttpError(403, "初始化令牌不正确。");
  }
  const existing = await pool.query("select 1 from accounts where role = 'admin' limit 1");
  if (existing.rowCount) throw new HttpError(409, "管理员已经初始化，请直接登录后台。");
  const user = await createAccount(input, "admin");
  const token = jwt.sign({ accountId: user.id, tenantId: user.tenantId, role: "admin" }, jwtSecret, { expiresIn: "30d" });
  send(res, 201, { accessToken: token, user });
}

async function recoverAdminPassword(req, res) {
  if (!adminSetupToken) throw new HttpError(503, "服务器尚未配置管理员初始化令牌。");
  const input = await body(req);
  const suppliedToken = Buffer.from(String(input.setupToken || ""));
  const expectedToken = Buffer.from(adminSetupToken);
  if (suppliedToken.length !== expectedToken.length || !crypto.timingSafeEqual(suppliedToken, expectedToken)) {
    throw new HttpError(403, "初始化令牌不正确。");
  }
  const { email, password } = validateAccountInput({ ...input, tenantName: "password-recovery" });
  const hash = await bcrypt.hash(password, 12);
  const result = await pool.query(
    "update accounts set password_hash = $1 where email = $2 and role = 'admin' returning id,email",
    [hash, email]
  );
  if (!result.rowCount) throw new HttpError(404, "没有找到这个管理员邮箱。");
  send(res, 200, { ok: true, email: result.rows[0].email });
}

async function listAdminAccounts(req, res) {
  adminAuth(req);
  const result = await pool.query(
    "select a.id,a.email,a.role,a.tenant_id,t.name as tenant_name,a.subscription_status,a.subscription_expires_at,a.created_at from accounts a join tenants t on t.id = a.tenant_id order by a.created_at desc"
  );
  send(res, 200, { accounts: result.rows.map(accountView) });
}

async function createAdminAccount(req, res) {
  adminAuth(req);
  const input = await body(req);
  const user = await createAccount(input, input.role === "admin" ? "admin" : "user");
  send(res, 201, { user });
}

async function changeAdminPassword(req, res) {
  const claims = adminAuth(req);
  const input = await body(req);
  const currentPassword = String(input.currentPassword || "");
  const nextPassword = String(input.newPassword || "");
  if (!currentPassword || nextPassword.length < 12) throw new HttpError(400, "当前密码不能为空，新密码至少需要 12 个字符。");
  const result = await pool.query("select password_hash from accounts where id = $1 and role = 'admin'", [claims.accountId]);
  const account = result.rows[0];
  if (!account || !(await bcrypt.compare(currentPassword, account.password_hash))) throw new HttpError(401, "当前密码不正确。");
  const hash = await bcrypt.hash(nextPassword, 12);
  await pool.query("update accounts set password_hash = $1 where id = $2", [hash, claims.accountId]);
  send(res, 200, { ok: true });
}

async function getAdminSmtp(req, res) {
  adminAuth(req);
  send(res, 200, { smtp: smtpStatus(await getSmtpConfig()) });
}

async function saveAdminSmtp(req, res) {
  adminAuth(req);
  const input = await body(req);
  const current = await getSmtpConfig();
  const host = String(input.host || "").trim();
  const user = String(input.user || "").trim();
  const from = String(input.from || "").trim();
  const port = Number(input.port || 0);
  const pass = String(input.pass || "");
  if (!host || !user || !from) throw new HttpError(400, "SMTP 主机、用户名和发件人不能为空。");
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new HttpError(400, "SMTP 端口不正确。");
  if (!pass && !current.pass) throw new HttpError(400, "请输入 SMTP Key 或密码。");
  const next = {
    host,
    port,
    secure: Boolean(input.secure),
    user,
    from,
    passCiphertext: encryptSmtpSecret(pass || current.pass)
  };
  const mailer = createMailer({ ...next, pass: pass || current.pass });
  try {
    await mailer.verify();
  } catch (error) {
    throw new HttpError(400, `SMTP 连接验证失败：${error.message}`);
  } finally {
    mailer?.close();
  }
  await pool.query(
    "insert into app_settings (setting_key,setting_value,updated_at) values ('smtp',$1,now()) on conflict (setting_key) do update set setting_value=excluded.setting_value,updated_at=now()",
    [next]
  );
  send(res, 200, { ok: true, smtp: smtpStatus({ ...next, pass: pass || current.pass, source: "admin" }) });
}

async function serveAdmin(res) {
  const html = await readFile(join(adminDirectory, "index.html"), "utf8");
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
  res.end(html);
}

async function serveAdminAsset(res, name, contentType) {
  const asset = await readFile(join(adminDirectory, name), "utf8");
  res.writeHead(200, { "Content-Type": contentType, "Cache-Control": "no-store" });
  res.end(asset);
}

async function syncSnapshot(req, res) {
  const claims = auth(req);
  const accountResult = await pool.query("select subscription_status, subscription_expires_at from accounts where id = $1 and tenant_id = $2", [claims.accountId, claims.tenantId]);
  const account = accountResult.rows[0];
  const expired = account?.subscription_expires_at && new Date(account.subscription_expires_at).getTime() <= Date.now();
  if (!account || account.subscription_status !== "active" || expired) {
    return send(res, 403, { error: "当前账户没有可用的同步授权。" });
  }
  const input = await body(req);
  const posts = Array.isArray(input.posts) ? input.posts : [];
  const comments = Array.isArray(input.comments) ? input.comments : [];
  const users = Array.isArray(input.users) ? input.users : [];
  const analysisScopes = Array.isArray(input.analysisScopes) ? input.analysisScopes : [];
  if (posts.length > 100000 || comments.length > 500000 || users.length > 200000 || analysisScopes.length > 10000) {
    return send(res, 413, { error: "本次同步数据量超过限制。" });
  }
  const client = await pool.connect();
  try {
    await client.query("begin");
    for (const row of posts) {
      const id = String(row.id || crypto.createHash("sha256").update(String(row.url || "")).digest("hex"));
      await client.query(
        "insert into posts (tenant_id,id,payload,updated_at) values ($1,$2,$3,now()) on conflict (tenant_id,id) do update set payload=excluded.payload,updated_at=now()",
        [claims.tenantId, id, row]
      );
    }
    for (const row of comments) {
      const id = String(row.id || crypto.createHash("sha256").update(JSON.stringify(row)).digest("hex"));
      await client.query(
        "insert into comments (tenant_id,id,payload,updated_at) values ($1,$2,$3,now()) on conflict (tenant_id,id) do update set payload=excluded.payload,updated_at=now()",
        [claims.tenantId, id, row]
      );
    }
    for (const row of users) {
      const profile = String(row.profile || "");
      if (!profile) continue;
      await client.query(
        "insert into users (tenant_id,profile,payload,updated_at) values ($1,$2,$3,now()) on conflict (tenant_id,profile) do update set payload=excluded.payload,updated_at=now()",
        [claims.tenantId, profile, row]
      );
    }
    for (const row of analysisScopes) {
      const id = String(row.id || "");
      if (!id) continue;
      await client.query(
        "insert into analysis_scopes (tenant_id,id,payload,updated_at) values ($1,$2,$3,now()) on conflict (tenant_id,id) do update set payload=excluded.payload,updated_at=now()",
        [claims.tenantId, id, row]
      );
    }
    await client.query("commit");
    send(res, 200, { ok: true, counts: { posts: posts.length, comments: comments.length, users: users.length, analysisScopes: analysisScopes.length } });
  } catch (error) {
    await client.query("rollback");
    throw error;
  } finally {
    client.release();
  }
}

async function ensureRuntimeSchema() {
  await pool.query(`
    create table if not exists email_verification_codes (
      email text not null,
      purpose text not null check (purpose in ('login', 'register')),
      code_hash text not null,
      attempts integer not null default 0,
      expires_at timestamptz not null,
      created_at timestamptz not null default now(),
      primary key (email, purpose)
    )
  `);
  await pool.query(`
    create table if not exists app_settings (
      setting_key text primary key,
      setting_value jsonb not null,
      updated_at timestamptz not null default now()
    )
  `);
}

await pool.query("create table if not exists analysis_scopes (tenant_id uuid not null references tenants(id) on delete cascade, id text not null, payload jsonb not null, updated_at timestamptz not null default now(), primary key (tenant_id, id))");

const server = http.createServer(async (req, res) => {
  if (req.method === "OPTIONS") return send(res, 204, {});
  try {
    if (req.method === "GET" && req.url === "/admin") return await serveAdmin(res);
    if (req.method === "GET" && req.url === "/admin/app.js") return await serveAdminAsset(res, "app.js", "text/javascript; charset=utf-8");
    if (req.method === "GET" && req.url === "/health") {
      await pool.query("select 1");
      return send(res, 200, { ok: true });
    }
    if (req.method === "POST" && req.url === "/v1/auth/login") return await login(req, res);
    if (req.method === "POST" && req.url === "/v1/auth/request-code") return await requestEmailCode(req, res);
    if (req.method === "POST" && req.url === "/v1/auth/login-code") return await loginWithCode(req, res);
    if (req.method === "POST" && req.url === "/v1/auth/register") return await register(req, res);
    if (req.method === "POST" && req.url === "/v1/admin/bootstrap") return await bootstrapAdmin(req, res);
    if (req.method === "POST" && req.url === "/v1/admin/recover") return await recoverAdminPassword(req, res);
    if (req.method === "GET" && req.url === "/v1/admin/accounts") return await listAdminAccounts(req, res);
    if (req.method === "POST" && req.url === "/v1/admin/accounts") return await createAdminAccount(req, res);
    if (req.method === "POST" && req.url === "/v1/admin/password") return await changeAdminPassword(req, res);
    if (req.method === "GET" && req.url === "/v1/admin/smtp") return await getAdminSmtp(req, res);
    if (req.method === "POST" && req.url === "/v1/admin/smtp") return await saveAdminSmtp(req, res);
    if (req.method === "GET" && req.url === "/v1/auth/me") {
      const claims = auth(req);
      const result = await pool.query("select * from accounts where id = $1 and tenant_id = $2", [claims.accountId, claims.tenantId]);
      return result.rows[0] ? send(res, 200, { user: accountView(result.rows[0]) }) : send(res, 401, { error: "账户不存在。" });
    }
    if (req.method === "POST" && req.url === "/v1/sync/snapshot") return await syncSnapshot(req, res);
    send(res, 404, { error: "接口不存在。" });
  } catch (error) {
    const status = error.status || (/未登录|jwt|token/i.test(error.message || "") ? 401 : 500);
    send(res, status, { error: error.message || "服务器错误。" });
  }
});

await ensureRuntimeSchema();
server.listen(port, () => console.log(`Comment filter cloud server listening on :${port}`));
