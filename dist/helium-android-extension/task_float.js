(() => {
  if (window.__commentFilterTaskFloatInstalled) return;
  // Manifest 注入和程序化注入可能来自不同隔离上下文，不能只依赖 window 标记防重。
  document.querySelectorAll("#comment-filter-task-float-host").forEach(existingHost => existingHost.remove());
  window.__commentFilterTaskFloatInstalled = true;

  const storageKey = "commentFilterFloatPosition";
  let minimized = false;
  let position = { side: "right", y: 112 };
  const host = document.createElement("div");
  host.id = "comment-filter-task-float-host";
  host.style.cssText = "all:initial;position:fixed;z-index:2147483647;display:none;";
  const shadow = host.attachShadow({ mode: "open" });
  shadow.innerHTML = `
    <style>
      :host { all: initial; }
      .panel { width:252px; overflow:hidden; contain:layout paint; border:1px solid rgba(15,23,42,.10); border-radius:16px; background:rgba(255,255,255,.86); color:#1d1d1f; box-shadow:0 14px 40px rgba(15,23,42,.18),0 2px 8px rgba(15,23,42,.08); backdrop-filter:blur(22px) saturate(1.3); font:13px/1.4 -apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",sans-serif; }
      .head { display:flex; align-items:center; gap:8px; min-height:42px; padding:0 10px 0 13px; border-bottom:1px solid rgba(15,23,42,.08); cursor:grab; user-select:none; }
      .head:active { cursor:grabbing; }
      .indicator { width:8px; height:8px; border-radius:50%; background:#34c759; box-shadow:0 0 0 3px rgba(52,199,89,.14); }
      .title { flex:1; font-size:13px; font-weight:700; letter-spacing:0; }
      .collapse { width:28px; height:28px; border:0; border-radius:50%; background:rgba(15,23,42,.06); color:#3a3a3c; font:500 22px/1 -apple-system,BlinkMacSystemFont,sans-serif; cursor:pointer; }
      .collapse:hover { background:rgba(15,23,42,.12); }
      .body { padding:12px 13px 13px; }
      .state { display:-webkit-box; min-height:37px; overflow:hidden; color:#303038; font-weight:600; line-height:1.42; -webkit-box-orient:vertical; -webkit-line-clamp:2; word-break:break-word; }
      .meta { display:flex; align-items:center; width:max-content; min-width:142px; min-height:27px; margin-top:9px; padding:4px 8px; border-radius:999px; background:rgba(0,122,255,.10); color:#0066d6; font-size:12px; font-weight:650; }
      .context, .target { margin-top:7px; color:#60636b; font-size:11px; line-height:1.4; overflow-wrap:anywhere; }
      .source { margin-top:5px; overflow:hidden; color:#60636b; font-size:11px; white-space:nowrap; text-overflow:ellipsis; }
      .target { color:#303038; }
      .task-id { margin-top:5px; color:#747782; font-size:10px; overflow-wrap:anywhere; }
      .stop, .resume { width:100%; min-height:34px; margin-top:11px; border:0; border-radius:9px; background:#ff453a; color:#fff; font:700 13px/1 -apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",sans-serif; cursor:pointer; }
      .stop:hover { background:#e83b32; }
      .resume { display:none; background:#007aff; }
      .resume:hover { background:#006fe6; }
      .stop:disabled, .resume:disabled { background:rgba(118,118,128,.16); color:#6e6e73; cursor:default; }
      .copy { display:none; width:100%; min-height:34px; margin-top:9px; border:0; border-radius:9px; background:#007aff; color:#fff; font:700 13px/1 -apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",sans-serif; cursor:pointer; }
      .copy:hover { background:#006fe6; }
      .copy:disabled { background:rgba(118,118,128,.16); color:#6e6e73; cursor:default; }
      .open-database { display:none; width:100%; min-height:34px; margin-top:9px; border:0; border-radius:9px; background:#007aff; color:#fff; font:700 13px/1 -apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",sans-serif; cursor:pointer; }
      .open-database:hover { background:#006fe6; }
      .open-database:disabled { background:rgba(118,118,128,.16); color:#6e6e73; cursor:default; }
      .mini { display:none; width:154px; min-height:38px; padding:0 12px; border:1px solid rgba(15,23,42,.10); background:rgba(255,255,255,.92); color:#1d1d1f; box-shadow:0 10px 28px rgba(15,23,42,.16); backdrop-filter:blur(18px) saturate(1.25); font:700 12px/1 -apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",sans-serif; cursor:pointer; }
      .mini::before { content:""; display:inline-block; width:7px; height:7px; margin-right:7px; border-radius:50%; background:#34c759; vertical-align:1px; }
      :host(.minimized) .panel { display:none; }
      :host(.minimized) .mini { display:block; }
      :host(.side-right.minimized) .mini { border-radius:19px 0 0 19px; }
      :host(.side-left.minimized) .mini { border-radius:0 19px 19px 0; }
    </style>
    <div class="panel">
      <div class="head"><span class="indicator"></span><span class="title">评论抓取任务</span><button class="collapse" title="收起到侧边"></button></div>
      <div class="body"><div class="state"></div><div class="meta"></div><div class="context"></div><div class="source"></div><div class="target"></div><div class="task-id"></div><button class="resume">验证完成，继续抓取</button><button class="stop">停止任务</button><button class="open-database">打开评论数据库</button><button class="copy">复制结果</button></div>
    </div>
    <button class="mini">评论抓取中</button>
  `;
  document.documentElement.append(host);

  const panel = shadow.querySelector(".panel");
  const head = shadow.querySelector(".head");
  const state = shadow.querySelector(".state");
  const meta = shadow.querySelector(".meta");
  const title = shadow.querySelector(".title");
  const context = shadow.querySelector(".context");
  const source = shadow.querySelector(".source");
  const target = shadow.querySelector(".target");
  const taskId = shadow.querySelector(".task-id");
  const resume = shadow.querySelector(".resume");
  const stop = shadow.querySelector(".stop");
  const openDatabase = shadow.querySelector(".open-database");
  const copy = shadow.querySelector(".copy");
  const collapse = shadow.querySelector(".collapse");
  const mini = shadow.querySelector(".mini");
  let stopping = false;

  function isInvalidExtensionContext(error) {
    return /Extension context invalidated/i.test(String(error?.message || error || ""));
  }

  function latestTask(scanTask, profileTask) {
    if (scanTask?.status === "running" || scanTask?.status === "waiting-verification") return scanTask;
    if (profileTask?.status === "running") return profileTask;
    if (!scanTask) return profileTask;
    if (!profileTask) return scanTask;
    return (profileTask.completedAt || 0) > (scanTask.completedAt || 0) ? profileTask : scanTask;
  }

  function applyPosition() {
    host.classList.toggle("side-left", position.side === "left");
    host.classList.toggle("side-right", position.side === "right");
    const edge = minimized ? "0" : "14px";
    host.style.left = position.side === "left" ? edge : "auto";
    host.style.right = position.side === "right" ? edge : "auto";
    host.style.top = `${Math.max(12, Math.min(window.innerHeight - 50, position.y || 112))}px`;
  }

  async function loadPosition() {
    try {
      const stored = await chrome.storage.local.get({ [storageKey]: position });
      position = { ...position, ...(stored[storageKey] || {}) };
      applyPosition();
    } catch (error) {
      if (!isInvalidExtensionContext(error)) throw error;
      host.remove();
    }
  }

  function render(task, progress) {
    const active = task?.status === "running" || task?.status === "waiting-verification" || task?.status === "waiting-profile";
    const waitingVerification = task?.status === "waiting-verification";
    const finished = task?.status === "completed" || task?.status === "stopped" || task?.status === "profile-incomplete";
    const profileTask = task?.kind === "profile";
    if ((!active && !finished) || (profileTask && !active)) {
      host.style.display = "none";
      return;
    }

    host.style.display = "block";
    title.textContent = profileTask ? "主页资料补充" : "评论抓取任务";
    state.textContent = waitingVerification
      ? (task?.message || "平台触发了人机验证，请完成当前页面验证后继续抓取。")
      : active ? (profileTask ? task?.message : progress?.message || task?.message || "正在准备任务...") : (task?.message || progress?.message || "任务已结束");
    const page = profileTask
      ? `主页 ${Math.min((task.currentIndex || 0) + 1, task.targets?.length || 0)}/${task.targets?.length || 0}`
      : task?.totalPages > 1 ? `帖子 ${Math.min((task.currentIndex || 0) + 1, task.totalPages)}/${task.totalPages}` : "当前帖子";
    const messageCount = Number((progress?.message || task?.message || "").match(/(?:当前命中|已命中|已补充)\s*(\d+)\s*条/)?.[1] || 0);
    const liveCount = Math.max(Number(progress?.matched || 0), messageCount);
    const count = profileTask
      ? (active ? Math.max(liveCount, task?.enrichedCount ?? 0) : (task?.enrichedCount ?? 0))
      : (active ? Math.max(liveCount, task?.newRows?.length ?? 0) : (task?.newRows?.length ?? progress?.matched ?? 0));
    meta.textContent = profileTask ? `${page} · 已补充 ${count} 条` : `${page} · 已命中 ${count} 条`;
    context.textContent = profileTask
      ? `全局后台任务 · ${task.automatic === true ? "扫描后自动启动" : task.automatic === false ? "手动启动" : "历史任务"} · ${task.sourcePostCount ? `来自 ${task.sourcePostCount} 个帖子` : "全部历史评论"}`
      : `正在处理 ${task.totalPages || 1} 个帖子`;
    source.textContent = profileTask && task.sourcePostUrl ? `${task.sourcePostCount > 1 ? "首个来源帖" : "来源帖"}：${task.sourcePostUrl}` : "";
    source.title = task.sourcePostUrl || "";
    const currentTarget = profileTask ? task.targets?.[task.currentIndex] : null;
    target.textContent = profileTask
      ? (currentTarget ? `当前账户：${currentTarget.nickname || currentTarget.url}` : "正在完成资料任务")
      : (task.urls?.[task.currentIndex] || "");
    target.title = currentTarget?.url || (task.urls?.[task.currentIndex] || "");
    taskId.textContent = task?.taskNumber ? `任务 #${task.taskNumber}` : "历史任务";
    taskId.title = task.id || "";
    if (!active) stopping = false;
    stop.disabled = !active || stopping;
    stop.textContent = !active ? "任务已结束" : stopping ? "正在停止..." : "停止任务";
    resume.style.display = waitingVerification ? "block" : "none";
    resume.disabled = false;
    openDatabase.style.display = finished ? "block" : "none";
    openDatabase.disabled = false;
    copy.style.display = finished ? "block" : "none";
    copy.disabled = false;
    copy.textContent = "复制结果";
    const taskLabel = Number(task?.taskNumber) > 0 ? `任务 #${task.taskNumber} · ` : "";
    mini.textContent = waitingVerification
      ? `${taskLabel}等待验证`
      : active
      ? (profileTask ? `${taskLabel}资料补充 ${Math.min((task.currentIndex || 0) + 1, task.targets?.length || 0)}/${task.targets?.length || 0}` : `${taskLabel}抓取中 ${count} 条`)
      : task?.status === "profile-incomplete" ? `${taskLabel}主页资料未完成` : `${taskLabel}抓取完成`;
    collapse.textContent = position.side === "right" ? "›" : "‹";
    collapse.title = position.side === "right" ? "收起到右侧" : "收起到左侧";
    host.classList.toggle("minimized", minimized);
    applyPosition();
  }

  resume.addEventListener("click", async () => {
    resume.disabled = true;
    state.textContent = "正在检查验证状态并继续...";
    try {
      const result = await chrome.runtime.sendMessage({ type: "RESUME_SCAN_AFTER_VERIFICATION" });
      if (!result?.ok) throw new Error(result?.error || "验证后继续失败");
      state.textContent = result.message || "已继续当前评论任务。";
    } catch (error) {
      resume.disabled = false;
      state.textContent = `继续失败：${error.message || error}`;
    }
  });

  stop.addEventListener("click", async () => {
    if (stopping) return;
    stopping = true;
    stop.disabled = true;
    stop.textContent = "正在停止...";
    try {
      // A page-local storage write is the fastest stop path on Android extension browsers.
      await chrome.storage.local.set({ stopRequested: true });
      const result = await chrome.runtime.sendMessage({ type: "STOP_SCAN_TASK" });
      if (!result?.ok) throw new Error(result?.error || "停止请求未被后台确认");
      state.textContent = result.message || "正在停止...";
    } catch (error) {
      stopping = false;
      stop.disabled = false;
      stop.textContent = "重试停止";
      state.textContent = isInvalidExtensionContext(error)
        ? "扩展已更新，请刷新此网页后重试停止。"
        : `停止失败：${error.message || error}`;
    }
  });
  openDatabase.addEventListener("click", async () => {
    openDatabase.disabled = true;
    openDatabase.textContent = "正在打开...";
    try {
      const response = await chrome.runtime.sendMessage({ type: "OPEN_COMMENT_DATABASE" });
      if (!response?.ok) throw new Error(response?.error || "无法打开评论数据库");
    } catch (error) {
      openDatabase.textContent = "打开失败";
    }
    setTimeout(() => {
      openDatabase.disabled = false;
      openDatabase.textContent = "打开评论数据库";
    }, 1500);
  });
  copy.addEventListener("click", async () => {
    copy.disabled = true;
    try {
      const { output = "" } = await chrome.storage.local.get({ output: "" });
      if (!output.trim()) throw new Error("没有可复制的结果");
      try {
        await navigator.clipboard.writeText(output);
      } catch (error) {
        const textarea = document.createElement("textarea");
        textarea.value = output;
        textarea.style.cssText = "position:fixed;left:-9999px;top:0;";
        document.documentElement.append(textarea);
        textarea.select();
        const copied = document.execCommand("copy");
        textarea.remove();
        if (!copied) throw error;
      }
      copy.textContent = "已复制";
    } catch (error) {
      copy.textContent = "复制失败";
    }
    setTimeout(() => {
      copy.disabled = false;
      copy.textContent = "复制结果";
    }, 1500);
  });
  collapse.addEventListener("click", event => {
    event.stopPropagation();
    minimized = true;
    host.classList.add("minimized");
    applyPosition();
  });
  let ignoreMiniClick = false;
  mini.addEventListener("click", () => {
    if (ignoreMiniClick) {
      ignoreMiniClick = false;
      return;
    }
    minimized = false;
    host.classList.remove("minimized");
    applyPosition();
  });

  let dragging = false;
  let startX = 0;
  let startY = 0;
  let originTop = 0;
  head.addEventListener("pointerdown", event => {
    if (event.target.closest("button")) return;
    dragging = true;
    startX = event.clientX;
    startY = event.clientY;
    originTop = host.getBoundingClientRect().top;
    head.setPointerCapture(event.pointerId);
    event.preventDefault();
  });
  head.addEventListener("pointermove", event => {
    if (!dragging) return;
    host.style.top = `${Math.max(12, Math.min(window.innerHeight - 50, originTop + event.clientY - startY))}px`;
    host.style.left = `${Math.max(8, Math.min(window.innerWidth - 40, host.getBoundingClientRect().left + event.clientX - startX))}px`;
    host.style.right = "auto";
    startX = event.clientX;
    startY = event.clientY;
    originTop = host.getBoundingClientRect().top;
  });
  head.addEventListener("pointerup", async event => {
    if (!dragging) return;
    dragging = false;
    const rect = host.getBoundingClientRect();
    position = { side: rect.left + rect.width / 2 < window.innerWidth / 2 ? "left" : "right", y: rect.top };
    applyPosition();
    collapse.textContent = position.side === "right" ? "›" : "‹";
    await chrome.storage.local.set({ [storageKey]: position });
    try { head.releasePointerCapture(event.pointerId); } catch (error) {}
  });

  let miniDragging = false;
  let miniMoved = false;
  let miniStartX = 0;
  let miniStartY = 0;
  let miniOriginTop = 0;
  mini.addEventListener("pointerdown", event => {
    miniDragging = true;
    miniMoved = false;
    miniStartX = event.clientX;
    miniStartY = event.clientY;
    miniOriginTop = host.getBoundingClientRect().top;
    mini.setPointerCapture(event.pointerId);
  });
  mini.addEventListener("pointermove", event => {
    if (!miniDragging) return;
    const offsetY = event.clientY - miniStartY;
    if (Math.abs(offsetY) > 3 || Math.abs(event.clientX - miniStartX) > 3) miniMoved = true;
    if (!miniMoved) return;
    position = {
      side: event.clientX < window.innerWidth / 2 ? "left" : "right",
      y: Math.max(12, Math.min(window.innerHeight - 50, miniOriginTop + offsetY))
    };
    applyPosition();
  });
  mini.addEventListener("pointerup", async event => {
    if (!miniDragging) return;
    miniDragging = false;
    if (miniMoved) {
      ignoreMiniClick = true;
      await chrome.storage.local.set({ [storageKey]: position }).catch(() => {});
    }
    try { mini.releasePointerCapture(event.pointerId); } catch (error) {}
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes.stopRequested?.newValue) {
      window.dispatchEvent(new CustomEvent("comment-filter-stop"));
    }
    if (area !== "local" || (!changes.scanTask && !changes.profileTask && !changes.scanProgress)) return;
    chrome.storage.local.get({ scanTask: null, profileTask: null, scanProgress: null }).then(values => {
      render(latestTask(values.scanTask, values.profileTask), values.scanProgress);
    }).catch(error => {
      if (isInvalidExtensionContext(error)) host.remove();
    });
  });
  window.addEventListener("resize", applyPosition);
  loadPosition().catch(() => {});
  chrome.storage.local.get({ scanTask: null, profileTask: null, scanProgress: null }).then(values => {
    render(latestTask(values.scanTask, values.profileTask), values.scanProgress);
  }).catch(error => {
    if (isInvalidExtensionContext(error)) host.remove();
  });
})();
