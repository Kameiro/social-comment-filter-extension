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
      .panel { width:252px; overflow:hidden; border:1px solid rgba(15,23,42,.10); border-radius:16px; background:rgba(255,255,255,.86); color:#1d1d1f; box-shadow:0 14px 40px rgba(15,23,42,.18),0 2px 8px rgba(15,23,42,.08); backdrop-filter:blur(22px) saturate(1.3); font:13px/1.4 -apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",sans-serif; }
      .head { display:flex; align-items:center; gap:8px; min-height:42px; padding:0 10px 0 13px; border-bottom:1px solid rgba(15,23,42,.08); cursor:grab; user-select:none; }
      .head:active { cursor:grabbing; }
      .indicator { width:8px; height:8px; border-radius:50%; background:#34c759; box-shadow:0 0 0 3px rgba(52,199,89,.14); }
      .title { flex:1; font-size:13px; font-weight:700; letter-spacing:0; }
      .collapse { width:28px; height:28px; border:0; border-radius:50%; background:rgba(15,23,42,.06); color:#3a3a3c; font:500 22px/1 -apple-system,BlinkMacSystemFont,sans-serif; cursor:pointer; }
      .collapse:hover { background:rgba(15,23,42,.12); }
      .body { padding:12px 13px 13px; }
      .state { display:-webkit-box; overflow:hidden; color:#303038; font-weight:600; line-height:1.42; -webkit-box-orient:vertical; -webkit-line-clamp:2; word-break:break-word; }
      .meta { display:inline-flex; margin-top:9px; padding:4px 8px; border-radius:999px; background:rgba(0,122,255,.10); color:#0066d6; font-size:12px; font-weight:650; }
      .stop { width:100%; min-height:34px; margin-top:11px; border:0; border-radius:9px; background:#ff453a; color:#fff; font:700 13px/1 -apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",sans-serif; cursor:pointer; }
      .stop:hover { background:#e83b32; }
      .stop:disabled { background:rgba(118,118,128,.16); color:#6e6e73; cursor:default; }
      .copy { display:none; width:100%; min-height:34px; margin-top:9px; border:0; border-radius:9px; background:#007aff; color:#fff; font:700 13px/1 -apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",sans-serif; cursor:pointer; }
      .copy:hover { background:#006fe6; }
      .copy:disabled { background:rgba(118,118,128,.16); color:#6e6e73; cursor:default; }
      .mini { display:none; min-width:126px; min-height:38px; padding:0 12px; border:1px solid rgba(15,23,42,.10); background:rgba(255,255,255,.92); color:#1d1d1f; box-shadow:0 10px 28px rgba(15,23,42,.16); backdrop-filter:blur(18px) saturate(1.25); font:700 12px/1 -apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",sans-serif; cursor:pointer; }
      .mini::before { content:""; display:inline-block; width:7px; height:7px; margin-right:7px; border-radius:50%; background:#34c759; vertical-align:1px; }
      :host(.minimized) .panel { display:none; }
      :host(.minimized) .mini { display:block; }
      :host(.side-right.minimized) .mini { border-radius:19px 0 0 19px; }
      :host(.side-left.minimized) .mini { border-radius:0 19px 19px 0; }
    </style>
    <div class="panel">
      <div class="head"><span class="indicator"></span><span class="title">评论抓取任务</span><button class="collapse" title="收起到侧边"></button></div>
      <div class="body"><div class="state"></div><div class="meta"></div><button class="stop">停止任务</button><button class="copy">复制结果</button></div>
    </div>
    <button class="mini">评论抓取中</button>
  `;
  document.documentElement.append(host);

  const panel = shadow.querySelector(".panel");
  const head = shadow.querySelector(".head");
  const state = shadow.querySelector(".state");
  const meta = shadow.querySelector(".meta");
  const stop = shadow.querySelector(".stop");
  const copy = shadow.querySelector(".copy");
  const collapse = shadow.querySelector(".collapse");
  const mini = shadow.querySelector(".mini");

  function isInvalidExtensionContext(error) {
    return /Extension context invalidated/i.test(String(error?.message || error || ""));
  }

  function latestTask(scanTask, profileTask) {
    if (scanTask?.status === "running") return scanTask;
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
    const active = task?.status === "running";
    const finished = task?.status === "completed" || task?.status === "stopped";
    if (!active && !finished) {
      host.style.display = "none";
      return;
    }

    host.style.display = "block";
    state.textContent = active ? (progress?.message || task?.message || "正在准备任务...") : (task?.message || progress?.message || "任务已结束");
    const profileTask = task?.kind === "profile";
    const page = profileTask
      ? `主页 ${Math.min((task.currentIndex || 0) + 1, task.targets?.length || 0)}/${task.targets?.length || 0}`
      : task?.totalPages > 1 ? `帖子 ${Math.min((task.currentIndex || 0) + 1, task.totalPages)}/${task.totalPages}` : "当前帖子";
    const messageCount = Number((progress?.message || task?.message || "").match(/(?:当前命中|已命中|已补充)\s*(\d+)\s*条/)?.[1] || 0);
    const liveCount = Math.max(Number(progress?.matched || 0), messageCount);
    const count = profileTask
      ? (active ? Math.max(liveCount, task?.enrichedCount ?? 0) : (task?.enrichedCount ?? 0))
      : (active ? Math.max(liveCount, task?.newRows?.length ?? 0) : (task?.newRows?.length ?? progress?.matched ?? 0));
    meta.textContent = profileTask ? `${page} · 已补充 ${count} 条` : `${page} · 已命中 ${count} 条`;
    stop.disabled = !active;
    stop.textContent = active ? "停止任务" : "任务已结束";
    copy.style.display = finished ? "block" : "none";
    copy.disabled = false;
    copy.textContent = "复制结果";
    mini.textContent = active ? `${profileTask ? "补充中" : "抓取中"} · ${count} 条` : profileTask ? "资料补充完成" : "抓取完成";
    collapse.textContent = position.side === "right" ? "›" : "‹";
    collapse.title = position.side === "right" ? "收起到右侧" : "收起到左侧";
    host.classList.toggle("minimized", minimized);
    applyPosition();
  }

  stop.addEventListener("click", async () => {
    stop.disabled = true;
    await chrome.runtime.sendMessage({ type: "STOP_SCAN_TASK" }).catch(() => {});
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
