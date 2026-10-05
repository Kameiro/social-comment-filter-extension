(() => {
  if (globalThis.CommentFilterCloudSync) return;

  const STORAGE_KEY = "cloudConfig";
  const DEFAULT_MIGRATION_KEY = "cloudDefaultServerMigratedV1";
  const DEFAULT_API_BASE_URL = "https://comment-filter.04210410.xyz";
  const DEFAULT_CONFIG = {
    mode: "managed",
    apiBaseUrl: DEFAULT_API_BASE_URL,
    email: "",
    tenantId: "",
    accessToken: ""
  };

  function cleanBaseUrl(value) {
    return String(value || "").trim().replace(/\/+$/, "");
  }

  async function getConfig() {
    const stored = await chrome.storage.local.get({ [STORAGE_KEY]: null, [DEFAULT_MIGRATION_KEY]: false });
    const previous = stored[STORAGE_KEY] || {};
    const shouldMigrate = !stored[DEFAULT_MIGRATION_KEY]
      && (!stored[STORAGE_KEY] || (previous.mode === "local" && !previous.apiBaseUrl && !previous.accessToken));
    const config = {
      ...DEFAULT_CONFIG,
      ...previous,
      ...(shouldMigrate ? { mode: "managed", apiBaseUrl: DEFAULT_API_BASE_URL } : {})
    };
    config.apiBaseUrl = cleanBaseUrl(config.apiBaseUrl);
    if (shouldMigrate) await chrome.storage.local.set({ [STORAGE_KEY]: config, [DEFAULT_MIGRATION_KEY]: true });
    return config;
  }

  async function saveConfig(next) {
    const config = { ...DEFAULT_CONFIG, ...(next || {}) };
    config.apiBaseUrl = cleanBaseUrl(config.apiBaseUrl);
    await chrome.storage.local.set({ [STORAGE_KEY]: config, [DEFAULT_MIGRATION_KEY]: true });
    return config;
  }

  async function request(path, options = {}, suppliedConfig = null) {
    const config = suppliedConfig || await getConfig();
    if (!config.apiBaseUrl) throw new Error("账户服务地址未配置。");
    const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
    if (config.accessToken) headers.Authorization = `Bearer ${config.accessToken}`;
    const response = await fetch(`${config.apiBaseUrl}/v1${path}`, { ...options, headers });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || result.message || `账户服务请求失败（${response.status}）。`);
    return result;
  }

  async function login(email, password, config) {
    const result = await request("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password })
    }, config);
    return saveConfig({ ...config, email, tenantId: result.user?.tenantId || "", accessToken: result.accessToken || "" });
  }

  async function requestCode(email, purpose, config) {
    return request("/auth/request-code", {
      method: "POST",
      body: JSON.stringify({ email, purpose })
    }, config);
  }

  async function loginWithCode(email, code, config) {
    const result = await request("/auth/login-code", {
      method: "POST",
      body: JSON.stringify({ email, code })
    }, config);
    return saveConfig({ ...config, email, tenantId: result.user?.tenantId || "", accessToken: result.accessToken || "" });
  }

  async function register(email, code, password, config) {
    const result = await request("/auth/register", {
      method: "POST",
      body: JSON.stringify({ email, code, password })
    }, config);
    return saveConfig({ ...config, email, tenantId: result.user?.tenantId || "", accessToken: result.accessToken || "" });
  }

  async function logout() {
    const config = await getConfig();
    return saveConfig({ ...config, accessToken: "", tenantId: "" });
  }

  async function getCurrentUser() {
    return request("/auth/me");
  }

  async function syncSnapshot(snapshot) {
    const config = await getConfig();
    if (config.mode === "local") throw new Error("当前未启用服务器同步。");
    if (!config.accessToken) throw new Error("请先登录账户。");
    return request("/sync/snapshot", {
      method: "POST",
      body: JSON.stringify({
        posts: snapshot.posts || [],
        comments: snapshot.comments || [],
        users: snapshot.users || [],
        analysisScopes: snapshot.analysisScopes || []
      })
    }, config);
  }

  globalThis.CommentFilterCloudSync = { getConfig, saveConfig, login, requestCode, loginWithCode, register, logout, getCurrentUser, syncSnapshot };
})();
