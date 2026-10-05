(() => {
  const tokenKey = "commentFilterAdminToken";
  const $ = selector => document.querySelector(selector);

  function setNotice(message, error = false) {
    const node = $("#notice");
    node.textContent = message || "";
    node.classList.toggle("error", error);
  }

  function formObject(form) {
    return Object.fromEntries(new FormData(form).entries());
  }

  async function request(path, options = {}) {
    const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
    const token = localStorage.getItem(tokenKey);
    if (token) headers.Authorization = `Bearer ${token}`;
    const response = await fetch(path, { ...options, headers });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || `请求失败（${response.status}）。`);
    return result;
  }

  function setLoggedIn(value) {
    $("#bootstrapPanel").hidden = value;
    $("#loginPanel").hidden = value;
    $("#accountPanel").hidden = !value;
    $("#logout").hidden = !value;
  }

  function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
  }

  async function loadAccounts() {
    const result = await request("/v1/admin/accounts");
    const rows = result.accounts || [];
    $("#accounts").innerHTML = rows.length
      ? `<table><thead><tr><th>邮箱</th><th>角色</th><th>租户</th><th>状态</th><th>创建时间</th></tr></thead><tbody>${rows.map(account => `<tr><td>${escapeHtml(account.email)}</td><td>${escapeHtml(account.role)}</td><td>${escapeHtml(account.tenantName || account.tenantId)}</td><td>${escapeHtml(account.subscriptionStatus)}</td><td>${escapeHtml(account.createdAt || "")}</td></tr>`).join("")}</tbody></table>`
      : "还没有账户。";
  }

  async function loadSmtp() {
    const result = await request("/v1/admin/smtp");
    const smtp = result.smtp || {};
    const form = $("#smtpForm");
    form.host.value = smtp.host || "smtp-relay.brevo.com";
    form.port.value = smtp.port || 587;
    form.user.value = smtp.user || "";
    form.from.value = smtp.from || "";
    form.secure.checked = Boolean(smtp.secure);
    $("#smtpStatus").textContent = smtp.configured
      ? `邮件服务已配置：${smtp.from}（${smtp.source === "admin" ? "后台配置" : "环境变量"}）`
      : "邮件服务尚未配置。";
  }

  $("#bootstrapForm").addEventListener("submit", async event => {
    event.preventDefault();
    const button = event.target.querySelector("button[type=submit]");
    button.disabled = true;
    try {
      const result = await request("/v1/admin/bootstrap", { method: "POST", body: JSON.stringify(formObject(event.target)) });
      localStorage.setItem(tokenKey, result.accessToken);
      event.target.reset();
      setLoggedIn(true);
      setNotice("管理员已创建。");
      await loadAccounts();
      await loadSmtp();
    } catch (error) {
      setNotice(error.message, true);
    } finally {
      button.disabled = false;
    }
  });

  $("#loginForm").addEventListener("submit", async event => {
    event.preventDefault();
    const button = event.target.querySelector("button[type=submit]");
    button.disabled = true;
    try {
      const result = await request("/v1/auth/login", { method: "POST", body: JSON.stringify(formObject(event.target)) });
      if (result.user?.role !== "admin") throw new Error("这个账户不是管理员。");
      localStorage.setItem(tokenKey, result.accessToken);
      event.target.reset();
      setLoggedIn(true);
      setNotice("已登录管理后台。");
      await loadAccounts();
      await loadSmtp();
    } catch (error) {
      setNotice(error.message, true);
    } finally {
      button.disabled = false;
    }
  });

  $("#recoverForm").addEventListener("submit", async event => {
    event.preventDefault();
    const button = event.target.querySelector("button[type=submit]");
    button.disabled = true;
    try {
      await request("/v1/admin/recover", { method: "POST", body: JSON.stringify(formObject(event.target)) });
      event.target.reset();
      setNotice("管理员密码已重设，请使用新密码登录。");
    } catch (error) {
      setNotice(error.message, true);
    } finally {
      button.disabled = false;
    }
  });

  $("#accountForm").addEventListener("submit", async event => {
    event.preventDefault();
    const button = event.target.querySelector("button[type=submit]");
    button.disabled = true;
    try {
      await request("/v1/admin/accounts", { method: "POST", body: JSON.stringify(formObject(event.target)) });
      event.target.reset();
      setNotice("账户已创建。");
      await loadAccounts();
    } catch (error) {
      setNotice(error.message, true);
    } finally {
      button.disabled = false;
    }
  });

  $("#passwordForm").addEventListener("submit", async event => {
    event.preventDefault();
    const button = event.target.querySelector("button[type=submit]");
    button.disabled = true;
    try {
      await request("/v1/admin/password", { method: "POST", body: JSON.stringify(formObject(event.target)) });
      event.target.reset();
      setNotice("管理员密码已修改。");
    } catch (error) {
      setNotice(error.message, true);
    } finally {
      button.disabled = false;
    }
  });

  $("#smtpForm").addEventListener("submit", async event => {
    event.preventDefault();
    const button = event.target.querySelector("button[type=submit]");
    button.disabled = true;
    try {
      const result = await request("/v1/admin/smtp", { method: "POST", body: JSON.stringify(formObject(event.target)) });
      event.target.pass.value = "";
      const smtp = result.smtp || {};
      $("#smtpStatus").textContent = `邮件服务已配置：${smtp.from}（后台加密保存）`;
      setNotice("SMTP 配置已保存并验证成功。验证码邮件现在可以发送了。");
    } catch (error) {
      setNotice(error.message, true);
    } finally {
      button.disabled = false;
    }
  });

  $("#logout").addEventListener("click", () => {
    localStorage.removeItem(tokenKey);
    setLoggedIn(false);
    setNotice("已退出登录。");
  });

  const token = localStorage.getItem(tokenKey);
  setLoggedIn(Boolean(token));
  if (token) Promise.all([loadAccounts(), loadSmtp()]).catch(error => {
    localStorage.removeItem(tokenKey);
    setLoggedIn(false);
    setNotice(error.message || "登录已失效。", true);
  });
})();
