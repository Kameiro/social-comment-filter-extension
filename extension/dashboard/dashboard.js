(() => {
  const $ = selector => document.querySelector(selector);
  const state = {
    view: "posts",
    query: "",
    postId: "",
    taskId: "",
    openTaskPosts: new Set(),
    discoverySelected: new Set(),
    discoveryPlatform: "douyin",
    discoveryKeyword: "",
    discoverySort: "latest",
    discoveryStatusFilter: "all",
    discoveryLimit: 50,
    discoveryPages: 20,
    discoveryBusy: false,
    discoveryScanBusy: false,
    discoveryRuntime: { status: "unknown" },
    analysisScopeId: "",
    data: { posts: [], comments: [], users: [], discoveredPosts: [], analysisScopes: [] },
    messageLog: {},
    cloudConfig: null,
    cloudUser: null,
    cloudSyncState: { status: "idle", lastSyncedAt: 0 },
    likedComments: new Set(),
    accountMode: "password",
    codeTimer: null
  };

  function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
  }

  function platformName(platform) {
    return platform === "xhs" ? "小红书" : platform === "douyin" ? "抖音" : platform === "kuaishou" ? "快手" : "其他";
  }

  function formatCount(value) {
    const count = Number(value || 0);
    return Number.isFinite(count) ? count.toLocaleString("zh-CN") : "0";
  }

  function platformMetricText(post, field) {
    return Number(post?.statsCapturedAt || 0) > 0 ? formatCount(post?.[field]) : "—";
  }

  function discoveredFetchedAt(post) {
    return Number(post?.lastSeenAt || post?.statsCapturedAt || post?.discoveredAt || 0);
  }

  function compareKnownNumbers(left, right, descending = true) {
    const leftKnown = Number(left) > 0;
    const rightKnown = Number(right) > 0;
    if (leftKnown !== rightKnown) return leftKnown ? -1 : 1;
    const difference = Number(right || 0) - Number(left || 0);
    return descending ? difference : -difference;
  }

  function compareDiscoveredPosts(left, right) {
    if (state.discoverySort === "likes") {
      const likes = compareKnownNumbers(left.diggCount, right.diggCount);
      if (likes) return likes;
    }
    if (state.discoverySort === "latest") {
      const published = compareKnownNumbers(left.createTime, right.createTime);
      if (published) return published;
    }
    return Number(discoveredFetchedAt(right)) - Number(discoveredFetchedAt(left)) ||
      String(left.id || "").localeCompare(String(right.id || ""));
  }

  function filteredDiscoveredPosts() {
    return [...(state.data.discoveredPosts || [])]
      .sort(compareDiscoveredPosts)
      .filter(post => discoveryStatusMatches(post))
      .filter(post => matchesQuery([post.title, post.nickname, post.url, post.keyword, ...(post.keywords || []), platformName(post.platform)]));
  }

  function discoveryKey(record) {
    const platform = String(record?.platform || "unknown");
    const id = String(record?.id || "");
    return id.startsWith(`${platform}:`) ? id : `${platform}:${id}`;
  }

  function trackedPostForDiscovery(record) {
    const identity = CommentFilterDatabase.postIdentity(record?.url || "");
    return state.data.posts.find(post => post.id === identity || post.id === discoveryKey(record) || post.url === record?.url) || null;
  }

  function discoveryStatus(record, commentsByPost) {
    const tracked = trackedPostForDiscovery(record);
    if (!tracked) return { label: "未获取评论", detail: "尚未抓取", kind: "pending" };
    const commentCount = Number(tracked.commentCount || commentsByPost.get(tracked.id)?.size || 0);
    const userCount = commentsByPost.get(tracked.id)?.profiles?.size || 0;
    const details = [`${formatCount(commentCount)} 条评论`];
    if (userCount) details.push(`${formatCount(userCount)} 个用户`);
    if (tracked.lastScannedAt) details.push(formatDate(tracked.lastScannedAt));
    return { label: "已获取评论", detail: details.join(" · "), kind: "done" };
  }

  function analysisScopesForDiscoveryPost(record) {
    const key = discoveryKey(record);
    const url = String(record?.url || "");
    return (state.data.analysisScopes || []).filter(scope => {
      const selectedPostIds = scope?.filters?.selectedPostIds || [];
      return selectedPostIds.includes(key) || selectedPostIds.includes(record?.id) || selectedPostIds.includes(url);
    });
  }

  function discoveryStatusMatches(record) {
    if (state.discoveryStatusFilter === "pending") return !trackedPostForDiscovery(record);
    if (state.discoveryStatusFilter === "done") return Boolean(trackedPostForDiscovery(record));
    return true;
  }

  function postNumberLabel(post) {
    const number = Number(post?.postNumber || 0);
    return number > 0 ? `帖子 #${number}` : "未编号帖子";
  }

  function taskNumberLabel(task) {
    const number = Number(task?.taskNumber || 0);
    return number > 0 ? `任务 #${number}` : "历史任务";
  }

  function taskLabelsForComment(row, post) {
    const ids = Array.isArray(row?.filterTaskIds) ? row.filterTaskIds : [];
    const tasks = Array.isArray(post?.filterTasks) ? post.filterTasks : [];
    const labels = ids.map(id => tasks.find(task => task.id === id)).filter(Boolean).map(task => taskNumberLabel(task));
    return [...new Set(labels)];
  }

  function formatDate(value) {
    if (!value) return "";
    const date = new Date(Number(value));
    return Number.isNaN(date.getTime()) ? "" : new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(date);
  }

  function matchesQuery(values) {
    if (!state.query) return true;
    const haystack = values.filter(Boolean).join(" ").toLocaleLowerCase();
    return haystack.includes(state.query.toLocaleLowerCase());
  }

  function normalizeListInput(value) {
    return [...new Set(String(value || "").split(/[\n,，、]+/).map(item => item.trim()).filter(Boolean))];
  }

  function analysisScope() {
    return state.data.analysisScopes?.find(scope => scope.id === state.analysisScopeId) || state.data.analysisScopes?.[0] || null;
  }

  function analysisPostRecords(scope) {
    if (!scope) return [];
    const filters = scope.filters || {};
    const selected = new Set(filters.selectedPostIds || []);
    const byId = new Map();
    for (const post of state.data.discoveredPosts || []) byId.set(post.id, post);
    for (const post of state.data.posts || []) {
      if (!byId.has(post.id)) byId.set(post.id, {
        id: post.id,
        platform: post.platform,
        url: post.url,
        title: post.title,
        createTime: 0,
        keywords: []
      });
    }
    const include = filters.includeKeywords || [];
    const exclude = filters.excludeKeywords || [];
    const cutoff = Number(filters.relativeDays || 0) > 0 ? Date.now() - Number(filters.relativeDays) * 86400000 : 0;
    const matchesText = post => [post.title, post.nickname, post.keyword, ...(post.keywords || []), post.region, post.location].filter(Boolean).join(" ").toLocaleLowerCase();
    return [...byId.values()].filter(post => {
      if (selected.size && !selected.has(post.id)) return false;
      if (filters.platform && filters.platform !== "all" && post.platform !== filters.platform) return false;
      const text = matchesText(post);
      const keywordMatched = filters.matchMode === "any"
        ? include.some(keyword => text.includes(keyword.toLocaleLowerCase()))
        : include.every(keyword => text.includes(keyword.toLocaleLowerCase()));
      if (include.length && !keywordMatched) return false;
      if (exclude.some(keyword => text.includes(keyword.toLocaleLowerCase()))) return false;
      if (filters.region && !text.includes(String(filters.region).toLocaleLowerCase())) return false;
      const publishedAt = Number(post.createTime || 0) * 1000;
      if (cutoff && publishedAt > 0 && publishedAt < cutoff) return false;
      return true;
    });
  }

  function commentTimeValue(row) {
    return Number(row.commentCreatedAt || row.createdAt || row.commentAt || row.collectedAt || 0);
  }

  function analysisUserRows(scope) {
    const posts = analysisPostRecords(scope);
    const postIds = new Set(posts.map(post => post.id));
    const postById = new Map(posts.map(post => [post.id, post]));
    const userByProfile = new Map((state.data.users || []).map(user => [user.profile, user]));
    const filters = scope?.filters || {};
    const commentsByProfile = new Map();
    for (const row of state.data.comments || []) {
      if (!row.profile || !postIds.has(row.postId)) continue;
      const user = userByProfile.get(row.profile) || row;
      const gender = user.gender && user.gender !== "未知" ? user.gender : row.gender || "未知";
      if (filters.genderFilter === "female" && gender !== "女") continue;
      if (filters.genderFilter === "male" && gender !== "男") continue;
      const location = [user.profileLocation, user.ipRegion, row.profileLocation, row.ipRegion].filter(Boolean).join(" ");
      if (filters.region && !location.toLocaleLowerCase().includes(String(filters.region).toLocaleLowerCase())) continue;
      const bucket = commentsByProfile.get(row.profile) || { user, rows: [], posts: new Set() };
      bucket.rows.push(row);
      bucket.posts.add(row.postId);
      commentsByProfile.set(row.profile, bucket);
    }
    const excludePhrases = filters.excludePhrases || [];
    return [...commentsByProfile.entries()].map(([profile, bucket]) => {
      const user = { ...bucket.user, profile };
      const evidence = [user.nickname, user.profileLocation, ...bucket.rows.map(row => row.text)].filter(Boolean).join(" ");
      const matchedExcludePhrase = excludePhrases.find(phrase => evidence.toLocaleLowerCase().includes(String(phrase).toLocaleLowerCase())) || "";
      const decision = scope.userDecisions?.[profile];
      const status = decision?.status || (matchedExcludePhrase ? "excluded" : "candidate");
      const times = bucket.rows.map(commentTimeValue).filter(Boolean).sort((a, b) => a - b);
      const likes = bucket.rows.map(row => Number(row.likeCount || 0)).filter(Number.isFinite);
      const replies = bucket.rows.map(row => Number(row.replyCount || 0)).filter(Number.isFinite);
      return {
        ...user,
        status,
        statusSource: decision ? "manual" : matchedExcludePhrase ? "explicit" : "pending",
        matchedExcludePhrase,
        postCount: bucket.posts.size,
        commentCount: bucket.rows.length,
        totalLikeCount: likes.reduce((total, value) => total + value, 0),
        totalReplyCount: replies.reduce((total, value) => total + value, 0),
        maxLikeCount: likes.length ? Math.max(...likes) : 0,
        firstCommentAt: times[0] || 0,
        lastCommentAt: times[times.length - 1] || 0,
        commentTimeApproximate: bucket.rows.some(row => !row.commentCreatedAt && !row.createdAt && !row.commentAt),
        lastCollectedAt: Math.max(...bucket.rows.map(row => Number(row.collectedAt || 0)), 0),
        evidence: bucket.rows.slice().sort((a, b) => commentTimeValue(b) - commentTimeValue(a)).slice(0, 3).map(row => ({
          text: row.text || "",
          postId: row.postId,
          postUrl: row.postUrl,
          postTitle: postById.get(row.postId)?.title || ""
        }))
      };
    }).sort((left, right) => Number(right.commentCount || 0) - Number(left.commentCount || 0) || Number(right.totalLikeCount || 0) - Number(left.totalLikeCount || 0) || String(left.nickname || "").localeCompare(String(right.nickname || "")));
  }

  function setNotice(message) {
    $("#notice").textContent = message || "";
  }

  function discoveryRuntimeText(runtime = {}) {
    if (runtime.status === "waiting-login") {
      return {
        label: "等待抖音登录",
        detail: "请在弹出的独立窗口中扫码或完成验证码登录，登录后会自动继续。",
        kind: "warning"
      };
    }
    if (runtime.status === "waiting-verification") {
      return {
        label: "等待完成平台验证",
        detail: "已打开独立验证窗口，完成后会自动继续当前发现任务。",
        kind: "warning"
      };
    }
    if (runtime.status === "running") {
      const progress = runtime.maxPages ? `第 ${Number(runtime.page || 0)}/${Number(runtime.maxPages)} 轮` : "正在读取";
      return {
        label: "本地发现运行中",
        detail: `${progress} · 收到 ${formatCount(runtime.responses)} 个接口响应 · 解析 ${formatCount(runtime.items)} 条帖子`,
        kind: "running"
      };
    }
    if (runtime.status === "completed") {
      const stopLabels = {
        target_reached: "已达到目标数量",
        no_more_results: "已到达搜索结果末尾",
        max_pages: "已达到设置的加载轮数",
        risk: "遇到平台风控",
        no_search_response: "未收到平台搜索接口响应"
      };
      return {
        label: "最近一次发现已结束",
        detail: `${Number(runtime.page || 0)} 轮 · ${formatCount(runtime.responses)} 个接口响应 · ${formatCount(runtime.items)} 条帖子${stopLabels[runtime.stopReason] ? ` · ${stopLabels[runtime.stopReason]}` : ""}`,
        kind: runtime.stopReason === "no_search_response" ? "warning" : "done"
      };
    }
    if (runtime.status === "failed") {
      return { label: "最近一次发现失败", detail: runtime.error || "后台服务返回了错误。", kind: "warning" };
    }
    if (runtime.status === "offline") return { label: "本地发现服务未连接", detail: "请重新加载扩展或启动自动后台服务。", kind: "warning" };
    return { label: "本地发现服务待命", detail: "尚未执行新的发现任务。", kind: "idle" };
  }

  async function refreshDiscoveryRuntime(shouldRender = true) {
    if (state.view !== "discover" && !state.discoveryBusy) return;
    const response = await chrome.runtime.sendMessage({ type: "GET_DISCOVERY_STATUS" }).catch(() => null);
    const next = response?.discovery || (response?.available === false ? { status: "offline" } : { status: "unknown" });
    const previous = state.discoveryRuntime || {};
    state.discoveryRuntime = next;
    if (state.discoveryBusy && ["completed", "failed"].includes(next.status)) state.discoveryBusy = false;
    if (shouldRender && JSON.stringify(previous) !== JSON.stringify(next) && state.view === "discover") render();
  }

  function syncTimeText(value) {
    if (!value) return "尚未同步";
    const date = new Date(Number(value));
    if (Number.isNaN(date.getTime())) return "尚未同步";
    return new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(date);
  }

  function setCloudSyncStatus() {
    const summary = $(".sync-summary");
    const text = $("#cloudSyncStatusText");
    const time = $("#cloudSyncLastTime");
    const loggedIn = Boolean(state.cloudConfig?.accessToken && state.cloudUser);
    const syncState = state.cloudSyncState || {};
    summary.classList.toggle("syncing", loggedIn && syncState.status === "syncing");
    summary.classList.toggle("success", loggedIn && syncState.status === "success");
    summary.classList.toggle("error", loggedIn && syncState.status === "error");
    if (!loggedIn) {
      text.textContent = "登录后自动同步";
      time.textContent = "尚未同步";
      time.dateTime = "";
      return;
    }
    text.textContent = syncState.status === "syncing"
      ? "同步中"
      : syncState.status === "success"
        ? "已自动同步"
        : syncState.status === "error"
          ? "同步失败"
          : "等待自动同步";
    time.textContent = syncTimeText(syncState.lastSyncedAt);
    time.dateTime = syncState.lastSyncedAt ? new Date(syncState.lastSyncedAt).toISOString() : "";
  }

  function setCloudAccountStatus() {
    const status = $("#cloudAccountStatus");
    const statusText = $("#cloudAccountStatusText");
    const button = $("#cloudAccountButton");
    const loggedIn = Boolean(state.cloudConfig?.accessToken && state.cloudUser);
    statusText.textContent = loggedIn ? state.cloudUser.email : "未登录";
    status.classList.toggle("connected", loggedIn);
    button.textContent = loggedIn ? "账户设置" : "登录 / 注册";
    setCloudSyncStatus();
  }

  async function loadCloudSyncState() {
    const stored = await chrome.storage.local.get({ cloudSyncState: null });
    if (stored.cloudSyncState) state.cloudSyncState = stored.cloudSyncState;
    setCloudSyncStatus();
  }

  async function loadCloudAccount() {
    const config = await CommentFilterCloudSync.getConfig();
    state.cloudConfig = config;
    state.cloudUser = null;
    if (config.accessToken) {
      try {
        const response = await CommentFilterCloudSync.getCurrentUser();
        state.cloudUser = response.user || null;
      } catch (error) {
        await CommentFilterCloudSync.logout();
        state.cloudConfig = await CommentFilterCloudSync.getConfig();
        setNotice("账户登录已过期，请重新登录。");
      }
    }
    setCloudAccountStatus();
  }

  function openCloudLoginModal() {
    const config = state.cloudConfig || {};
    $("#cloudLoginEmail").value = config.email || state.cloudUser?.email || "";
    $("#cloudLoginPassword").value = "";
    $("#accountCode").value = "";
    $("#cloudLoginNotice").textContent = state.cloudUser ? `当前账户：${state.cloudUser.email}` : "";
    $("#cloudLogoutButton").hidden = !state.cloudUser;
    $("#cloudLoginTitle").textContent = state.cloudUser ? "账户" : "账户登录";
    setAccountMode(state.cloudUser ? "password" : state.accountMode);
    $("#cloudLoginModal").hidden = false;
    $("#cloudLoginEmail").focus();
  }

  function closeCloudLoginModal() {
    $("#cloudLoginModal").hidden = true;
  }

  function setAccountMode(mode) {
    state.accountMode = mode;
    const codeMode = mode === "code" || mode === "register";
    const registerMode = mode === "register";
    $("#accountCodeWrap").hidden = !codeMode;
    $("#accountPasswordWrap").hidden = mode === "code";
    $("#accountCode").required = codeMode;
    $("#cloudLoginPassword").required = mode !== "code";
    $("#cloudLoginPassword").autocomplete = registerMode ? "new-password" : "current-password";
    $("#accountPasswordHint").textContent = registerMode ? "注册密码至少需要 12 个字符。" : "密码至少需要 12 个字符。";
    $("#submitCloudLogin").textContent = registerMode ? "注册并登录" : "登录";
    document.querySelectorAll(".account-tab").forEach(tab => {
      const active = tab.dataset.accountMode === mode;
      tab.classList.toggle("active", active);
      tab.setAttribute("aria-selected", String(active));
    });
  }

  function startCodeCooldown(seconds = 60) {
    const button = $("#sendAccountCode");
    if (state.codeTimer) clearInterval(state.codeTimer);
    let remaining = seconds;
    const update = () => {
      button.disabled = remaining > 0;
      button.textContent = remaining > 0 ? `${remaining}s 后重发` : "获取验证码";
      remaining -= 1;
      if (remaining < 0) {
        clearInterval(state.codeTimer);
        state.codeTimer = null;
      }
    };
    update();
    state.codeTimer = setInterval(update, 1000);
  }

  async function sendAccountCode() {
    const email = $("#cloudLoginEmail").value.trim();
    if (!email) {
      $("#cloudLoginNotice").textContent = "请先填写邮箱。";
      $("#cloudLoginEmail").focus();
      return;
    }
    const purpose = state.accountMode === "register" ? "register" : "login";
    const config = state.cloudConfig || await CommentFilterCloudSync.getConfig();
    const button = $("#sendAccountCode");
    button.disabled = true;
    $("#cloudLoginNotice").textContent = "正在发送验证码...";
    try {
      const result = await CommentFilterCloudSync.requestCode(email, purpose, config);
      $("#cloudLoginNotice").textContent = `验证码已发送到 ${email}，${Math.ceil((result.expiresIn || 600) / 60)} 分钟内有效。`;
      startCodeCooldown(60);
    } catch (error) {
      button.disabled = false;
      button.textContent = "获取验证码";
      $("#cloudLoginNotice").textContent = error.message || "验证码发送失败。";
    }
  }

  async function loginCloud(event) {
    event.preventDefault();
    const button = $("#submitCloudLogin");
    const email = $("#cloudLoginEmail").value.trim();
    const password = $("#cloudLoginPassword").value;
    const code = $("#accountCode").value.trim();
    if (!email || (state.accountMode !== "code" && !password) || ((state.accountMode === "code" || state.accountMode === "register") && !code)) return;
    button.disabled = true;
    $("#cloudLoginNotice").textContent = "正在登录...";
    try {
      const config = state.cloudConfig || await CommentFilterCloudSync.getConfig();
      const nextConfig = state.accountMode === "register"
        ? await CommentFilterCloudSync.register(email, code, password, config)
        : state.accountMode === "code"
          ? await CommentFilterCloudSync.loginWithCode(email, code, config)
          : await CommentFilterCloudSync.login(email, password, config);
      state.cloudConfig = nextConfig;
      const response = await CommentFilterCloudSync.getCurrentUser();
      state.cloudUser = response.user || null;
      setCloudAccountStatus();
      closeCloudLoginModal();
      try {
        await syncCloudSnapshot();
        setNotice(`已登录账户：${state.cloudUser?.email || email}，本地数据已自动同步。`);
      } catch (syncError) {
        setNotice(`已登录账户：${state.cloudUser?.email || email}，自动同步失败：${syncError.message || "请稍后重试"}`);
      }
    } catch (error) {
      $("#cloudLoginNotice").textContent = error.message || "登录失败。";
    } finally {
      button.disabled = false;
    }
  }

  async function logoutCloud() {
    await CommentFilterCloudSync.logout();
    state.cloudConfig = await CommentFilterCloudSync.getConfig();
    state.cloudUser = null;
    state.cloudSyncState = { status: "idle", lastSyncedAt: 0 };
    await chrome.storage.local.set({ cloudSyncState: state.cloudSyncState });
    setCloudAccountStatus();
    closeCloudLoginModal();
    setNotice("已退出账户，之后的数据只保存在本机。");
  }

  async function syncCloudSnapshot() {
    if (!state.cloudUser) {
      openCloudLoginModal();
      return;
    }
    state.cloudSyncState = { ...state.cloudSyncState, status: "syncing" };
    await chrome.storage.local.set({ cloudSyncState: state.cloudSyncState });
    setCloudSyncStatus();
    try {
      const snapshot = await CommentFilterDatabase.getDashboardData();
      const result = await CommentFilterCloudSync.syncSnapshot(snapshot);
      state.cloudSyncState = { status: "success", lastSyncedAt: Date.now(), counts: result.counts || {} };
      await chrome.storage.local.set({ cloudSyncState: state.cloudSyncState });
      setCloudSyncStatus();
      return result;
    } catch (error) {
      state.cloudSyncState = { ...state.cloudSyncState, status: "error" };
      await chrome.storage.local.set({ cloudSyncState: state.cloudSyncState });
      setCloudSyncStatus();
      throw error;
    }
  }

  function updateCounts() {
    $("#postCount").textContent = state.data.posts.length.toLocaleString("zh-CN");
    $("#commentCount").textContent = state.data.comments.length.toLocaleString("zh-CN");
    $("#userCount").textContent = state.data.users.length.toLocaleString("zh-CN");
    const byProfile = new Map(state.data.users.map(user => [user.profile, user]));
    state.userByProfile = byProfile;
  }

  function renderPostFilter() {
    const select = $("#postFilter");
    const selected = state.postId;
    select.innerHTML = '<option value="">全部帖子</option>' + state.data.posts.map(post =>
      `<option value="${escapeHtml(post.id)}">${escapeHtml(`${postNumberLabel(post)} · ${post.title || post.url} (${Number(post.commentCount || 0)})`)}</option>`
    ).join("");
    if (state.data.posts.some(post => post.id === selected)) select.value = selected;
    else state.postId = "";
  }

  function renderTaskFilter() {
    const select = $("#taskFilter");
    const selected = state.taskId;
    const posts = state.postId ? state.data.posts.filter(post => post.id === state.postId) : state.data.posts;
    const tasks = posts.flatMap(post => (Array.isArray(post.filterTasks) ? post.filterTasks : []).map(task => ({ task, post })));
    const taskIds = new Set(tasks.map(item => item.task.id));
    const unassignedCount = state.data.comments.filter(row => {
      if (state.postId && row.postId !== state.postId) return false;
      return !Array.isArray(row.filterTaskIds) || row.filterTaskIds.length === 0;
    }).length;
    const options = ['<option value="">全部任务</option>', ...tasks.map(({ task, post }) => {
      const number = taskNumberLabel(task);
      const title = post.title || post.url || "未命名帖子";
      return `<option value="${escapeHtml(task.id)}">${escapeHtml(`${postNumberLabel(post)} · ${number} · ${title} · 命中 ${Number(task.matchedCount || 0).toLocaleString("zh-CN")} 条`)}</option>`;
    })];
    if (unassignedCount) options.push(`<option value="__unassigned__">未关联任务（${unassignedCount.toLocaleString("zh-CN")} 条历史评论）</option>`);
    select.innerHTML = options.join("");
    select.disabled = tasks.length === 0 && !unassignedCount;
    if (taskIds.has(selected) || (selected === "__unassigned__" && unassignedCount)) select.value = selected;
    else state.taskId = "";
  }

  function renderPosts() {
    const posts = state.data.posts.filter(post => matchesQuery([post.title, post.url, post.sourceUrl, platformName(post.platform)]));
    if (!posts.length) return emptyMarkup("还没有帖子记录", "完成一次评论扫描后，帖子和评论会保存在这个浏览器的数据库里。");
    return `<div class="post-list">${posts.map(post => `
          <article class="post-entry">
        <div class="post-main">
          <div class="post-info">
            <div class="post-heading"><span class="post-number">${escapeHtml(postNumberLabel(post))}</span><span class="platform">${platformName(post.platform)}</span><h2 class="post-title">${escapeHtml(post.title || "未读取到标题")}</h2></div>
            <div class="post-source"><span>原始帖子</span><a href="${escapeHtml(post.url)}" target="_blank" rel="noreferrer">${escapeHtml(post.url)}</a></div>
            ${post.sourceUrl && post.sourceUrl !== post.url ? `<div class="post-source"><span>发现来源页</span><a href="${escapeHtml(post.sourceUrl)}" target="_blank" rel="noreferrer">${escapeHtml(post.sourceUrl)}</a></div>` : ""}
          </div>
          <div class="post-side"><div class="post-stats"><span><strong>${Number(post.commentCount || 0).toLocaleString("zh-CN")}</strong> 条已保存评论</span>${Number(post.platformCommentCount || 0) ? `<span>平台显示 ${Number(post.platformCommentCount).toLocaleString("zh-CN")} 条</span>` : ""}<span>最近抓取 ${escapeHtml(formatDate(post.lastScannedAt) || "未知")}</span></div>
            <div class="row-actions"><a class="row-action" href="${escapeHtml(post.url)}" target="_blank" rel="noreferrer">打开原帖</a><button class="row-action" type="button" data-open-post="${escapeHtml(post.id)}">查看评论</button><button class="row-action" type="button" data-refresh-post="${escapeHtml(post.url)}">按当前条件更新</button><button class="row-action danger" type="button" data-delete-post="${escapeHtml(post.url)}">删除此帖数据</button></div>
          </div>
        </div>
        ${filterTasksMarkup(post)}
      </article>`).join("")}</div>`;
  }

  function filterTaskLabel(task) {
    const filters = task.filters || {};
    const parts = [];
    parts.push(`关键词：${filters.keyword || "不限"}`);
    if (filters.excludeKeyword) parts.push(`排除：${filters.excludeKeyword}`);
    parts.push(`地区：${filters.region || "不限"}`);
    parts.push(filters.matchMode === "any" ? "任一条件" : "全部条件");
    parts.push(filters.relativeDays ? `时间：${filters.relativeDays}天内` : "时间：不限");
    parts.push(`性别：${filters.genderFilter === "female" ? "女" : filters.genderFilter === "male" ? "男" : "全部"}`);
    if (filters.targetGenderCount) parts.push(`目标：${filters.targetGenderCount}人`);
    parts.push(filters.scrollLimit ? `接口页数：${filters.scrollLimit}页` : "接口页数：全部");
    parts.push(`方式：${filters.scanMode === "replace" ? "替换" : "追加"}`);
    return parts;
  }

  function filterTasksMarkup(post) {
    const tasks = Array.isArray(post.filterTasks) ? post.filterTasks : [];
    if (!tasks.length) return `<div class="post-tasks task-empty">暂无筛选任务记录</div>`;
    const latest = tasks[0];
    const preview = [latest.filters?.keyword && `关键词 ${latest.filters.keyword}`, latest.filters?.region && `地区 ${latest.filters.region}`, latest.filters?.genderFilter === "female" ? "女性" : latest.filters?.genderFilter === "male" ? "男性" : "全部性别"].filter(Boolean).join(" · ");
    return `<details class="post-tasks" data-task-post="${escapeHtml(post.id)}"${state.openTaskPosts.has(post.id) ? " open" : ""}><summary><span class="task-summary-title">${escapeHtml(postNumberLabel(post))} · 筛选任务 <span class="task-count">${tasks.length}</span></span><span class="task-preview">最近：${escapeHtml(preview)} · 命中 ${Number(latest.matchedCount || 0).toLocaleString("zh-CN")} 条</span><span class="task-chevron" aria-hidden="true"></span></summary><ol class="task-list">${tasks.map((task, index) => {
      const status = task.status === "stopped" ? "已停止" : task.status === "completed" ? (task.completionState === "partial" ? "已完成（有缺口）" : "已完成") : task.status === "profile-incomplete" ? "主页资料未完成" : task.status === "waiting-profile" ? "补充主页资料中" : task.status === "waiting-verification" ? "等待平台验证" : task.status;
      const taskLabel = taskNumberLabel(task);
      const audit = task.pagination || null;
      const auditText = audit
        ? `接口页 ${Number(audit.apiPagesRead || 0)} · 原始 ${Number(audit.rawRowsRead || 0)} · ${audit.paginationComplete ? "已确认到末页" : audit.stopReason === "goal_reached" ? "达到目标提前结束" : "未确认到末页"}${Number(audit.platformCommentCount || 0) ? ` · 平台总数 ${Number(audit.platformCommentCount).toLocaleString("zh-CN")}` : ""}`
        : "尚无分页审计";
      const profileIncomplete = status === "主页资料未完成" || task.profileStatus === "incomplete";
      const profileErrors = Array.isArray(task.profileErrors) ? task.profileErrors : [];
      const profileText = profileIncomplete
        ? `主页资料 ${Number(task.profileEnrichedCount || 0)}/${Number(task.profileTargetCount || 0)} · 失败 ${profileErrors.length || "未知"}`
        : "";
      const firstProfileError = profileErrors.find(error => error?.message)?.message || (!profileErrors.length && profileIncomplete ? "旧任务未保存失败原因，请重新按此条件更新" : "");
      const taskAuditText = [auditText, profileText, firstProfileError].filter(Boolean).join(" · ");
      return `<li class="task-item"><div class="task-item-head"><div><strong>${escapeHtml(postNumberLabel(post))} · ${escapeHtml(taskLabel)}</strong><span class="task-status">${escapeHtml(status)}</span><span class="task-date">${escapeHtml(formatDate(task.completedAt || task.startedAt))}</span></div><strong class="task-result">命中 ${Number(task.matchedCount || 0).toLocaleString("zh-CN")} 条</strong></div><div class="task-tags">${filterTaskLabel(task).map(label => `<span class="task-tag">${escapeHtml(label)}</span>`).join("")}</div><div class="task-audit">${escapeHtml(taskAuditText)}</div><div class="task-footer"><span title="${escapeHtml(task.id)}">${index === 0 ? "最近一次" : `历史任务 ${index + 1}`}</span><span class="task-actions"><button class="row-action" type="button" data-view-task="${escapeHtml(task.id)}" data-task-owner="${escapeHtml(post.id)}">查看本次评论</button><button class="row-action" type="button" data-refresh-task="${escapeHtml(task.id)}" data-task-owner="${escapeHtml(post.id)}">按此条件更新</button><button class="row-action danger" type="button" data-delete-task="${escapeHtml(task.id)}" data-task-owner="${escapeHtml(post.id)}" data-task-post-url="${escapeHtml(post.url)}">删除本次数据</button></span></div></li>`;
    }).join("")}</ol></details>`;
  }

  function genderMarkup(gender) {
    const value = gender || "未知";
    const kind = value === "女" ? "female" : value === "男" ? "male" : "";
    return `<span class="gender ${kind}">${escapeHtml(value)}</span>`;
  }

  function genderSelectMarkup(user) {
    const value = ["男", "女", "未知"].includes(user.gender) ? user.gender : "未知";
    return `<select class="gender-select" data-gender-profile="${escapeHtml(user.profile)}" aria-label="更新 ${escapeHtml(user.nickname || "用户")} 的性别"><option value="未知"${value === "未知" ? " selected" : ""}>未知</option><option value="男"${value === "男" ? " selected" : ""}>男</option><option value="女"${value === "女" ? " selected" : ""}>女</option></select>`;
  }

  function platformFromProfile(profile) {
    if (/xiaohongshu\.com/i.test(profile || "")) return "小红书";
    if (/douyin\.com/i.test(profile || "")) return "抖音";
    if (/kuaishou\.com/i.test(profile || "")) return "快手";
    return "平台";
  }

  function profileKey(profile) {
    try {
      const url = new URL(profile);
      url.search = "";
      url.hash = "";
      return url.href.replace(/\/$/, "");
    } catch (error) {
      return String(profile || "");
    }
  }

  function messageLogFor(profile) {
    return state.messageLog?.[profileKey(profile)] || null;
  }

  function commentActionKey(row) {
    return [row.postUrl || "", row.commentId || row.cid || "", row.profile || row.nickname || "", row.text || ""].join("||");
  }

  function messageActionMarkup(user) {
    const log = messageLogFor(user.profile);
    if (log?.status === "sent") return `<button class="row-action message-sent" type="button" disabled>已私信</button>`;
    return `<button class="row-action" type="button" data-message-profile="${escapeHtml(user.profile)}">私信</button>`;
  }

  function renderComments() {
    const comments = state.data.comments.filter(row => {
      if (state.postId && row.postId !== state.postId) return false;
      if (state.taskId === "__unassigned__" && Array.isArray(row.filterTaskIds) && row.filterTaskIds.length) return false;
      if (state.taskId && state.taskId !== "__unassigned__" && (!Array.isArray(row.filterTaskIds) || !row.filterTaskIds.includes(state.taskId))) return false;
      return matchesQuery([row.nickname, row.text, row.ipRegion, row.profileLocation, row.profileAge, row.profile, row.postUrl, row.rawTimeText]);
    });
    if (!comments.length) return emptyMarkup("没有匹配的评论", state.query || state.postId || state.taskId ? "试试修改搜索内容、帖子或任务筛选。" : "扫描到的评论会集中显示在这里。");
    const postById = new Map(state.data.posts.map(post => [post.id, post]));
    return `<div class="comment-list">${comments.map(row => {
      const user = state.userByProfile.get(row.profile) || {};
      const post = postById.get(row.postId);
      const gender = user.gender && user.gender !== "未知" ? user.gender : row.gender;
      const age = user.profileAge || row.profileAge;
      const location = user.profileLocation || row.profileLocation;
      const taskLabels = taskLabelsForComment(row, post);
      const rowPlatform = row.platform || post?.platform || "";
      const canLike = rowPlatform === "douyin" && row.postUrl;
      const likeKey = commentActionKey(row);
      const likeButton = canLike
        ? `<button class="row-action${state.likedComments.has(likeKey) ? " message-sent" : ""}" type="button" data-like-comment="${escapeHtml(likeKey)}" data-like-post-url="${escapeHtml(row.postUrl)}" data-like-comment-id="${escapeHtml(row.commentId || row.cid || "")}" data-like-nickname="${escapeHtml(row.nickname || "")}" data-like-text="${escapeHtml(row.text || "")}"${state.likedComments.has(likeKey) ? " disabled" : ""}>${state.likedComments.has(likeKey) ? "已点赞" : "点赞"}</button>`
        : "";
      return `<article class="comment">
        <div class="comment-user"><div class="comment-name">${escapeHtml(row.nickname || "未知昵称")}${genderMarkup(gender)}</div>
          <div class="comment-meta"><span>${escapeHtml(postNumberLabel(post))}</span><span>${escapeHtml(row.rawTimeText || row.dateText || "时间未知")}</span><span>${escapeHtml(row.ipRegion || "属地未知")}</span>${age ? `<span>${escapeHtml(age)}</span>` : ""}${location ? `<span>${escapeHtml(location)}</span>` : ""}</div>
        </div>
        <div><div class="comment-text">${escapeHtml(row.text)}</div>
          <div class="comment-meta"><span>${escapeHtml(taskLabels.length ? taskLabels.join("、") : "未关联任务")}</span><span>赞 ${escapeHtml(row.likeCount || "0")}</span><span>回复 ${escapeHtml(row.replyCount || "0")}</span><span>${escapeHtml(post?.title || platformName(row.platform))}</span></div>
          <div class="comment-links">${likeButton}${row.profile ? `<a href="${escapeHtml(row.profile)}" target="_blank" rel="noreferrer">打开主页</a>` : ""}${row.postUrl ? `<a href="${escapeHtml(row.postUrl)}" target="_blank" rel="noreferrer" data-open-comment-post="${escapeHtml(row.postUrl)}">打开帖子</a>` : ""}</div>
        </div>
      </article>`;
    }).join("")}</div>`;
  }

  function renderUsers() {
    const commentCounts = new Map();
    for (const row of state.data.comments) {
      if (row.profile) commentCounts.set(row.profile, (commentCounts.get(row.profile) || 0) + 1);
    }
    const users = state.data.users.filter(user => matchesQuery([user.nickname, user.gender, user.profileAge, user.profileLocation, user.ipRegion, user.profile]));
    if (!users.length) return emptyMarkup("还没有可关联的用户资料", "有主页链接的评论用户会在这里按主页归并。");
    return `<div class="table-wrap"><table class="user-table"><thead><tr><th>昵称</th><th>性别</th><th>年龄线索</th><th>主页所在地</th><th>评论属地</th><th>评论数</th><th>最后出现</th><th>私信状态</th><th></th></tr></thead><tbody>${users.map(user => `
      <tr><td>${escapeHtml(user.nickname || "未知昵称")}</td><td>${genderSelectMarkup(user)}</td><td>${escapeHtml(user.profileAge || "")}</td><td>${escapeHtml(user.profileLocation || "")}</td><td>${escapeHtml(user.ipRegion || "")}</td><td>${(commentCounts.get(user.profile) || 0).toLocaleString("zh-CN")}</td><td>${escapeHtml(formatDate(user.lastSeenAt))}</td><td>${messageLogFor(user.profile)?.status === "sent" ? `<span class="message-status sent">已发送</span>` : `<span class="message-status">未记录</span>`}</td><td><div class="row-actions"><a class="row-action open-profile" href="${escapeHtml(user.profile)}" target="_blank" rel="noreferrer">主页</a>${messageActionMarkup(user)}</div></td></tr>`).join("")}</tbody></table></div>`;
  }

  function renderDiscover() {
    const discoveredPosts = filteredDiscoveredPosts();
    const runtimeText = discoveryRuntimeText(state.discoveryRuntime);
    const commentsByPost = new Map();
    for (const row of state.data.comments) {
      const bucket = commentsByPost.get(row.postId) || { size: 0, profiles: new Set() };
      bucket.size += 1;
      if (row.profile) bucket.profiles.add(row.profile);
      commentsByPost.set(row.postId, bucket);
    }
    const selectedVisible = discoveredPosts.filter(post => state.discoverySelected.has(discoveryKey(post))).length;
    const selectedTotal = state.discoverySelected.size;
    const rows = discoveredPosts.map(post => {
      const status = discoveryStatus(post, commentsByPost);
      const key = discoveryKey(post);
      const keywords = Array.isArray(post.keywords) && post.keywords.length ? post.keywords : post.keyword ? [post.keyword] : [];
      const title = post.title || "未读取到标题";
      const analysisScopes = analysisScopesForDiscoveryPost(post);
      const analysisMarkup = analysisScopes.length
        ? '<span class="discovery-analysis joined">已加入 ' + formatCount(analysisScopes.length) + ' 个范围</span><span class="discovery-analysis-names">' + escapeHtml(analysisScopes.map(scope => scope.name).join("、")) + '</span>'
        : '<span class="discovery-analysis">未加入分析</span>';
      const created = post.createTime ? formatDate(Number(post.createTime) * 1000) : post.createTimeText || "时间未知";
      const fetched = discoveredFetchedAt(post) ? formatDate(discoveredFetchedAt(post)) : "时间未知";
      return `<tr>
        <td class="discovery-select-cell"><input type="checkbox" data-discover-select="${escapeHtml(key)}"${state.discoverySelected.has(key) ? " checked" : ""} aria-label="选择 ${escapeHtml(title)}"></td>
        <td><div class="discovery-title"><strong>${escapeHtml(title)}</strong><span class="discovery-author">${escapeHtml(post.nickname || "未知作者")}</span></div><a class="subtle" href="${escapeHtml(post.url)}" target="_blank" rel="noreferrer">${escapeHtml(post.url)}</a></td>
        <td><span class="platform">${escapeHtml(platformName(post.platform))}</span></td>
        <td><div class="discovery-metrics"><span>赞 ${platformMetricText(post, "diggCount")}</span><span>评 ${platformMetricText(post, "commentCount")}</span><span>转 ${platformMetricText(post, "shareCount")}</span><span>藏 ${platformMetricText(post, "collectCount")}</span></div></td>
        <td><div>${escapeHtml(created)}</div><div class="discovery-keywords">${keywords.length ? escapeHtml(keywords.join("、")) : "未记录关键词"}</div></td>
       <td>${escapeHtml(fetched)}</td>
       <td><span class="discovery-status ${status.kind}">${escapeHtml(status.label)}</span><span class="discovery-status-detail">${escapeHtml(status.detail)}</span></td>
        <td>${analysisMarkup}</td>
       <td><a class="row-action open-profile" href="${escapeHtml(post.url)}" target="_blank" rel="noreferrer">打开帖子</a></td>
      </tr>`;
    }).join("");
    let resultMarkup = rows
      ? `<div class="table-wrap"><table class="discovery-table"><thead><tr><th><span class="sr-only">选择</span></th><th>帖子</th><th>平台</th><th>帖子总数据（获取时）</th><th>发布时间 / 来源</th><th>抓取时间</th><th>评论状态</th><th></th></tr></thead><tbody>${rows}</tbody></table></div>`
      : emptyMarkup("还没有发现帖子", "在上方输入关键词并发现帖子，结果会保存在评论数据库中。不同关键词和平台的结果会合并展示。")
    resultMarkup = resultMarkup.replace("<th>评论状态</th><th></th>", "<th>评论状态</th><th>分析范围</th><th></th>");
    return `<section class="discovery-panel">
      <div class="discovery-header"><div><h2>发现帖子</h2><p>帖子总数据取自发现或打开帖子时的平台快照；评论状态单独显示本工具实际保存的评论和用户数量。</p></div><div class="discovery-header-side"><span class="discovery-total">共 ${formatCount(discoveredPosts.length)} 条</span><div class="discovery-runtime ${runtimeText.kind}"><span class="discovery-runtime-dot"></span><div><strong>${escapeHtml(runtimeText.label)}</strong><span>${escapeHtml(runtimeText.detail)}</span></div></div></div></div>
      <div class="discovery-form">
        <label><span>平台</span><select data-discover-platform><option value="douyin"${state.discoveryPlatform === "douyin" ? " selected" : ""}>抖音</option><option value="xhs"${state.discoveryPlatform === "xhs" ? " selected" : ""}>小红书</option><option value="kuaishou" disabled>快手（搜索接口待接入）</option></select></label>
        <label class="discovery-keyword-field"><span>搜索关键词</span><input data-discover-keyword type="search" value="${escapeHtml(state.discoveryKeyword)}" placeholder="例如：北京陪拍"></label>
        <label><span>排序</span><select data-discover-sort><option value="relevance"${state.discoverySort === "relevance" ? " selected" : ""}>综合</option><option value="likes"${state.discoverySort === "likes" ? " selected" : ""}>最多点赞</option><option value="latest"${state.discoverySort === "latest" ? " selected" : ""}>最新发布</option></select></label>
        <label><span>目标数量</span><input data-discover-limit type="number" min="1" max="500" value="${Number(state.discoveryLimit) || 50}"></label>
        <label><span>最多加载页数</span><input data-discover-pages type="number" min="1" max="100" value="${Number(state.discoveryPages) || 20}"></label>
        <button class="button primary" type="button" data-discover-run${state.discoveryBusy || state.discoveryScanBusy ? " disabled" : ""}>${state.discoveryBusy ? "发现中..." : "发现帖子"}</button>
      </div>
      <div class="discovery-filter-bar"><label><span>评论状态</span><select data-discover-status><option value="all">全部</option><option value="pending">未抓取评论</option><option value="done">已抓取评论</option></select></label></div>
      <div class="discovery-actions"><span>已选 ${formatCount(selectedTotal)} 条${selectedVisible !== selectedTotal ? `（当前显示 ${formatCount(selectedVisible)} 条）` : ""}</span><div class="row-actions"><button class="row-action" type="button" data-discover-select-all${!discoveredPosts.length || state.discoveryScanBusy ? " disabled" : ""}>选择当前结果</button><button class="row-action" type="button" data-discover-invert${!discoveredPosts.length || state.discoveryScanBusy ? " disabled" : ""}>反选当前结果</button><button class="button secondary" type="button" data-discover-create-analysis${!selectedTotal || state.discoveryBusy || state.discoveryScanBusy ? " disabled" : ""}>创建用户分析范围</button><button class="button primary" type="button" data-discover-start${!selectedTotal || state.discoveryBusy || state.discoveryScanBusy ? " disabled" : ""}>${state.discoveryScanBusy ? "抓取中..." : "抓取选中帖子评论和用户"}</button></div></div>
      <p class="discovery-note">抖音和小红书均通过独立后台浏览器读取各自网页自身接口，不需要第三方数据 API。小红书链接会保留搜索结果返回的访问参数；旧记录如果没有保存平台快照，会显示“—”，重新发现或打开帖子后即可记录新的总数据。</p>
    </section>${resultMarkup}`;
  }

  function analysisStatusLabel(status) {
    return status === "candidate" ? "候选用户" : status === "excluded" ? "明确排除" : status === "contacted" ? "已联系" : "待人工判断";
  }

  function renderAnalysis() {
    const scopes = state.data.analysisScopes || [];
    const scope = analysisScope();
    const filters = scope?.filters || {};
    const users = scope ? analysisUserRows(scope).filter(user => matchesQuery([
      user.nickname, user.gender, user.profileAge, user.profileLocation, user.ipRegion, user.profile,
      user.status, user.matchedExcludePhrase, ...user.evidence.map(item => item.text)
    ])) : [];
    const selectedPosts = [...state.discoverySelected];
    const summary = scope ? analysisPostRecords(scope) : [];
    const candidates = users.filter(user => user.status === "candidate").length;
    const excluded = users.filter(user => user.status === "excluded").length;
    const rows = users.map(user => {
      const evidence = user.evidence.length
        ? '<details class="analysis-evidence"><summary>查看评论证据</summary>' +
          user.evidence.map(item => '<p>' + escapeHtml(item.text) + '<a href="' + escapeHtml(item.postUrl || "#") + '" target="_blank" rel="noreferrer">' + escapeHtml(item.postTitle || "打开帖子") + '</a></p>').join("") +
          '</details>'
        : "";
      const accountUser = state.userByProfile.get(user.profile) || user;
      const contactAction = user.status === "excluded" ? "" : messageActionMarkup(accountUser);
      const scopeId = scope?.id || "";
      return '<tr>' +
        '<td><strong>' + escapeHtml(user.nickname || "未知昵称") + '</strong><span class="analysis-subline">' + escapeHtml(user.profile || "") + '</span></td>' +
        '<td>' + genderMarkup(user.gender || "未知") + '</td>' +
        '<td>' + formatCount(user.postCount) + '</td>' +
        '<td>' + formatCount(user.commentCount) + '</td>' +
        '<td>' + formatCount(user.totalLikeCount) + '</td>' +
        '<td>' + formatCount(user.totalReplyCount) + '</td>' +
        '<td>' + escapeHtml((user.commentTimeApproximate ? "≈" : "") + (formatDate(user.firstCommentAt) || "时间未知")) + '</td>' +
        '<td>' + escapeHtml((user.commentTimeApproximate ? "≈" : "") + (formatDate(user.lastCommentAt) || "时间未知")) + '</td>' +
        '<td><select class="analysis-status-select ' + escapeHtml(user.status) + '" data-analysis-status data-analysis-scope="' + escapeHtml(scopeId) + '" data-analysis-profile="' + escapeHtml(user.profile) + '" aria-label="更新 ' + escapeHtml(user.nickname || "用户") + ' 的候选状态">' +
          '<option value="pending"' + (user.status === "pending" ? " selected" : "") + '>待人工判断</option>' +
          '<option value="candidate"' + (user.status === "candidate" ? " selected" : "") + '>候选用户</option>' +
          '<option value="excluded"' + (user.status === "excluded" ? " selected" : "") + '>明确排除</option>' +
          '<option value="contacted"' + (user.status === "contacted" ? " selected" : "") + '>已联系</option>' +
        '</select>' +
        (user.matchedExcludePhrase ? '<span class="analysis-reason">排除词：' + escapeHtml(user.matchedExcludePhrase) + '</span>' : "") +
        evidence + '</td>' +
        '<td><div class="row-actions"><button class="row-action" type="button" data-analysis-open-comments="' + escapeHtml(user.profile) + '">评论</button><a class="row-action open-profile" href="' + escapeHtml(user.profile) + '" target="_blank" rel="noreferrer">主页</a>' + contactAction + '</div></td>' +
      '</tr>';
    }).join("");
    const table = rows
      ? '<div class="table-wrap"><table class="analysis-table"><thead><tr><th>用户</th><th>性别</th><th>帖子数</th><th>评论数</th><th>获赞总数</th><th>回复总数</th><th>首次评论 / 记录</th><th>最近评论 / 记录</th><th>候选状态</th><th></th></tr></thead><tbody>' + rows + '</tbody></table></div>'
      : emptyMarkup(scope ? "这个分析范围暂时没有匹配用户" : "还没有分析范围", scope ? "请先抓取匹配帖子的评论，或调整分析范围条件。" : "先创建一个分析范围，再从帖子和评论中生成用户统计。");
    return '<section class="analysis-panel">' +
      '<div class="analysis-header"><div><h2>用户分析</h2><p>只统计已抓取到的公开评论。没有数据库记录，不代表用户在平台上从未被其他人联系过。</p></div><span class="discovery-total">' + (scope ? "范围内 " + formatCount(summary.length) + " 个帖子" : "") + '</span></div>' +
      '<div class="analysis-form">' +
        '<label><span>分析范围名称</span><input data-analysis-name type="text" value="' + escapeHtml(scope?.name || "北京地陪候选") + '" placeholder="例如：北京地陪候选"></label>' +
        '<label><span>帖子关键词（全部满足）</span><input data-analysis-include type="text" value="' + escapeHtml((filters.includeKeywords || []).join("、") || state.discoveryKeyword) + '" placeholder="北京、地陪"></label>' +
        '<label><span>排除帖子关键词</span><input data-analysis-exclude type="text" value="' + escapeHtml((filters.excludeKeywords || []).join("、")) + '" placeholder="广告、招聘"></label>' +
        '<label><span>平台</span><select data-analysis-platform><option value="all"' + (filters.platform === "all" ? " selected" : "") + '>全部</option><option value="douyin"' + (filters.platform === "douyin" ? " selected" : "") + '>抖音</option><option value="xhs"' + (filters.platform === "xhs" ? " selected" : "") + '>小红书</option><option value="kuaishou"' + (filters.platform === "kuaishou" ? " selected" : "") + '>快手</option></select></label>' +
        '<label><span>用户/评论地区</span><input data-analysis-region type="text" value="' + escapeHtml(filters.region || "北京") + '" placeholder="北京"></label>' +
        '<label><span>关键词关系</span><select data-analysis-match><option value="all"' + (filters.matchMode !== "any" ? " selected" : "") + '>全部满足</option><option value="any"' + (filters.matchMode === "any" ? " selected" : "") + '>任意满足</option></select></label>' +
        '<label><span>性别</span><select data-analysis-gender><option value="all"' + (filters.genderFilter === "all" ? " selected" : "") + '>全部</option><option value="female"' + (filters.genderFilter === "female" ? " selected" : "") + '>女</option><option value="male"' + (filters.genderFilter === "male" ? " selected" : "") + '>男</option></select></label>' +
        '<label><span>帖子时间范围</span><input data-analysis-days type="number" min="0" max="3650" value="' + Number(filters.relativeDays || 0) + '" placeholder="0 表示不限"></label>' +
        '<label class="analysis-wide"><span>明确排除短语</span><input data-analysis-exclude-phrases type="text" value="' + escapeHtml((filters.excludePhrases || []).join("、") || "纯绿地陪、只接纯绿") + '" placeholder="纯绿地陪、只接纯绿"></label>' +
        '<div class="analysis-form-actions"><button class="button primary" type="button" data-analysis-create>' + (scope ? "保存分析范围" : "创建分析范围") + '</button>' + (scope ? '<button class="button secondary" type="button" data-analysis-refresh>重新计算</button><button class="button quiet" type="button" data-analysis-delete>删除范围</button>' : "") + '</div>' +
      '</div>' +
      '<div class="analysis-scope-bar"><label><span>当前分析范围</span><select data-analysis-scope><option value="">' + (scopes.length ? "请选择分析范围" : "暂无分析范围") + '</option>' + scopes.map(item => '<option value="' + escapeHtml(item.id) + '"' + (item.id === scope?.id ? " selected" : "") + '>' + escapeHtml(item.name) + '</option>').join("") + '</select></label><span>候选 ' + formatCount(candidates) + ' · 明确排除 ' + formatCount(excluded) + ' · 用户 ' + formatCount(users.length) + (selectedPosts.length ? " · 当前选中帖子 " + formatCount(selectedPosts.length) : "") + '</span></div>' +
      (scope ? '<div class="analysis-tags"><span>帖子：' + formatCount(summary.length) + '</span><span>关键词：' + escapeHtml((filters.includeKeywords || []).join("、") || "不限") + '</span><span>地区：' + escapeHtml(filters.region || "不限") + '</span><span>性别：' + (filters.genderFilter === "female" ? "女" : filters.genderFilter === "male" ? "男" : "全部") + '</span></div>' : "") +
      table +
    '</section>';
  }

  function updateDiscoverySelectionUi() {
    const visible = filteredDiscoveredPosts();
    const selectedVisible = visible.filter(post => state.discoverySelected.has(discoveryKey(post))).length;
    const summary = $(".discovery-actions > span");
    if (summary) summary.textContent = `已选 ${formatCount(state.discoverySelected.size)} 条${selectedVisible !== state.discoverySelected.size ? `（当前显示 ${formatCount(selectedVisible)} 条）` : ""}`;
    document.querySelectorAll("[data-discover-select]").forEach(input => {
      input.checked = state.discoverySelected.has(input.dataset.discoverSelect);
    });
    const start = $("[data-discover-start]");
    if (start) {
      start.disabled = !state.discoverySelected.size || state.discoveryBusy || state.discoveryScanBusy;
      start.textContent = state.discoveryScanBusy ? "抓取中..." : "抓取选中帖子评论和用户";
    }
    const createAnalysis = $("[data-discover-create-analysis]");
    if (createAnalysis) createAnalysis.disabled = !state.discoverySelected.size || state.discoveryBusy || state.discoveryScanBusy;
  }

  function emptyMarkup(title, description) {
    return `<div class="empty"><strong>${escapeHtml(title)}</strong><span>${escapeHtml(description)}</span></div>`;
  }

  const scanSettingKeys = ["keyword", "excludeKeyword", "region", "matchMode", "relativeDays", "scrollLimit", "genderFilter", "targetGenderCount"];

  async function getLatestScanSettings() {
    const values = await chrome.storage.local.get({
      keyword: "眼线",
      excludeKeyword: "",
      region: "北京",
      matchMode: "all",
      relativeDays: 30,
      scrollLimit: 0,
      genderFilter: "all",
      targetGenderCount: 0
    });
    return Object.fromEntries(scanSettingKeys.map(key => [key, values[key]]));
  }

  async function runDiscovery() {
    const keywordInput = $("[data-discover-keyword]");
    const sortInput = $("[data-discover-sort]");
    const limitInput = $("[data-discover-limit]");
    const pagesInput = $("[data-discover-pages]");
    const keyword = String(keywordInput?.value || "").trim();
    const limit = Math.max(1, Math.min(500, Number(limitInput?.value) || 50));
    const maxPages = Math.max(1, Math.min(100, Number(pagesInput?.value) || 20));
    const sort = ["relevance", "likes", "latest"].includes(sortInput?.value) ? sortInput.value : "relevance";
    state.discoveryKeyword = keyword;
    state.discoveryLimit = limit;
    state.discoveryPages = maxPages;
    state.discoverySort = sort;
    if (!keyword) {
      setNotice("请先输入搜索关键词。");
      keywordInput?.focus();
      return;
    }
    if (!["douyin", "xhs"].includes(state.discoveryPlatform)) {
      setNotice("这个平台的搜索发现接口还没有接入。");
      return;
    }
    state.discoveryBusy = true;
    render();
    setNotice(`正在使用独立后台浏览器读取${state.discoveryPlatform === "xhs" ? "小红书" : "抖音"}网页搜索接口...`);
    try {
      const response = await chrome.runtime.sendMessage({
        type: "DISCOVER_POSTS",
        options: { platform: state.discoveryPlatform, keyword, limit, maxPages, sort }
      });
      if (!response?.ok) throw new Error(response?.error || "发现帖子失败。");
      const statsCapturedAt = Date.now();
      const records = (response.results || []).map(record => ({
        ...record,
        platform: state.discoveryPlatform,
        keyword,
        statsSource: `${state.discoveryPlatform}-search`,
        statsCapturedAt
      }));
      await CommentFilterDatabase.upsertDiscoveredPosts(records);
      state.discoverySelected = new Set(records.map(discoveryKey));
      const platformLabel = state.discoveryPlatform === "xhs" ? "小红书" : "抖音";
      const stopLabels = {
        target_reached: "已达到目标数量",
        no_more_results: "已到达搜索结果末尾",
        max_pages: "已达到设置的加载轮数",
        risk: "遇到平台风控",
        no_search_response: "未收到平台搜索接口响应"
      };
      await refresh(`已发现 ${formatCount(records.length)} 条${platformLabel}帖子${response.pages ? `，读取 ${response.pages} 轮搜索结果` : ""}${stopLabels[response.stopReason] ? `，${stopLabels[response.stopReason]}` : ""}。`);
      if (response.riskMessage) setNotice(`${response.riskMessage} 已保存本次已发现的 ${formatCount(records.length)} 条结果。`);
    } catch (error) {
      setNotice(error.message || "发现帖子失败。");
    } finally {
      state.discoveryBusy = false;
      render();
    }
  }

  async function startSelectedDiscoveryScan() {
    const records = (state.data.discoveredPosts || []).filter(record => state.discoverySelected.has(discoveryKey(record)));
    if (!records.length) {
      setNotice("请先选择要抓取的帖子。");
      return;
    }
    if (!window.confirm(`将按当前评论筛选条件，逐个抓取已选择的 ${records.length} 个帖子，并补充评论用户资料。继续吗？`)) return;
    state.discoveryScanBusy = true;
    render();
    setNotice(`正在启动批量任务，将抓取 ${records.length} 个帖子...`);
    try {
      const filters = {
        ...(await getLatestScanSettings()),
        postUrls: records.map(record => record.url).join("\n"),
        scanMode: "append"
      };
      const response = await chrome.runtime.sendMessage({ type: "START_SCAN_TASK", filters, activeTab: null, options: { openTabsInactive: true } });
      if (!response?.ok) throw new Error(response?.error || "无法启动批量评论任务。");
      const taskLabel = Number(response.task?.taskNumber) > 0 ? `任务 #${Number(response.task.taskNumber)}` : "评论任务";
      setNotice(`${taskLabel}已启动，选中的帖子会在后台逐个抓取。`);
      const task = await waitForScanTask(response.task?.id);
      await refresh(task?.message || "选中帖子抓取完成。");
    } catch (error) {
      setNotice(error.message || "批量抓取失败。");
    } finally {
      state.discoveryScanBusy = false;
      render();
    }
  }

  async function createAnalysisFromDiscovery() {
    const records = (state.data.discoveredPosts || []).filter(record => state.discoverySelected.has(discoveryKey(record)));
    if (!records.length) {
      setNotice("请先选择要分析的帖子。");
      return;
    }
    const platforms = [...new Set(records.map(record => record.platform).filter(Boolean))];
    const keyword = String(state.discoveryKeyword || "").trim();
    const saved = await CommentFilterDatabase.saveAnalysisScope({
      name: (keyword || "选中帖子") + "用户分析",
      filters: {
        includeKeywords: [],
        excludeKeywords: [],
        region: "",
        platform: platforms.length === 1 ? platforms[0] : "all",
        matchMode: "all",
        genderFilter: "all",
        relativeDays: 0,
        excludePhrases: ["纯绿地陪", "只接纯绿"],
        selectedPostIds: records.map(discoveryKey)
      }
    });
    state.analysisScopeId = saved.id;
    state.view = "analysis";
    await refresh("已创建用户分析范围，包含 " + formatCount(records.length) + " 个帖子。");
  }

  function waitForScanTask(taskId) {
    return new Promise((resolve, reject) => {
      const finish = task => {
        chrome.storage.onChanged.removeListener(onChanged);
        resolve(task);
      };
      const onChanged = (changes, area) => {
        if (area !== "local") return;
        const progress = changes.scanProgress?.newValue;
        if (progress?.message) setNotice(progress.message);
        const task = changes.scanTask?.newValue;
        if (task?.id === taskId && !["running", "waiting-verification", "waiting-profile"].includes(task.status)) finish(task);
      };

      chrome.storage.onChanged.addListener(onChanged);
      chrome.storage.local.get({ scanTask: null })
        .then(({ scanTask }) => {
          if (scanTask?.id === taskId && !["running", "waiting-verification", "waiting-profile"].includes(scanTask.status)) finish(scanTask);
          else if (!scanTask || scanTask.id !== taskId) {
            chrome.storage.onChanged.removeListener(onChanged);
            reject(new Error("未找到帖子更新任务状态，请刷新数据库后确认结果。"));
          }
        })
        .catch(error => {
          chrome.storage.onChanged.removeListener(onChanged);
          reject(error);
        });
    });
  }

  async function refreshPost(postUrl, savedFilters = null) {
    const filters = savedFilters || await getLatestScanSettings();
    const response = await chrome.runtime.sendMessage({ type: "REFRESH_POST_DATA", postUrl, filters });
    if (!response?.ok) throw new Error(response?.error || "无法开始更新帖子。");
    const taskLabel = Number(response.task?.taskNumber) > 0 ? `任务 #${Number(response.task.taskNumber)}` : "更新任务";
    setNotice(`已启动${taskLabel}，正在打开帖子页面并抓取评论...`);
    const task = await waitForScanTask(response.task?.id);
    await refresh(task?.replacementSucceeded ? "帖子评论已按当前条件更新" : task?.message || "帖子更新结束");
  }

  async function saveCurrentAnalysisScope() {
    const existing = analysisScope();
    const includeKeywords = normalizeListInput($("[data-analysis-include]")?.value);
    const excludeKeywords = normalizeListInput($("[data-analysis-exclude]")?.value);
    const excludePhrases = normalizeListInput($("[data-analysis-exclude-phrases]")?.value);
    if (!includeKeywords.length && !state.discoverySelected.size && !existing?.filters?.selectedPostIds?.length) {
      setNotice("请填写至少一个帖子关键词，或先在“发现帖子”中选择帖子。");
      return;
    }
    const selectedPostIds = existing?.filters?.selectedPostIds?.length
      ? existing.filters.selectedPostIds
      : [...state.discoverySelected];
    const saved = await CommentFilterDatabase.saveAnalysisScope({
      ...(existing || {}),
      id: existing?.id,
      name: $("[data-analysis-name]")?.value || "未命名分析范围",
      filters: {
        includeKeywords,
        excludeKeywords,
        region: $("[data-analysis-region]")?.value || "",
        platform: $("[data-analysis-platform]")?.value || "all",
        matchMode: $("[data-analysis-match]")?.value || "all",
        genderFilter: $("[data-analysis-gender]")?.value || "all",
        relativeDays: Math.max(0, Number($("[data-analysis-days]")?.value) || 0),
        excludePhrases,
        selectedPostIds
      }
    });
    state.analysisScopeId = saved.id;
    await refresh("分析范围已保存，统计已重新计算。");
  }

  async function deleteCurrentAnalysisScope() {
    const scope = analysisScope();
    if (!scope || !window.confirm("确定删除这个分析范围吗？不会删除帖子、评论或用户原始数据。")) return;
    await CommentFilterDatabase.deleteAnalysisScope(scope.id);
    state.analysisScopeId = "";
    await refresh("分析范围已删除。");
  }

  async function deletePost(postUrl) {
    const { scanTask, profileTask } = await chrome.storage.local.get({ scanTask: null, profileTask: null });
    if ((scanTask?.status === "running" || scanTask?.status === "waiting-verification") || profileTask?.status === "running") {
      throw new Error("当前已有任务在运行，请完成后再删除帖子数据。");
    }
    const response = await chrome.runtime.sendMessage({ type: "DELETE_POST_DATA", postUrl });
    if (!response?.ok) throw new Error(response?.error || "删除帖子数据失败。");
    state.postId = "";
    await refresh(`已删除 ${response.deletedComments || 0} 条评论及无其他引用的用户资料`);
  }

  function render() {
    $("#postFilterWrap").hidden = state.view !== "comments";
    $("#taskFilterWrap").hidden = state.view !== "comments";
    renderPostFilter();
    renderTaskFilter();
    $("#content").innerHTML = state.view === "posts"
      ? renderPosts()
      : state.view === "comments"
        ? renderComments()
        : state.view === "users"
          ? renderUsers()
          : state.view === "discover"
           ? renderDiscover()
           : renderAnalysis();
    const discoveryStatus = $("[data-discover-status]");
    if (discoveryStatus) discoveryStatus.value = state.discoveryStatusFilter;
   document.querySelectorAll(".tab").forEach(tab => {
      const active = tab.dataset.view === state.view;
      tab.classList.toggle("active", active);
      tab.setAttribute("aria-selected", String(active));
    });
  }

  async function refresh(message = "") {
    try {
      const storedMessages = await chrome.storage.local.get({ directMessageLog: {} });
      state.messageLog = storedMessages.directMessageLog || {};
      state.data = await CommentFilterDatabase.getDashboardData();
      if (!state.analysisScopeId || !state.data.analysisScopes.some(scope => scope.id === state.analysisScopeId)) {
        state.analysisScopeId = state.data.analysisScopes[0]?.id || "";
      }
      updateCounts();
      renderPostFilter();
      render();
      setNotice(message || (state.cloudUser ? "数据保存在本机，并会自动同步到你的账户。" : "数据保存在当前浏览器中"));
    } catch (error) {
      $("#content").innerHTML = emptyMarkup("数据库读取失败", error.message || "请重新加载扩展后再试。");
      setNotice("读取失败");
    }
  }

  function commentsForUser(profile) {
    const postById = new Map(state.data.posts.map(post => [post.id, post]));
    return state.data.comments
      .filter(row => row.profile === profile)
      .sort((a, b) => Number(b.collectedAt || 0) - Number(a.collectedAt || 0))
      .map(row => ({ row, post: postById.get(row.postId) }))
      .slice(0, 3);
  }

  function buildMessageDraft(user) {
    const contexts = commentsForUser(user.profile);
    const first = contexts[0];
    const nickname = user.nickname || "你好";
    const postTitle = first?.post?.title || "你评论的帖子";
    const comment = String(first?.row?.text || "").replace(/[\r\n]+/g, " ").trim().slice(0, 90);
    if (comment) return `${nickname}，你好。我看到你在《${postTitle}》下面提到“${comment}”，觉得很有意思，想和你认识一下，方便聊聊吗？`;
    return `${nickname}，你好。我看到你在${platformFromProfile(user.profile)}的评论，觉得很有意思，想和你认识一下，方便聊聊吗？`;
  }

  function openMessageModal(profile) {
    const user = state.data.users.find(item => item.profile === profile);
    if (!user) return;
    if (messageLogFor(profile)?.status === "sent") {
      setNotice("这个用户已经有本机私信记录。");
      return;
    }
    const contexts = commentsForUser(profile);
    const contextText = contexts.length
      ? `参考内容：${contexts.map(({ row, post }) => `${post?.title || "帖子"}：${row.text || ""}`).join("；")}`
      : "没有找到可用于个性化的评论内容。";
    const modal = $("#messageModal");
    modal.dataset.profile = profile;
    modal.dataset.nickname = user.nickname || "";
    modal.dataset.platform = platformFromProfile(profile);
    $("#messageModalMeta").textContent = `${user.nickname || "未知昵称"} · ${platformFromProfile(profile)}`;
    $("#messageContent").value = buildMessageDraft(user);
    $("#messageContext").textContent = contextText;
    modal.hidden = false;
    $("#messageContent").focus();
  }

  function closeMessageModal() {
    $("#messageModal").hidden = true;
    $("#messageModal").dataset.profile = "";
  }

  async function sendDirectMessage() {
    const modal = $("#messageModal");
    const profile = modal.dataset.profile || "";
    const content = $("#messageContent").value.trim();
    const button = $("#sendMessage");
    if (!profile || !content) {
      setNotice("请填写私信内容。");
      return;
    }
    if (content.length > 500) {
      setNotice("私信内容不能超过 500 个字符。");
      return;
    }
    button.disabled = true;
    button.textContent = "正在发送...";
    setNotice("正在后台打开用户主页并发送私信...");
    try {
      const contexts = commentsForUser(profile);
      const response = await chrome.runtime.sendMessage({
        type: "SEND_DIRECT_MESSAGE",
        profile,
        nickname: modal.dataset.nickname || "",
        platform: modal.dataset.platform || "",
        content,
        context: contexts.map(({ row, post }) => ({ postTitle: post?.title || "", text: row.text || "", postUrl: row.postUrl || "" }))
      });
      if (!response?.ok) throw new Error(response?.error || "私信发送失败。");
      for (const scope of state.data.analysisScopes || []) {
        if (analysisUserRows(scope).some(item => item.profile === profile)) {
          await CommentFilterDatabase.setAnalysisUserDecision(scope.id, profile, "contacted");
        }
      }
      closeMessageModal();
      await refresh("私信已发送，并已记录到本机。");
    } catch (error) {
      setNotice(error.message || "私信发送失败。请确认已登录并允许该平台私信。");
    } finally {
      button.disabled = false;
      button.textContent = "发送私信";
    }
  }

  function rowsForCopy() {
    if (state.view === "posts") {
      return state.data.posts.filter(post => matchesQuery([post.title, post.url, postNumberLabel(post), platformName(post.platform)])).map(post => [postNumberLabel(post), post.title || "", platformName(post.platform), post.commentCount || 0, post.lastScannedAt ? new Date(post.lastScannedAt).toISOString() : "", post.url]);
    }
    if (state.view === "users") {
      return state.data.users.filter(user => matchesQuery([user.nickname, user.gender, user.profileAge, user.profileLocation, user.ipRegion, user.profile])).map(user => [user.nickname, user.gender, user.profileAge, user.profileLocation, user.ipRegion, user.profile, user.commentCount || 0]);
    }
    if (state.view === "analysis") {
      const scope = analysisScope();
      return analysisUserRows(scope).filter(user => matchesQuery([user.nickname, user.gender, user.profile, user.status, user.matchedExcludePhrase])).map(user => [
        scope?.name || "", user.nickname || "", user.gender || "未知", user.status || "pending",
        user.postCount || 0, user.commentCount || 0, user.totalLikeCount || 0, user.totalReplyCount || 0,
        user.firstCommentAt ? (user.commentTimeApproximate ? "≈" : "") + formatDate(user.firstCommentAt) : "", user.lastCommentAt ? (user.commentTimeApproximate ? "≈" : "") + formatDate(user.lastCommentAt) : "",
        user.profile || "", user.matchedExcludePhrase || ""
      ]);
    }
    if (state.view === "discover") {
      return filteredDiscoveredPosts()
        .map(post => {
          const tracked = trackedPostForDiscovery(post);
          const status = tracked ? `已获取评论（${tracked.commentCount || 0} 条）` : "未获取评论";
          return [platformName(post.platform), post.title, post.nickname, platformMetricText(post, "diggCount"), platformMetricText(post, "commentCount"), platformMetricText(post, "shareCount"), platformMetricText(post, "collectCount"), post.createTime ? formatDate(Number(post.createTime) * 1000) : post.createTimeText || "时间未知", discoveredFetchedAt(post) ? formatDate(discoveredFetchedAt(post)) : "时间未知", status, post.url, post.keywords?.join("、") || post.keyword || ""];
        });
    }
    const postById = new Map(state.data.posts.map(post => [post.id, post]));
    return state.data.comments.filter(row => {
      if (state.postId && row.postId !== state.postId) return false;
      if (state.taskId === "__unassigned__" && Array.isArray(row.filterTaskIds) && row.filterTaskIds.length) return false;
      if (state.taskId && state.taskId !== "__unassigned__" && (!Array.isArray(row.filterTaskIds) || !row.filterTaskIds.includes(state.taskId))) return false;
      return matchesQuery([row.nickname, row.text, row.ipRegion, row.profile, row.postUrl, row.rawTimeText]);
    }).map(row => {
      const post = postById.get(row.postId);
      return [postNumberLabel(post), taskLabelsForComment(row, post).join("、") || "未关联任务", row.postUrl, row.nickname, row.gender || "未知", row.dateText, row.rawTimeText, row.ipRegion, row.likeCount || 0, row.replyCount || 0, row.profile, row.text];
    });
  }

  async function copyCurrentView() {
    const header = state.view === "posts"
      ? ["帖子编号", "帖子标题", "平台", "评论数", "最近抓取", "帖子链接"]
      : state.view === "users"
        ? ["昵称", "性别", "年龄线索", "主页所在地", "评论属地", "主页链接", "评论数"]
        : state.view === "analysis"
          ? ["分析范围", "昵称", "性别", "候选状态", "帖子数", "评论数", "获赞总数", "回复总数", "首次评论 / 记录", "最近评论 / 记录", "主页链接", "排除依据"]
        : state.view === "discover"
          ? ["平台", "帖子标题", "作者", "赞（获取时）", "评论（获取时）", "转发（获取时）", "收藏（获取时）", "发布时间", "抓取时间", "评论抓取状态", "帖子链接", "搜索关键词"]
        : ["帖子编号", "任务编号", "帖子链接", "昵称", "性别", "推算日期", "原始时间", "IP属地", "评论获赞", "回复数量", "主页链接", "评论内容"];
    const text = [header, ...rowsForCopy()].map(row => row.map(value => String(value ?? "").replace(/[\t\r\n]+/g, " ")).join("\t")).join("\n");
    try {
      await navigator.clipboard.writeText(text);
      setNotice("当前列表已复制");
    } catch (error) {
      setNotice("复制失败，请检查浏览器剪贴板权限");
    }
  }

  document.querySelectorAll(".tab").forEach(tab => tab.addEventListener("click", () => {
    state.view = tab.dataset.view;
    render();
    if (state.view === "discover") refreshDiscoveryRuntime().catch(() => {});
  }));
  $("#search").addEventListener("input", event => {
    state.query = event.target.value.trim();
    render();
  });
  $("#postFilter").addEventListener("change", event => {
    state.postId = event.target.value;
    state.taskId = "";
    renderTaskFilter();
    render();
  });
  $("#taskFilter").addEventListener("change", event => {
    state.taskId = event.target.value;
    render();
  });
  $("#content").addEventListener("input", event => {
    const keyword = event.target.closest("[data-discover-keyword]");
    if (keyword) state.discoveryKeyword = keyword.value;
  });
  $("#cloudAccountButton").addEventListener("click", openCloudLoginModal);
  document.querySelectorAll(".account-tab").forEach(tab => tab.addEventListener("click", () => {
    setAccountMode(tab.dataset.accountMode);
    $("#cloudLoginNotice").textContent = "";
  }));
  $("#sendAccountCode").addEventListener("click", () => sendAccountCode());
  $("#content").addEventListener("click", event => {
    const analysisCreate = event.target.closest("[data-analysis-create]");
    if (analysisCreate) {
      analysisCreate.disabled = true;
      saveCurrentAnalysisScope()
        .catch(error => setNotice(error.message || "保存分析范围失败。"))
        .finally(() => { analysisCreate.disabled = false; });
      return;
    }

    const analysisRefresh = event.target.closest("[data-analysis-refresh]");
    if (analysisRefresh) {
      refresh("分析统计已重新计算。").catch(error => setNotice(error.message || "刷新分析统计失败。"));
      return;
    }

    const analysisDelete = event.target.closest("[data-analysis-delete]");
    if (analysisDelete) {
      deleteCurrentAnalysisScope().catch(error => setNotice(error.message || "删除分析范围失败。"));
      return;
    }

    const analysisComments = event.target.closest("[data-analysis-open-comments]");
    if (analysisComments) {
      state.view = "comments";
      state.postId = "";
      state.taskId = "";
      state.query = analysisComments.dataset.analysisOpenComments || "";
      $("#search").value = state.query;
      render();
      setNotice("已切换到该用户的全部评论。");
      return;
    }

    const discoverRun = event.target.closest("[data-discover-run]");
    if (discoverRun) {
      runDiscovery().catch(error => setNotice(error.message || "发现帖子失败。"));
      return;
    }

    const discoverSelectAll = event.target.closest("[data-discover-select-all]");
    if (discoverSelectAll) {
      const visible = filteredDiscoveredPosts();
      visible.forEach(post => state.discoverySelected.add(discoveryKey(post)));
      updateDiscoverySelectionUi();
      return;
    }

    const discoverInvert = event.target.closest("[data-discover-invert]");
    if (discoverInvert) {
      const visible = filteredDiscoveredPosts();
      visible.forEach(post => {
        const key = discoveryKey(post);
        if (state.discoverySelected.has(key)) state.discoverySelected.delete(key);
        else state.discoverySelected.add(key);
      });
      updateDiscoverySelectionUi();
      return;
    }

    const discoverStart = event.target.closest("[data-discover-start]");
    const discoverCreateAnalysis = event.target.closest("[data-discover-create-analysis]");
    if (discoverCreateAnalysis) {
      discoverCreateAnalysis.disabled = true;
      createAnalysisFromDiscovery()
        .catch(error => setNotice(error.message || "创建用户分析范围失败。"))
        .finally(() => { discoverCreateAnalysis.disabled = false; });
      return;
    }

    if (discoverStart) {
      startSelectedDiscoveryScan().catch(error => setNotice(error.message || "批量抓取失败。"));
      return;
    }

    const openCommentPost = event.target.closest("[data-open-comment-post]");
    if (openCommentPost) {
      event.preventDefault();
      openCommentPost.setAttribute("aria-busy", "true");
      setNotice("正在打开帖子并定位评论区...");
      chrome.runtime.sendMessage({ type: "OPEN_POST_COMMENTS", postUrl: openCommentPost.dataset.openCommentPost })
        .then(response => {
          if (!response?.ok) throw new Error(response?.error || "无法打开评论区");
          setNotice(response.message || "帖子已打开，并正在定位评论区。");
        })
        .catch(error => setNotice(error.message || "无法打开评论区"))
        .finally(() => openCommentPost.removeAttribute("aria-busy"));
      return;
    }

    const likeCommentButton = event.target.closest("[data-like-comment]");
    if (likeCommentButton) {
      likeCommentButton.disabled = true;
      setNotice("正在打开帖子并定位评论，准备点赞...");
      chrome.runtime.sendMessage({
        type: "LIKE_COLLECTED_COMMENT",
        postUrl: likeCommentButton.dataset.likePostUrl,
        commentId: likeCommentButton.dataset.likeCommentId,
        nickname: likeCommentButton.dataset.likeNickname,
        text: likeCommentButton.dataset.likeText
      }).then(response => {
        if (!response?.ok) throw new Error(response?.error || "评论点赞失败");
        state.likedComments.add(likeCommentButton.dataset.likeComment);
        setNotice(response.alreadyLiked ? "这条评论之前已经点过赞。" : "评论已点赞，帖子页面已打开供你确认。");
        render();
      }).catch(error => {
        likeCommentButton.disabled = false;
        setNotice(error.message || "评论点赞失败");
      });
      return;
    }

    const button = event.target.closest("[data-open-post]");
    if (button) {
      state.view = "comments";
      state.postId = button.dataset.openPost;
      state.taskId = "";
      renderPostFilter();
      render();
      return;
    }

    const viewTaskButton = event.target.closest("[data-view-task]");
    if (viewTaskButton) {
      state.view = "comments";
      state.postId = viewTaskButton.dataset.taskOwner;
      state.taskId = viewTaskButton.dataset.viewTask;
      renderPostFilter();
      renderTaskFilter();
      render();
      setNotice("已切换到这条任务获取的评论。");
      return;
    }

    const taskButton = event.target.closest("[data-refresh-task]");
    if (taskButton) {
      const post = state.data.posts.find(item => item.id === taskButton.dataset.taskOwner);
      const task = post?.filterTasks?.find(item => item.id === taskButton.dataset.refreshTask);
      if (!task) { setNotice("未找到这条筛选任务，请刷新数据库后重试。"); return; }
      if (!window.confirm("将按这条任务保存的筛选条件重新抓取此帖，并替换该帖现有评论。任务停止或失败时旧数据会保留。继续吗？")) return;
      taskButton.disabled = true;
      setNotice("正在启动帖子更新...");
      refreshPost(post.url, task.filters)
        .catch(error => setNotice(error.message || "更新帖子失败"))
        .finally(() => { taskButton.disabled = false; });
      return;
    }

    const deleteTaskButton = event.target.closest("[data-delete-task]");
    if (deleteTaskButton) {
      const taskId = deleteTaskButton.dataset.deleteTask;
      if (!window.confirm("确定删除这条任务获取的评论数据吗？如果评论也被其他任务使用，只会移除本任务关联。此操作不能撤销。")) return;
      deleteTaskButton.disabled = true;
      setNotice("正在删除本次任务数据...");
      chrome.runtime.sendMessage({ type: "DELETE_TASK_DATA", postUrl: deleteTaskButton.dataset.taskPostUrl, taskId })
        .then(response => {
          if (!response?.ok) throw new Error(response?.error || "删除任务数据失败");
          if (state.taskId === taskId) state.taskId = "";
          return refresh(`已删除本次任务 ${response.deletedComments || 0} 条评论${response.unlinkedComments ? `，解除 ${response.unlinkedComments} 条关联` : ""}`);
        })
        .catch(error => setNotice(error.message || "删除任务数据失败"))
        .finally(() => { deleteTaskButton.disabled = false; });
      return;
    }

    const refreshButton = event.target.closest("[data-refresh-post]");
    if (refreshButton) {
      if (!window.confirm("将按当前插件筛选条件重新抓取此帖，并替换该帖现有评论。任务停止或失败时旧数据会保留。继续吗？")) return;
      refreshButton.disabled = true;
      setNotice("正在启动帖子更新...");
      refreshPost(refreshButton.dataset.refreshPost)
        .catch(error => setNotice(error.message || "更新帖子失败"))
        .finally(() => { refreshButton.disabled = false; });
      return;
    }

    const deleteButton = event.target.closest("[data-delete-post]");
    if (deleteButton) {
      if (!window.confirm("确定删除这个帖子的全部评论和帖子记录吗？其他帖子仍引用的用户资料会保留。此操作不能撤销。")) return;
      deleteButton.disabled = true;
      setNotice("正在删除帖子数据...");
      deletePost(deleteButton.dataset.deletePost)
        .catch(error => setNotice(error.message || "删除帖子数据失败"))
        .finally(() => { deleteButton.disabled = false; });
      return;
    }

    const messageButton = event.target.closest("[data-message-profile]");
    if (messageButton) {
      openMessageModal(messageButton.dataset.messageProfile);
    }
  });
  $("#content").addEventListener("toggle", event => {
    const details = event.target.closest("[data-task-post]");
    if (!details) return;
    if (details.open) state.openTaskPosts.add(details.dataset.taskPost);
    else state.openTaskPosts.delete(details.dataset.taskPost);
  }, true);
  $("#content").addEventListener("change", async event => {
    const analysisScopeSelect = event.target.closest("[data-analysis-scope]");
    if (analysisScopeSelect) {
      state.analysisScopeId = analysisScopeSelect.value;
      render();
      return;
    }

    const analysisStatus = event.target.closest("[data-analysis-status]");
    if (analysisStatus) {
      analysisStatus.disabled = true;
      try {
        await CommentFilterDatabase.setAnalysisUserDecision(
          analysisStatus.dataset.analysisScope,
          analysisStatus.dataset.analysisProfile,
          analysisStatus.value
        );
        await refresh("用户候选状态已更新。");
      } catch (error) {
        setNotice(error.message || "更新用户候选状态失败。");
        analysisStatus.disabled = false;
      }
      return;
    }

    const discoverCheckbox = event.target.closest("[data-discover-select]");
    if (discoverCheckbox) {
      if (discoverCheckbox.checked) state.discoverySelected.add(discoverCheckbox.dataset.discoverSelect);
      else state.discoverySelected.delete(discoverCheckbox.dataset.discoverSelect);
      updateDiscoverySelectionUi();
      return;
    }
    const discoverPlatform = event.target.closest("[data-discover-platform]");
    if (discoverPlatform) {
      state.discoveryPlatform = discoverPlatform.value;
      render();
      return;
    }
    const discoverSort = event.target.closest("[data-discover-sort]");
    if (discoverSort) {
      state.discoverySort = discoverSort.value;
      render();
      return;
    }
    const discoverStatus = event.target.closest("[data-discover-status]");
    if (discoverStatus) {
      state.discoveryStatusFilter = ["all", "pending", "done"].includes(discoverStatus.value) ? discoverStatus.value : "all";
      render();
      return;
    }
    const discoverLimit = event.target.closest("[data-discover-limit]");
    if (discoverLimit) {
      state.discoveryLimit = Math.max(1, Math.min(500, Number(discoverLimit.value) || 50));
      render();
      return;
    }
    const discoverPages = event.target.closest("[data-discover-pages]");
    if (discoverPages) {
      state.discoveryPages = Math.max(1, Math.min(100, Number(discoverPages.value) || 20));
      render();
      return;
    }
    const select = event.target.closest("[data-gender-profile]");
    if (!select) return;
    select.disabled = true;
    try {
      const response = await chrome.runtime.sendMessage({ type: "UPDATE_USER_GENDER", profile: select.dataset.genderProfile, gender: select.value });
      if (!response?.ok) throw new Error(response?.error || "更新用户性别失败。");
      await refresh("用户性别已更新，相关评论已同步。");
    } catch (error) {
      setNotice(error.message || "更新用户性别失败。");
      select.disabled = false;
    }
  });
  $("#refresh").addEventListener("click", () => refresh("数据库已刷新"));
  $("#copyView").addEventListener("click", copyCurrentView);

  $("#closeMessageModal").addEventListener("click", closeMessageModal);
  $("#cancelMessage").addEventListener("click", closeMessageModal);
  $("#sendMessage").addEventListener("click", () => sendDirectMessage());
  $("#messageModal").addEventListener("click", event => {
    if (event.target.id === "messageModal") closeMessageModal();
  });
  $("#cloudLoginForm").addEventListener("submit", loginCloud);
  $("#closeCloudLoginModal").addEventListener("click", closeCloudLoginModal);
  $("#cancelCloudLogin").addEventListener("click", closeCloudLoginModal);
  $("#cloudLogoutButton").addEventListener("click", () => logoutCloud().catch(error => {
    $("#cloudLoginNotice").textContent = error.message || "退出登录失败。";
  }));
  $("#cloudLoginModal").addEventListener("click", event => {
    if (event.target.id === "cloudLoginModal") closeCloudLoginModal();
  });
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && !$("#messageModal").hidden) closeMessageModal();
    if (event.key === "Escape" && !$("#cloudLoginModal").hidden) closeCloudLoginModal();
  });

  chrome.storage.onChanged.addListener((changes, areaName) => {
    if (areaName !== "local") return;
    if (changes.cloudSyncState) {
      state.cloudSyncState = changes.cloudSyncState.newValue || { status: "idle", lastSyncedAt: 0 };
      setCloudSyncStatus();
    }
    const scanTask = changes.scanTask?.newValue;
    const scanProgress = changes.scanProgress?.newValue;
    if (["running", "waiting-verification", "waiting-profile"].includes(scanTask?.status) && scanProgress?.message) {
      const taskLabel = Number(scanTask.taskNumber) > 0 ? `任务 #${Number(scanTask.taskNumber)}：` : "";
      setNotice(`${taskLabel}${scanProgress.message}`);
    }
    if (scanTask && !["running", "waiting-verification", "waiting-profile"].includes(scanTask.status)) {
      refresh(scanTask.message || "评论任务已结束").catch(error => setNotice(error.message || "刷新任务结果失败。"));
    }
  });

  setInterval(() => refreshDiscoveryRuntime().catch(() => {}), 1500);
  Promise.all([loadCloudAccount(), loadCloudSyncState(), refresh(), refreshDiscoveryRuntime(false)]).catch(error => setNotice(error.message || "数据库初始化失败。"));
})();
