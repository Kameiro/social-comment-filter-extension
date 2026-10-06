(() => {
  if (window.__dyCommentFilterInstalledV29) return;
  window.__dyCommentFilterInstalledV29 = true;
  window.__dyCommentFilterStopRequested = false;

  const clean = value => (value || "").replace(/\s+/g, " ").trim();
  const apiRows = new Map();
  let apiHasMore = true;
  let latestApiCursor = "";
  let latestApiRequestUrl = "";
  let lastApiResponseAt = 0;
  let latestPostStats = null;
  const timePattern = /刚刚|分钟前|小时前|今天|昨天|\d{1,3}\s*天前|\d{1,2}\s*(周|星期)前|\d{1,2}\s*(个)?月前|\d{1,2}\s*年前/;
  const containerNoisePattern = /全部评论|大家都在搜|相关搜索|倍速|智能|清屏|连播|加载中|留下你的精彩评论|TA的作品|问AI|识别画面|发送/;

  function installApiListener() {
    const api = globalThis.__commentFilterApiV1;
    if (!api?.listen || window.__dyCommentFilterApiListenerV1) return;
    window.__dyCommentFilterApiListenerV1 = true;
    api.listen(packet => {
      const isReplyRequest = /\/comment\/list\/reply(?:\/|\?|$)/i.test(packet.url || "");
      const payload = safeJson(packet.body);
      const postStats = extractPostStats(payload);
      if (postStats) latestPostStats = postStats;
      const pagination = api.extractPagination?.(payload);
      // Reply pagination has its own cursor and must never terminate the
      // primary comment scan.
      if (!isReplyRequest && pagination) {
        if (pagination.hasMore !== null) apiHasMore = pagination.hasMore;
        if (pagination.cursor) latestApiCursor = pagination.cursor;
        latestApiRequestUrl = packet.url || latestApiRequestUrl;
      }
      lastApiResponseAt = Date.now();
      for (const row of api.rowsFromPacket(packet, "douyin", location.href)) {
        apiRows.set(row.commentId ? `douyin:comment:${row.commentId}` : row._apiKey, row);
      }
    });
  }

  function extractPostStats(payload) {
    const visited = new Set();
    const number = value => {
      const parsed = Number(value);
      return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
    };
    const walk = value => {
      if (!value || typeof value !== "object" || visited.has(value)) return null;
      visited.add(value);
      if (!Array.isArray(value)) {
        const nested = value.aweme_detail || value.aweme_info;
        if (nested && typeof nested === "object") {
          const result = walk(nested);
          if (result) return result;
        }
        const stats = value.statistics || value.stats;
        const awemeId = value.aweme_id || value.awemeId || value.item_id;
        if (awemeId && stats && typeof stats === "object") {
          const diggCount = number(stats.digg_count ?? stats.diggCount ?? value.digg_count);
          const commentCount = number(stats.comment_count ?? stats.commentCount ?? value.comment_count);
          const shareCount = number(stats.share_count ?? stats.shareCount ?? value.share_count);
          const collectCount = number(stats.collect_count ?? stats.collectCount ?? value.collect_count);
          if ([diggCount, commentCount, shareCount, collectCount].some(item => item !== null)) {
            return {
              diggCount: diggCount ?? 0,
              commentCount: commentCount ?? 0,
              shareCount: shareCount ?? 0,
              collectCount: collectCount ?? 0,
              statsSource: "douyin-post-api",
              statsCapturedAt: Date.now()
            };
          }
        }
      }
      for (const child of Array.isArray(value) ? value : Object.values(value)) {
        const result = walk(child);
        if (result) return result;
      }
      return null;
    };
    return walk(payload);
  }

  function isMainCommentRequest(url) {
    return /\/comment\/list\/?(?:\?|$)/i.test(String(url || "")) &&
      !/\/comment\/list\/reply(?:\/|\?|$)/i.test(String(url || ""));
  }

  function buildApiPageUrl(template, cursor) {
    try {
      const url = new URL(template, location.href);
      url.searchParams.set("cursor", String(cursor));
      // The browser normally asks for 20 comments. Keeping this value on the
      // captured request avoids changing the site's request contract.
      if (url.searchParams.has("count")) url.searchParams.set("count", "20");
      return url.href;
    } catch (error) {
      return "";
    }
  }

  async function fetchNextApiPage(cursor, templateUrl = latestApiRequestUrl) {
    const api = globalThis.__commentFilterApiV1;
    if (!api?.replayRequest || !isMainCommentRequest(templateUrl)) return null;
    const url = buildApiPageUrl(templateUrl, cursor);
    if (!url) return null;
    const packet = await api.replayRequest(url);
    const payload = safeJson(packet.body);
    const postStats = extractPostStats(payload);
    if (postStats) latestPostStats = postStats;
    const pagination = api.extractPagination?.(payload) || {};
    if (pagination.hasMore !== null && pagination.hasMore !== undefined) apiHasMore = pagination.hasMore;
    if (pagination.cursor) latestApiCursor = pagination.cursor;
    latestApiRequestUrl = packet.url || url;
    lastApiResponseAt = Date.now();
    for (const row of api.rowsFromPacket(packet, "douyin", location.href)) {
      apiRows.set(row.commentId ? `douyin:comment:${row.commentId}` : row._apiKey, row);
    }
    return { payload, count: api.rowsFromPacket(packet, "douyin", location.href).length };
  }

  function awemeIdFromUrl(url) {
    const match = String(url || "").match(/(?:\/video\/|\/note\/|\/slides\/|modal_id=)(\d+)/i);
    return match?.[1] || "";
  }

  function buildReplyPageUrl(template, awemeId, commentId, cursor) {
    try {
      const url = new URL(template, location.href);
      url.pathname = url.pathname.replace(/\/comment\/list\/?$/i, "/comment/list/reply/");
      url.searchParams.delete("aweme_id");
      url.searchParams.set("item_id", String(awemeId));
      url.searchParams.set("comment_id", String(commentId));
      url.searchParams.set("cursor", String(cursor));
      if (url.searchParams.has("count")) url.searchParams.set("count", "20");
      return url.href;
    } catch (error) {
      return "";
    }
  }

  async function fetchReplyPages(templateUrl, awemeId, commentId, filters, seen) {
    const api = globalThis.__commentFilterApiV1;
    if (!api?.replayRequest || !commentId) return { ok: false, count: 0 };
    let cursor = "0";
    let hasMore = true;
    let pages = 0;
    let previousCursor = "";
    const maxPages = Math.max(1, Math.min(100, Number(filters.replyPageLimit || 100)));

    while (hasMore && pages < maxPages) {
      if (await shouldStop()) return { ok: true, stopped: true, count: 0 };
      const url = buildReplyPageUrl(templateUrl, awemeId, commentId, cursor);
      if (!url) return { ok: false, count: 0 };

      let packet;
      try {
        packet = await api.replayRequest(url);
      } catch (error) {
        return { ok: false, count: 0, error: error.message || "回复接口请求失败", waitingForVerification: error.verification === true };
      }

      const payload = safeJson(packet.body);
      const rows = api.rowsFromPacket(packet, "douyin", location.href);
      const pagination = api.extractPagination?.(payload) || {};
      for (const row of rows) {
        apiRows.set(row.commentId ? `douyin:comment:${row.commentId}` : row._apiKey, row);
      }
      collect(filters, seen);
      pages += 1;
      const nextCursor = String(pagination.cursor || "");
      hasMore = pagination.hasMore === true || pagination.hasMore === 1;
      if (!rows.length || !nextCursor || nextCursor === cursor || nextCursor === previousCursor) break;
      previousCursor = cursor;
      cursor = nextCursor;
    }
    return { ok: true, count: pages, stopped: false };
  }

  async function scanCommentsByApi(filters, postInfo, maxPages) {
    const api = globalThis.__commentFilterApiV1;
    if (!api?.replayRequest || !latestApiRequestUrl) return { ok: false };
    const templateUrl = latestApiRequestUrl;
    const awemeId = awemeIdFromUrl(postInfo.url || location.href) ||
      new URL(templateUrl, location.href).searchParams.get("aweme_id") || "";
    const templateAwemeId = new URL(templateUrl, location.href).searchParams.get("aweme_id") || "";
    if (!awemeId || !isMainCommentRequest(templateUrl) || (templateAwemeId && templateAwemeId !== awemeId)) return { ok: false };

    const seen = new Map();
    const verifiedProfiles = new Map();
    let cursor = "0";
    let hasMore = true;
    let previousCursor = "";
    let pageCount = 0;
    let goalReached = false;
    let stopped = false;
    let receivedAnyResponse = false;
    let replyFailed = false;
    let rawRowsRead = 0;
    let paginationComplete = false;
    let paginationKnown = false;
    let stopReason = "unknown";
    let lastCursor = "0";
    let replyGroupsCompleted = 0;
    let replyGroupsFailed = 0;

    // maxPages === 0 means "until the platform says there are no more pages".
    while (hasMore && (!maxPages || pageCount < maxPages)) {
      if (await shouldStop()) {
        stopped = true;
        stopReason = "stopped";
        break;
      }
      let page;
      try {
        page = await fetchNextApiPage(cursor, templateUrl);
      } catch (error) {
        if (error.verification) return verificationResult(postInfo, seen, pageCount, maxPages);
        return { ok: false, error: error.message || "评论接口请求失败" };
      }
      if (!page) return { ok: false };
      receivedAnyResponse = true;
      pageCount += 1;
      rawRowsRead += Number(page.count || 0);
      collect(filters, seen);

      const verification = await verifyLoadedProfiles(filters, seen, verifiedProfiles);
      if (verification.stopped) {
        stopped = true;
        stopReason = "stopped";
        break;
      }
      if (verification.goalReached) {
        goalReached = true;
        stopReason = "goal_reached";
        break;
      }

      const pagination = api.extractPagination?.(page.payload) || {};
      const nextCursor = String(pagination.cursor || latestApiCursor || "");
      const hasMoreKnown = pagination.hasMore === true || pagination.hasMore === false || pagination.hasMore === 1 || pagination.hasMore === 0;
      paginationKnown = hasMoreKnown;
      hasMore = hasMoreKnown ? (pagination.hasMore === true || pagination.hasMore === 1) : Boolean(nextCursor);
      await setProgress({
        phase: "crawling",
        message: `接口抓取中：${api.pageCounterText(pageCount, filters.scrollLimit)}，当前命中 ${seen.size} 条`,
        current: pageCount,
        total: Number(filters.scrollLimit || 0) > 0 ? maxPages : 0,
        matched: seen.size
      });
      if (hasMoreKnown && !hasMore) {
        stopReason = "no_more";
        break;
      }
      if (!nextCursor) {
        stopReason = hasMoreKnown ? "cursor_missing" : "pagination_unknown";
        break;
      }
      if (nextCursor === cursor || nextCursor === previousCursor) {
        stopReason = "cursor_stalled";
        break;
      }
      previousCursor = cursor;
      cursor = nextCursor;
      lastCursor = cursor;
    }

    if (!stopReason || stopReason === "unknown") {
      if (stopped) stopReason = "stopped";
      else if (goalReached) stopReason = "goal_reached";
      else if (maxPages > 0 && pageCount >= maxPages && hasMore) stopReason = "page_limit";
      else if (paginationKnown && !hasMore) stopReason = "no_more";
      else if (!rawRowsRead) stopReason = "pagination_unknown";
      else stopReason = "cursor_stalled";
    }
    // A repeated cursor is a safety stop, not proof that the platform exposed
    // every page. Only an explicit no-more/empty-page response is conclusive.
    paginationComplete = !stopped && !goalReached && stopReason === "no_more";

    // The primary endpoint exposes reply_comment_total. Fetching reply pages
    // here avoids opening every folded thread in the DOM.
    const replyTargets = [...seen.values()]
      .filter(row => Number(row.replyCount || 0) > 0 && row.commentId)
      .slice(0, Math.max(0, Number(filters.replyTargetLimit || 10000)));
    for (let index = 0; index < replyTargets.length; index += 1) {
      if (await shouldStop()) {
        stopped = true;
        stopReason = "stopped";
        break;
      }
      const target = replyTargets[index];
      await setProgress({
        phase: "crawling",
        message: `接口抓取中：正在读取第 ${index + 1}/${replyTargets.length} 组回复，已命中 ${seen.size} 条`,
        current: pageCount,
        total: maxPages,
        matched: seen.size
      });
      const replyResult = await fetchReplyPages(templateUrl, awemeId, target.commentId, filters, seen);
      if (replyResult.waitingForVerification) return verificationResult(postInfo, seen, pageCount, maxPages);
      if (!replyResult.ok) {
        // One failed reply group should not discard successfully fetched main
        // comments, but force the caller to use the DOM/network fallback so
        // the task is not incorrectly reported as fully complete.
        replyFailed = true;
        replyGroupsFailed += 1;
        continue;
      }
      replyGroupsCompleted += 1;
      const verification = await verifyLoadedProfiles(filters, seen, verifiedProfiles);
      if (verification.stopped) {
        stopped = true;
        stopReason = "stopped";
        break;
      }
      if (verification.goalReached) {
        goalReached = true;
        stopReason = "goal_reached";
        break;
      }
    }

    collect(filters, seen);
    const finalVerification = await verifyLoadedProfiles(filters, seen, verifiedProfiles);
    goalReached = goalReached || finalVerification.goalReached;
    stopped = stopped || finalVerification.stopped || await shouldStop();
    if (stopped) stopReason = "stopped";
    if (goalReached && stopReason === "unknown") stopReason = "goal_reached";
    return {
      // A reply-page failure must not send the scan back to the old scrolling
      // path. Main-comment pages already fetched are still valid results.
      ok: receivedAnyResponse,
      replyWarning: replyFailed ? "部分折叠回复接口未完成，已保留当前已获取内容" : "",
      rows: [...seen.values()].map(row => ({
        ...row,
        postUrl: postInfo.url,
        sourcePostUrl: postInfo.sourceUrl || "",
        postTitle: postInfo.title || ""
      })),
      postUrl: postInfo.url,
      sourceUrl: postInfo.sourceUrl || "",
      title: postInfo.title || "",
      postStats: latestPostStats,
      stopped,
      goalReached,
      pageCount,
      rawRowsRead,
      paginationComplete,
      paginationStopReason: stopReason,
      hasMoreAtEnd: paginationKnown ? hasMore : null,
      lastCursor,
      replyGroupsTotal: replyTargets.length,
      replyGroupsCompleted,
      replyGroupsFailed
    };
  }

  function verificationResult(postInfo, rows, pageCount, total) {
    const message = "平台触发了人机验证，请完成当前页面验证后继续抓取。";
    const resultRows = [...rows.values()].map(row => ({
      ...row,
      postUrl: postInfo.url,
      sourcePostUrl: postInfo.sourceUrl || "",
      postTitle: postInfo.title || ""
    }));
    setProgress({ phase: "waiting-verification", message, current: pageCount, total, matched: resultRows.length });
    return {
      ok: true,
      waitingForVerification: true,
      verificationMessage: message,
      rows: resultRows,
      postUrl: postInfo.url,
      sourceUrl: postInfo.sourceUrl || "",
      title: postInfo.title || "",
      postStats: latestPostStats,
      pageCount,
      rawRowsRead: [...rows.values()].length,
      paginationComplete: false,
      paginationStopReason: "verification",
      hasMoreAtEnd: true,
      lastCursor: String(latestApiCursor || "")
    };
  }

  async function waitForApiTemplate(timeout = 5000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      if (latestApiRequestUrl) return true;
      if (await shouldStop()) return false;
      await new Promise(resolve => setTimeout(resolve, 150));
    }
    return Boolean(latestApiRequestUrl);
  }

  installApiListener();

  function safeJson(value) {
    try { return JSON.parse(value || ""); } catch (error) { return null; }
  }

  // 抖音会把公开展示的性别图标渲染成 SVG 的 class 或 mask id；它没有可读文本，所以单独读取标记。
  function explicitGenderFromDom(element) {
    if (!element) return "";
    const selector = '[class*="woman_svg__a"], [class*="man_svg__a"], [class*="male_svg__a"], mask[id*="woman_svg__a"], [mask*="woman_svg__a"], svg path[fill], svg[fill]';
    const nodes = [element, ...element.querySelectorAll?.(selector) || []].slice(0, 80);
    for (const node of nodes) {
      const className = String(node?.getAttribute?.("class") || node?.className?.baseVal || node?.className || "");
      const id = String(node?.getAttribute?.("id") || "");
      const mask = String(node?.getAttribute?.("mask") || "");
      if (className.includes("woman_svg__a")) return "女";
      if (className.includes("man_svg__a") || className.includes("male_svg__a")) return "男";
      if (id.includes("woman_svg__a") || mask.includes("woman_svg__a")) return "女";

      // 男性图标没有稳定 class：限定图形路径、12×12 viewBox 和官方蓝色后再判断。
      const path = String(node?.getAttribute?.("d") || "");
      const fill = String(node?.getAttribute?.("fill") || node?.style?.fill || "").toLowerCase();
      const svg = node?.tagName?.toLowerCase?.() === "svg" ? node : node?.closest?.("svg");
      const viewBox = String(svg?.getAttribute?.("viewBox") || "").replace(/\s+/g, " ").trim();
      if (fill === "#168ef9" && viewBox === "0 0 12 12" && /M8\s+1\.25/.test(path) && /M5\s+10/.test(path)) return "男";
      if (viewBox === "0 0 12 12" && svg?.querySelector?.('mask[id*="woman_svg__a"]')) return "女";
    }
    return "";
  }

  function extractProfilePublicInfo() {
    const shared = globalThis.CommentFilterProfileMetadata?.extractDouyinRenderedProfileInfo?.();
    if (shared && (shared.gender !== "未知" || shared.profileAge || shared.profileLocation)) return shared;
    const text = clean(document.body?.innerText || document.body?.textContent || "");
    const profileFactsMatch = text.match(/([♂♀])\s*(\d{1,2}\s*岁)\s*([\u4e00-\u9fa5]{2,12}[·•・][\u4e00-\u9fa5]{2,20})/);
    const cityMatch =
      profileFactsMatch ||
      text.match(/(?:所在地|常驻地)[:：\s]*([\u4e00-\u9fa5]{2,12}[·•・][\u4e00-\u9fa5]{2,20})/) ||
      text.match(/(\b|[\s，。；;])([\u4e00-\u9fa5]{2,12}[·•・][\u4e00-\u9fa5]{2,20})(\b|[\s，。；;])/);
    const fallbackLocationMatch =
      text.match(/所在地[:：\s]*([\u4e00-\u9fa5A-Za-z]{2,20})/) ||
      text.match(/常驻地[:：\s]*([\u4e00-\u9fa5A-Za-z]{2,20})/) ||
      text.match(/IP属地[:：\s]*([\u4e00-\u9fa5A-Za-z]{2,20})/);
    const ageMatch =
      text.match(/(\d{1,2})\s*岁/) ||
      text.match(/([5-9]\d|0\d|1\d|2\d)\s*后/) ||
      text.match(/(19\d{2}|20\d{2})\s*年/);
    const gender = profileFactsMatch
      ? (profileFactsMatch[1] === "♂" ? "男" : "女")
      : explicitGenderFromDom(document) || "未知";
    const profileLocation = profileFactsMatch?.[3] || cityMatch?.[1] || cityMatch?.[2] || fallbackLocationMatch?.[1] || "";

    return {
      profileLocation: profileLocation.replace(/[•・]/g, "·"),
      gender,
      profileAge: profileFactsMatch?.[2] || ageMatch?.[0] || ""
    };
  }

  function getBaseDate() {
    return new Date();
  }

  function parseCommentTime(text) {
    const end = getBaseDate();
    let relative = text.match(/刚刚|(\d{1,2})\s*分钟前|(\d{1,2})\s*小时前/);
    if (relative) {
      const date = new Date(end);
      if (relative[1]) date.setMinutes(date.getMinutes() - Number(relative[1]));
      if (relative[2]) date.setHours(date.getHours() - Number(relative[2]));
      return { date, source: relative[0] };
    }

    if (/今天/.test(text)) {
      return { date: end, source: "今天" };
    }

    if (/昨天/.test(text)) {
      const date = new Date(end);
      date.setDate(date.getDate() - 1);
      return { date, source: "昨天" };
    }

    relative = text.match(/(\d{1,3})\s*天前/);
    if (relative) {
      const date = new Date(end);
      date.setDate(date.getDate() - Number(relative[1]));
      return { date, source: `${relative[1]}天前` };
    }

    relative = text.match(/(\d{1,2})\s*(周|星期)前/);
    if (relative) {
      const date = new Date(end);
      date.setDate(date.getDate() - Number(relative[1]) * 7);
      return { date, source: `${relative[1]}${relative[2]}前` };
    }

    relative = text.match(/(\d{1,2})\s*(个)?月前/);
    if (relative) {
      const date = new Date(end);
      date.setMonth(date.getMonth() - Number(relative[1]));
      return { date, source: `${relative[1]}月前` };
    }

    relative = text.match(/(\d{1,2})\s*年前/);
    if (relative) {
      const date = new Date(end);
      date.setFullYear(date.getFullYear() - Number(relative[1]));
      return { date, source: `${relative[1]}年前` };
    }

    let match = text.match(/(20\d{2})[-/年.](\d{1,2})[-/月.](\d{1,2})/);
    if (match) {
      const date = new Date(`${match[1]}-${match[2].padStart(2, "0")}-${match[3].padStart(2, "0")}T12:00:00+08:00`);
      return { date, source: `${match[1]}-${match[2]}-${match[3]}` };
    }

    match = text.match(/(^|\D)(\d{1,2})[-/.月](\d{1,2})(\D|$)/);
    if (match) {
      const date = new Date(`${end.getFullYear()}-${match[2].padStart(2, "0")}-${match[3].padStart(2, "0")}T12:00:00+08:00`);
      return { date, source: `${match[2]}-${match[3]}` };
    }

    return null;
  }

  function isScrollable(element) {
    if (!element) return false;
    if (element === document.scrollingElement || element === document.documentElement || element === document.body) return false;
    const style = getComputedStyle(element);
    const canScroll = /(auto|scroll|overlay)/.test(`${style.overflowY} ${style.overflow}`);
    return element.scrollHeight > element.clientHeight + 120 && canScroll;
  }

  function elementScore(element) {
    const rect = element.getBoundingClientRect();
    const text = clean(element.innerText || element.textContent);
    let score = 0;

    if (rect.right > window.innerWidth * 0.45) score += 30;
    if (rect.width > 240 && rect.height > 240) score += 20;
    if (/评论|回复|IP属地|展开/.test(text)) score += 40;
    if (text.length > 100) score += 10;

    return score;
  }

  function isVisibleRightPanel(element) {
    const rect = element.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return false;
    if (rect.right < window.innerWidth * 0.55) return false;
    if (rect.width < 260 || rect.height < 180) return false;
    if (rect.width > window.innerWidth * 0.75) return false;
    return true;
  }

  function isCommentPanel(element) {
    if (!isVisibleRightPanel(element)) return false;
    const text = clean(element.innerText || element.textContent);
    if (text.length < 20) return false;

    const hasCommentHeader = /评论|全部评论|条评论/.test(text);
    const hasCommentRows = /回复|IP属地/.test(text) || timePattern.test(text);
    return hasCommentHeader && hasCommentRows;
  }

  function findCommentPanels() {
    return [...document.querySelectorAll("div, section, main, aside")]
      .filter(isCommentPanel)
      .sort((a, b) => {
        const ar = a.getBoundingClientRect();
        const br = b.getBoundingClientRect();
        return br.height * br.width - ar.height * ar.width;
      });
  }

  function isCommentTabLabel(text) {
    return /^评论(?:\s*[（(]\s*\d+\s*[)）])?$/.test(clean(text));
  }

  function isCommentTabSelected(element) {
    if (!element) return false;
    const nodes = [];
    let current = element;
    for (let index = 0; current && index < 4; index += 1, current = current.parentElement) nodes.push(current);
    const isAccent = value => {
      const text = String(value || "").toLowerCase();
      if (/#(?:fe2c55|ff2c55|ff2442)/.test(text)) return true;
      const match = text.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/);
      return Boolean(match && Number(match[1]) >= 230 && Number(match[2]) < 100 && Number(match[3]) < 140);
    };
    return nodes.some(node => {
      const className = String(node.className || "");
      const style = typeof getComputedStyle === "function" ? getComputedStyle(node) : null;
      const after = typeof getComputedStyle === "function" ? getComputedStyle(node, "::after") : null;
      const borderBottom = `${style?.borderBottom || ""} ${after?.borderBottom || ""}`;
      return Boolean(node.getAttribute?.("aria-selected") === "true" ||
        node.getAttribute?.("aria-current") === "true" ||
        node.getAttribute?.("data-active") === "true" ||
        /(^|[-_\s])(active|selected|current)([-_\s]|$)/i.test(className) ||
        isAccent(style?.color) || isAccent(style?.borderBottomColor) || isAccent(after?.backgroundColor) ||
        isAccent(after?.borderBottomColor) || /\b[2-9]\d?px\s+solid\b/i.test(borderBottom) && isAccent(borderBottom));
    });
  }

  function findCommentTab() {
    const selectors = [
      "button, [role='tab'], [role='button'], a, div, span",
      "[class*='tab'], [class*='Tab']"
    ];
    const candidates = [...new Set(selectors.flatMap(selector => [...document.querySelectorAll(selector)]))]
      .filter(element => {
        const rect = element.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0 && rect.width < 260 && rect.height < 100 &&
          isCommentTabLabel(element.innerText || element.textContent || element.getAttribute("aria-label") || "");
      })
      .sort((a, b) => {
        const score = element => {
          const role = element.getAttribute("role");
          return (role === "tab" ? 30 : 0) + (element.tagName === "BUTTON" ? 20 : 0) +
            (isCommentTabSelected(element) ? 10 : 0);
        };
        return score(b) - score(a) || a.getBoundingClientRect().width - b.getBoundingClientRect().width;
      });
    return candidates[0] || null;
  }

  function findScrollTargets() {
    const panels = findCommentPanels();
    const elements = [];
    const mainCommentScrollers = [...document.querySelectorAll("div.comment-mainContent, div[class*='comment-mainContent']")]
      .filter(element => {
        const rect = element.getBoundingClientRect();
        return rect.width > 200 && rect.height > 180 && element.scrollHeight > element.clientHeight + 20;
      });

    elements.push(...mainCommentScrollers);

    for (const panel of panels) {
      if (isScrollable(panel)) elements.push(panel);
      for (const element of panel.querySelectorAll("div, section, main, aside")) {
        if (isScrollable(element)) elements.push(element);
      }
    }

    return [...new Set(elements)]
      .sort((a, b) => elementScore(b) - elementScore(a))
      .slice(0, 8);
  }

  function isCommentAreaOpen() {
    const visibleCommentContainers = [...document.querySelectorAll("div.comment-mainContent, div[class*='comment-mainContent']")]
      .filter(element => {
        const rect = element.getBoundingClientRect();
        return rect.width > 200 && rect.height > 120;
      });
    if (visibleCommentContainers.length) return true;
    const commentTab = findCommentTab();
    if (commentTab && !isCommentTabSelected(commentTab)) return false;
    return findCommentPanels().length > 0;
  }

  function describeElement(element) {
    if (!element) return null;
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    return {
      tag: element.tagName.toLowerCase(),
      id: element.id || "",
      cls: String(element.className || "").slice(0, 180),
      role: element.getAttribute("role") || "",
      aria: element.getAttribute("aria-label") || "",
      x: Math.round(rect.x),
      y: Math.round(rect.y),
      w: Math.round(rect.width),
      h: Math.round(rect.height),
      scrollTop: Math.round(element.scrollTop),
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
      overflowY: style.overflowY,
      scrollable: element.scrollHeight > element.clientHeight + 20,
      sample: clean(element.innerText || element.textContent).slice(0, 260)
    };
  }

  function diagnoseContainer() {
    const panels = findCommentPanels().map(describeElement);
    const targets = findScrollTargets().map(describeElement);
    const userLinks = [...document.querySelectorAll('a[href*="/user/"], a[href*="/profile/"]')]
      .map(link => ({
        href: link.href || link.getAttribute("href") || "",
        text: clean(`${link.innerText || link.textContent || ""} ${link.getAttribute("aria-label") || ""} ${link.getAttribute("title") || ""}`).slice(0, 80)
      }))
      .filter(link => link.href)
      .slice(0, 30);
    const points = [
      [window.innerWidth * 0.68, window.innerHeight * 0.35],
      [window.innerWidth * 0.76, window.innerHeight * 0.50],
      [window.innerWidth * 0.82, window.innerHeight * 0.68],
      [window.innerWidth * 0.88, window.innerHeight * 0.78]
    ];

    const chains = points.map(([x, y]) => {
      let element = document.elementFromPoint(x, y);
      const chain = [];
      for (let index = 0; element && index < 10; index += 1) {
        chain.push(describeElement(element));
        element = element.parentElement;
      }
      return {
        point: [Math.round(x), Math.round(y)],
        chain
      };
    });

    return {
      url: location.href,
      viewport: [window.innerWidth, window.innerHeight],
      panelCount: panels.length,
      targetCount: targets.length,
      userLinkCount: userLinks.length,
      userLinks,
      panels,
      targets,
      chains
    };
  }

  function pressShortcut(key, code) {
    const target = document.body;
    const options = {
      key,
      code,
      bubbles: true,
      cancelable: true
    };

    window.dispatchEvent(new KeyboardEvent("keydown", options));
    document.dispatchEvent(new KeyboardEvent("keydown", options));
    target.dispatchEvent(new KeyboardEvent("keydown", options));
    target.dispatchEvent(new KeyboardEvent("keyup", options));
    document.dispatchEvent(new KeyboardEvent("keyup", options));
    window.dispatchEvent(new KeyboardEvent("keyup", options));
  }

  function clickElement(element) {
    const rect = element.getBoundingClientRect();
    const options = {
      bubbles: true,
      cancelable: true,
      clientX: rect.left + rect.width / 2,
      clientY: rect.top + rect.height / 2,
      view: window
    };

    element.dispatchEvent(new MouseEvent("mouseover", options));
    element.dispatchEvent(new MouseEvent("mousedown", options));
    element.dispatchEvent(new MouseEvent("mouseup", options));
    element.dispatchEvent(new MouseEvent("click", options));
    element.click?.();
  }

  function normalizedCommentText(value) {
    return clean(value).replace(/[\u200b\u200c\u200d]/g, "");
  }

  function commentActionRoots(target = {}) {
    const expectedText = normalizedCommentText(target.text);
    const expectedNickname = normalizedCommentText(target.nickname);
    const expectedId = clean(target.commentId || target.cid);
    const candidates = [...document.querySelectorAll(
      "div.comment-mainContent, div[class*='comment-mainContent'], div[class*='comment-item'], [data-e2e*='comment'], [data-comment-id], [data-cid]"
    )].filter(element => {
      const text = normalizedCommentText(element.innerText || element.textContent);
      if (!text) return false;
      const id = clean(element.getAttribute("data-comment-id") || element.getAttribute("data-cid") || element.id).replace(/^comment-/, "");
      if (expectedId && id === expectedId) return true;
      if (expectedText && !text.includes(expectedText)) return false;
      return !expectedNickname || text.includes(expectedNickname);
    });
    return candidates.sort((left, right) => {
      const leftText = normalizedCommentText(left.innerText || left.textContent);
      const rightText = normalizedCommentText(right.innerText || right.textContent);
      const leftScore = (expectedText && leftText === expectedText ? 50 : 0) + (expectedId && clean(left.id).includes(expectedId) ? 40 : 0) - leftText.length / 10000;
      const rightScore = (expectedText && rightText === expectedText ? 50 : 0) + (expectedId && clean(right.id).includes(expectedId) ? 40 : 0) - rightText.length / 10000;
      return rightScore - leftScore;
    });
  }

  function commentLikeButton(root) {
    if (!root) return null;
    const selector = "button, [role='button'], a, [class*='like'], [class*='Like'], [class*='digg'], [class*='Digg']";
    const candidates = [...root.querySelectorAll(selector)].filter(element => {
      const rect = element.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0 && rect.width < 180 && rect.height < 100;
    });
    const isRed = element => {
      const style = getComputedStyle(element);
      const source = `${element.outerHTML || ""} ${style.color || ""} ${style.fill || ""}`.toLowerCase();
      return /#fe2c55|#ff2c55|#ff2442|rgb\(\s*254\s*,\s*44\s*,\s*85\s*\)|rgb\(\s*255\s*,\s*44\s*,\s*85\s*\)/.test(source);
    };
    const isLiked = element => {
      const label = clean(`${element.getAttribute("aria-label") || ""} ${element.getAttribute("title") || ""} ${element.innerText || element.textContent || ""}`);
      const className = String(element.className || "");
      return element.getAttribute("aria-pressed") === "true" || /(?:liked|active|selected)/i.test(className) || /已赞|取消赞|取消点赞/.test(label) || isRed(element);
    };
    const ranked = candidates.map(element => {
      const label = clean(`${element.getAttribute("aria-label") || ""} ${element.getAttribute("title") || ""} ${element.innerText || element.textContent || ""}`);
      const className = String(element.className || "");
      const source = String(element.outerHTML || "");
      const score = (/(?:点赞|赞|like|digg)/i.test(label) ? 80 : 0) + (/(?:like|digg)/i.test(className) ? 50 : 0) + (/(?:点赞|赞|like|digg)/i.test(source) ? 15 : 0) + (isLiked(element) ? 5 : 0);
      return { element, score, isLiked: isLiked(element) };
    }).filter(item => item.score >= 50).sort((left, right) => right.score - left.score);
    return ranked[0] || null;
  }

  async function likeCollectedComment(target = {}) {
    const opened = await openCommentsView();
    if (!opened?.ok) return opened;
    const deadline = Date.now() + 12000;
    while (Date.now() < deadline) {
      const root = commentActionRoots(target)[0];
      if (root) {
        const action = commentLikeButton(root);
        if (!action) return { ok: false, error: "已找到评论，但没有找到它旁边的点赞按钮。" };
        if (action.isLiked) return { ok: true, alreadyLiked: true, message: "这条评论已经点过赞。" };
        clickElement(action.element);
        await new Promise(resolve => setTimeout(resolve, 700));
        const after = commentLikeButton(root);
        if (after?.isLiked) return { ok: true, liked: true, message: "评论已点赞。" };
        return { ok: true, liked: true, message: "已发送点赞操作，请以页面状态为准。" };
      }
      await new Promise(resolve => setTimeout(resolve, 400));
    }
    return { ok: false, error: "评论尚未加载到页面，请先打开评论区并加载这条评论后重试。" };
  }

  function clickCommentButton() {
    const candidates = [...document.querySelectorAll("button, [role='button'], a, div, span")]
      .filter(element => {
        const rect = element.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) return false;
        if (rect.left < window.innerWidth * 0.45) return false;
        if (rect.width > 220 || rect.height > 180) return false;

        const text = clean(`${element.getAttribute("aria-label") || ""} ${element.getAttribute("title") || ""} ${element.innerText || element.textContent || ""}`);
        return /评论/.test(text);
      })
      .sort((a, b) => {
        const ar = a.getBoundingClientRect();
        const br = b.getBoundingClientRect();
        return (ar.width * ar.height) - (br.width * br.height);
      });

    const target = candidates[0];
    if (!target) return false;
    clickElement(target.closest("button, [role='button'], a") || target);
    return true;
  }

  function pauseVideos() {
    for (const video of document.querySelectorAll("video")) {
      try {
        video.pause();
      } catch (error) {
        // Some embedded players may block direct pause; the shortcut fallback handles those.
      }
    }
  }

  async function shouldStop() {
    if (window.__dyCommentFilterStopRequested) return true;
    try {
      const values = await chrome.storage.local.get({ stopRequested: false });
      return Boolean(values.stopRequested);
    } catch (error) {
      return false;
    }
  }

  window.addEventListener("comment-filter-stop", () => {
    window.__dyCommentFilterStopRequested = true;
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes.stopRequested?.newValue) {
      window.__dyCommentFilterStopRequested = true;
    }
  });

  async function waitOrStop(ms) {
    const step = 100;
    let elapsed = 0;
    while (elapsed < ms) {
      if (await shouldStop()) return true;
      await new Promise(resolve => setTimeout(resolve, Math.min(step, ms - elapsed)));
      elapsed += step;
    }
    return shouldStop();
  }

  function isProbablyLoadingComments() {
    const text = clean(
      [...document.querySelectorAll("div.comment-mainContent, div[class*='comment-mainContent'], div[class*='comment']")]
        .slice(0, 12)
        .map(element => element.innerText || element.textContent || "")
        .join(" ")
    );
    return /加载中|正在加载|努力加载|稍等|loading/i.test(text);
  }

  async function waitForCommentArea(timeout = 20000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      if (await shouldStop()) return false;
      if (isCommentAreaOpen()) return true;
      await new Promise(resolve => setTimeout(resolve, isProbablyLoadingComments() ? 350 : 250));
    }
    return isCommentAreaOpen();
  }

  async function activateCommentTab() {
    const first = findCommentTab();
    if (!first || isCommentTabSelected(first)) return Boolean(first);

    clickElement(first);
    const deadline = Date.now() + 12000;
    while (Date.now() < deadline) {
      if (await shouldStop()) return false;
      const current = findCommentTab();
      if (current && isCommentTabSelected(current) && await waitForCommentArea(1200)) return true;
      await new Promise(resolve => setTimeout(resolve, isProbablyLoadingComments() ? 350 : 250));
    }
    return Boolean(findCommentTab() && isCommentTabSelected(findCommentTab()));
  }

  async function setProgress(progress) {
    try {
      await chrome.storage.local.set({
        scanProgress: {
          updatedAt: Date.now(),
          ...progress
        }
      });
    } catch (error) {
      // Progress is best-effort; scanning should continue if storage is temporarily unavailable.
    }
  }

  async function preparePostPage() {
    pauseVideos();

    // 抖音帖子页用 x 快捷键打开评论，直接点击“评论”标签在部分版本只会停留在相关推荐。
    if (!isCommentAreaOpen()) {
      pressShortcut("x", "KeyX");
      await waitForCommentArea(20000);
    }

    pauseVideos();

    if (!isCommentAreaOpen()) {
      throw new Error(isProbablyLoadingComments()
        ? "评论区加载超时，已停止扫描以避免把相关推荐当成评论。请检查网络后重试。"
        : "未检测到评论区，已停止扫描以避免切换视频。请确认帖子页面已获得焦点后重试。");
    }
  }

  function normalizeProfileHref(link) {
    const href = link?.href || link?.getAttribute?.("href") || "";
    if (!href || !/(\/user\/|\/profile\/)/.test(href)) return "";
    try {
      return new URL(href, location.origin).href;
    } catch (error) {
      return href;
    }
  }

  function findCommentRow(element, nickname) {
    const name = normalizeLinkNickname(nickname);
    let current = element;

    for (let depth = 0; current && depth < 12; depth += 1) {
      const text = clean(current.innerText || current.textContent);
      const normalizedText = normalizeLinkNickname(text);
      if (
        text &&
        text.length <= 1600 &&
        (!name || normalizedText.includes(name)) &&
        timePattern.test(text) &&
        /·[\u4e00-\u9fa5]{2,}/.test(text) &&
        current.querySelector?.('a[href*="/user/"], a[href*="/profile/"]')
      ) {
        return current;
      }

      if (current.matches?.("div.comment-mainContent, div[class*='comment-mainContent']")) break;
      current = current.parentElement;
    }

    return null;
  }

  function getProfileLink(element, nickname) {
    const direct = normalizeProfileHref(element.querySelector('a[href*="/user/"], a[href*="/profile/"]'));
    if (direct) return direct;

    const row = findCommentRow(element, nickname);
    if (!row) return "";

    const links = [...row.querySelectorAll('a[href*="/user/"], a[href*="/profile/"]')];
    const targetName = normalizeLinkNickname(nickname);
    const exact = links.find(link => normalizeLinkNickname(linkOwnText(link)) === targetName);
    return normalizeProfileHref(exact || links[0]);
  }

  function getDisplayedGender(element) {
    if (!element) return "";
    return explicitGenderFromDom(element);
  }

  function linkOwnText(link) {
    return clean([
      link?.innerText || link?.textContent,
      link?.getAttribute?.("aria-label"),
      link?.getAttribute?.("title"),
      link?.querySelector?.("img[alt]")?.getAttribute("alt")
    ].filter(Boolean).join(" "));
  }

  function normalizeLinkNickname(text) {
    return clean(text)
      .replace(/^@/, "")
      .replace(/的主页$/, "")
      .replace(/主页$/, "")
      .replace(/^进入/, "")
      .replace(/^用户/, "用户")
      .trim();
  }

  function entryMatchesNickname(entry, nickname) {
    const name = clean(nickname);
    if (!entry?.href || !name) return false;
    if (entry.nickname && entry.nickname === name) return true;
    if (entry.ownText && normalizeLinkNickname(entry.ownText) === name) return true;
    return Boolean(entry.rowText && entry.rowText.includes(name));
  }

  function profileEntriesForContainer(container) {
    const panel = findCommentPanels().find(candidate => candidate.contains(container));
    const root = panel || container;
    const links = [...root.querySelectorAll('a[href*="/user/"], a[href*="/profile/"]')]
      .map((link, index) => {
        const rect = link.getBoundingClientRect();
        const row = link.closest("div, li, article") || link;
        const ownText = linkOwnText(link);
        const rowText = clean(row.innerText || row.textContent);
        return {
          href: normalizeProfileHref(link),
          gender: getDisplayedGender(row),
          ownText,
          rowText,
          nickname: normalizeLinkNickname(ownText),
          index,
          top: rect.height || rect.width ? rect.top : Number.MAX_SAFE_INTEGER,
          left: rect.height || rect.width ? rect.left : Number.MAX_SAFE_INTEGER
        };
      })
      .filter(link => link.href)
      .sort((a, b) => (a.top - b.top) || (a.left - b.left) || (a.index - b.index));

    const unique = [];
    let lastHref = "";
    for (const link of links) {
      if (link.href === lastHref) continue;
      lastHref = link.href;
      unique.push(link);
    }
    return unique;
  }

  function buildLooseProfileIndex() {
    const index = new Map();
    const containers = [...document.querySelectorAll("div.comment-mainContent, div[class*='comment-mainContent']")];
    const markerPattern = /(刚刚|\d{1,2}\s*分钟前|\d{1,2}\s*小时前|今天|昨天|\d{1,3}\s*天前|\d{1,2}\s*(?:周|星期)前|\d{1,2}\s*(?:个)?月前|\d{1,2}\s*年前)·([\u4e00-\u9fa5]{2,})/g;

    for (const container of containers) {
      const fullText = clean(container.innerText || container.textContent);
      if (!fullText) continue;

      const matches = [...fullText.matchAll(markerPattern)];
      const profiles = profileEntriesForContainer(container);
      let start = 0;
      let profileIndex = 0;

      for (const match of matches) {
        const rawBeforeMarker = fullText.slice(start, match.index);
        const commentText = stripCommentPrefix(rawBeforeMarker);
        start = match.index + match[0].length;

        if (!commentText || commentText.length < 3 || commentText.length > 1200) continue;
        if (containerNoisePattern.test(commentText)) continue;

        const nickname = getNickname(commentText);
        const commentBody = getCommentBody(commentText) || commentText;
        if (isBadParsedComment(nickname, commentBody, commentText)) continue;

        const profile = profiles[profileIndex];
        profileIndex += 1;
        if (!profile?.href) continue;

        const key = commentKey(nickname, clean(match[1]), clean(match[2]), commentBody);
        if (!index.has(key)) index.set(key, profile.href);
      }
    }

    return index;
  }

  function uniqueProfileLinks(element) {
    const links = [...element.querySelectorAll?.('a[href*="/user/"], a[href*="/profile/"]') || []]
      .map(normalizeProfileHref)
      .filter(Boolean);
    return [...new Set(links)];
  }

  function addProfileIndexEntry(index, nickname, href) {
    const name = normalizeLinkNickname(nickname);
    if (!name || !href || index.has(name)) return;
    index.set(name, href);
  }

  function buildProfileIndex() {
    const index = new Map();
    const links = [...document.querySelectorAll('a[href*="/user/"], a[href*="/profile/"]')];

    for (const link of links) {
      const text = linkOwnText(link);
      const href = normalizeProfileHref(link);
      if (!text || !href) continue;

      addProfileIndexEntry(index, text, href);
    }

    const rows = [...document.querySelectorAll("div, li, article")];
    for (const row of rows) {
      const rect = row.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) continue;

      const text = clean(row.innerText || row.textContent);
      if (!text || text.length < 8 || text.length > 700) continue;
      if (!timePattern.test(text) || !/·[\u4e00-\u9fa5]{2,}/.test(text)) continue;
      if (hasCommentChild(row)) continue;

      const rowLinks = uniqueProfileLinks(row);
      if (rowLinks.length !== 1) continue;

      const nickname = getNickname(text);
      const commentBody = getCommentBody(text) || text;
      if (isBadParsedComment(nickname, commentBody, text)) continue;
      addProfileIndexEntry(index, nickname, rowLinks[0]);
    }

    return index;
  }

  function buildGenderIndex() {
    const index = new Map();
    const links = [...document.querySelectorAll('a[href*="/user/"], a[href*="/profile/"]')];

    for (const link of links) {
      const href = normalizeProfileHref(link);
      if (!href || index.has(href)) continue;
      const gender = getDisplayedGender(link.closest("div, li, article") || link);
      if (gender) index.set(href, gender);
    }

    return index;
  }

  function getNickname(text) {
    const firstLine = text.split(/\n| {2,}/).map(clean).filter(Boolean)[0] || "";
    return firstLine
      .split(/\s+\.\.\.\s+|…/)[0]
      .replace(/\.\.\.$/, "")
      .replace(/作者赞过|作者|置顶/g, "")
      .trim();
  }

  function getCommentBody(text) {
    return clean(text)
      .replace(/^.*?\s+\.\.\.\s+/, "")
      .replace(/^.*?…/, "")
      .trim();
  }

  function isBadParsedComment(nickname, commentBody, sourceText) {
    const name = clean(nickname);
    const body = clean(commentBody);
    const source = clean(sourceText);
    if (!name || !body) return true;
    if (/^(\.\.\.|…)\s*/.test(name)) return true;
    if (body === name || body === `${name} ...` || body === `${name} …`) return true;
    if (body === "..." || body === "…") return true;
    if (source === `${name} ...` || source === `${name} …`) return true;
    return false;
  }

  function isLikelySingleComment(text) {
    if (!text || text.length < 8 || text.length > 320) return false;
    if (containerNoisePattern.test(text)) return false;
    if (!timePattern.test(text)) return false;
    if (!/·[\u4e00-\u9fa5]{2,}/.test(text)) return false;
    if ((text.match(timePattern) || []).length > 1) return false;
    return true;
  }

  function candidateCommentElements() {
    const panels = findCommentPanels();
    const roots = panels.length ? panels : [document.body];
    const elements = [];

    for (const root of roots) {
      elements.push(...root.querySelectorAll("div, li, article"));
    }

    return elements;
  }

  function hasCommentChild(element) {
    return [...element.querySelectorAll("div, li, article")].some(child => {
      if (child === element) return false;
      const childText = clean(child.innerText || child.textContent);
      return childText.length < 320 && isLikelySingleComment(childText);
    });
  }

  function isWithinDays(parsed, filters) {
    if (!parsed?.date) return false;
    const limitDays = Math.max(1, Number(filters.relativeDays || 30));
    const ageMs = getBaseDate().getTime() - parsed.date.getTime();
    return ageMs >= 0 && ageMs <= limitDays * 24 * 60 * 60 * 1000;
  }

  function splitTerms(value) {
    return clean(value)
      .split(/[\n,，、;；|｜\s]+/)
      .map(term => term.trim())
      .filter(Boolean);
  }

  function matchesFilters(commentText, regionText, filters) {
    const keywords = splitTerms(filters.keyword);
    const excludedKeywords = splitTerms(filters.excludeKeyword);
    const regions = splitTerms(filters.region);
    const hasKeywordFilter = keywords.length > 0;
    const hasRegionFilter = regions.length > 0;

    if (excludedKeywords.some(keyword => commentText.includes(keyword))) return false;
    if (!hasKeywordFilter && !hasRegionFilter) return true;

    const keywordMatched = hasKeywordFilter
      ? keywords.some(keyword => commentText.includes(keyword))
      : filters.matchMode === "all";

    const regionMatched = hasRegionFilter
      ? regions.some(region => regionText === region || regionText.includes(region))
      : filters.matchMode === "all";

    if (filters.matchMode === "any") {
      return (hasKeywordFilter && keywordMatched) || (hasRegionFilter && regionMatched);
    }

    return keywordMatched && regionMatched;
  }

  function stripCommentPrefix(text) {
    return clean(text)
      .replace(/^.*?全部评论\(\d+\)\s*/, "")
      .replace(/^大家都在搜：.*?\s+/, "")
      .replace(/^[\d.]+万?人\s+\.\.\.\s*/, "")
      .replace(/^[\d.]+万?\s+分享\s+回复(?:\s+展开\d*条回复)?\s*/, "")
      .replace(/^分享\s+回复(?:\s+展开\d*条回复)?\s*/, "")
      .replace(/^展开\d*条回复\s*/, "")
      .replace(/^作者赞过\s*/, "")
      .replace(/^作者回复过\s*/, "")
      .trim();
  }

  function commentKey(nickname, regionText, commentBody, profile = "") {
    return globalThis.CommentFilterPostUtils?.strictCommentKey?.({ nickname, profile, ipRegion: regionText, text: commentBody }, location.href) ||
      [profile || nickname || "", commentBody || ""].join("||");
  }

  function commentEngagement(text, element) {
    const value = clean(text);
    const statsText = clean(element?.querySelector("[class*='comment-item-stats-container']")?.innerText || "");
    const replyText = clean(element?.querySelector("button.comment-reply-expand-btn")?.innerText || "");
    const likes = [...value.matchAll(/(?:^|\s)(\d+(?:\.\d+)?(?:万|w)?)(?=\s*(?:赞|点赞|分享\s+回复))/gi)];
    const explicitLikes = [...value.matchAll(/(?:^|\s)赞\s*(\d+(?:\.\d+)?(?:万|w)?)(?=\s|$)/gi)];
    const shareTailLikes = [...value.matchAll(/分享\s*(\d+(?:\.\d+)?(?:万|w)?)(?=\s*(?:展开\s*\d+\s*条回复)?\s*$)/gi)];
    const replies = [...value.matchAll(/(?:展开\s*)?(\d+)\s*条回复/g)];
    const directLike = statsText.match(/^(\d+(?:\.\d+)?(?:万|w)?)(?=\s|$)/i)?.[1] || "";
    const directReply = replyText.match(/展开\s*(\d+)\s*条回复/)?.[1] || "";
    return {
      likeCount: directLike || explicitLikes.at(-1)?.[1] || shareTailLikes.at(-1)?.[1] || likes.at(-1)?.[1] || "0",
      replyCount: directReply || replies.at(-1)?.[1] || "0"
    };
  }

  function upsertComment(seen, key, row) {
    const existing = seen.get(key);
    seen.set(key, {
      ...existing,
      ...row,
      profile: row.profile || existing?.profile || "",
      possibleProfile: row.possibleProfile || existing?.possibleProfile || "",
      gender: row.gender && row.gender !== "未知" ? row.gender : existing?.gender || "未知",
      likeCount: row.likeCount && row.likeCount !== "0" ? row.likeCount : existing?.likeCount || row.likeCount || "0",
      replyCount: row.replyCount && row.replyCount !== "0" ? row.replyCount : existing?.replyCount || row.replyCount || "0"
    });
  }

  function commentIdentity(row) {
    return globalThis.CommentFilterPostUtils?.strictCommentKey?.(row, row.postUrl || location.href) ||
      (row.commentId || row.cid ? `douyin:comment:${row.commentId || row.cid}` : "") ||
      commentKey(row.nickname, row.ipRegion, row.text, row.profile || row.possibleProfile);
  }

  function collectFromTextStream(filters, seen) {
    const containers = [...document.querySelectorAll("div.comment-mainContent, div[class*='comment-mainContent']")];
    const markerPattern = /(刚刚|\d{1,2}\s*分钟前|\d{1,2}\s*小时前|今天|昨天|\d{1,3}\s*天前|\d{1,2}\s*(?:周|星期)前|\d{1,2}\s*(?:个)?月前|\d{1,2}\s*年前)·([\u4e00-\u9fa5]{2,})/g;
    const profileIndex = buildProfileIndex();
    const looseProfileIndex = buildLooseProfileIndex();
    const genderIndex = buildGenderIndex();

    for (const container of containers) {
      const fullText = clean(container.innerText || container.textContent);
      if (!fullText) continue;

      const matches = [...fullText.matchAll(markerPattern)];
      let start = 0;

      for (const match of matches) {
        const rawBeforeMarker = fullText.slice(start, match.index);
        const rawTimeText = clean(match[1]);
        const regionText = clean(match[2]);
        const commentText = stripCommentPrefix(rawBeforeMarker);
        start = match.index + match[0].length;

        if (!commentText || commentText.length < 3) continue;
        if (containerNoisePattern.test(commentText)) continue;
        if (commentText.length > 1200) continue;
        if (!matchesFilters(commentText, regionText, filters)) continue;

        const parsed = parseCommentTime(rawTimeText);
        if (!isWithinDays(parsed, filters)) continue;

        const nickname = getNickname(commentText) || "未知昵称";
        const commentBody = getCommentBody(commentText) || commentText;
        if (isBadParsedComment(nickname, commentBody, commentText)) continue;
        const profile = profileIndex.get(nickname) || profileIndex.get(normalizeLinkNickname(nickname)) || "";
        const gender = genderIndex.get(profile) || "未知";
        const key = commentKey(nickname, regionText, commentBody, profile);
        const possibleProfile = profile ? "" : looseProfileIndex.get(key) || "";
        const engagement = commentEngagement(commentText);
        upsertComment(seen, key, {
          nickname,
          gender,
          dateText: parsed?.date ? parsed.date.toISOString().slice(0, 10) : "日期未识别",
          rawTimeText,
          ipRegion: regionText,
          profile,
          possibleProfile,
          ...engagement,
          text: commentBody
        });
      }
    }
  }

  function collect(filters, seen) {
    for (const row of apiRows.values()) {
      const parsed = row.rawTimeText ? parseCommentTime(row.rawTimeText) : null;
      if (!matchesFilters(row.text, row.ipRegion, filters)) continue;
      if (parsed && !isWithinDays(parsed, filters)) continue;
      upsertComment(seen, commentIdentity(row), row);
    }

    const elements = candidateCommentElements();

    for (const element of elements) {
      const rect = element.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) continue;

      const text = clean(element.innerText || element.textContent);
      if (!isLikelySingleComment(text)) continue;
      if (hasCommentChild(element)) continue;
      const regionMatch = text.match(/·([\u4e00-\u9fa5]{2,})/);
      const regionText = regionMatch ? clean(regionMatch[1]) : "";
      if (!matchesFilters(text, regionText, filters)) continue;

      const parsed = parseCommentTime(text);
      if (!isWithinDays(parsed, filters)) continue;

      const nickname = getNickname(element.innerText || text);
      const commentBody = getCommentBody(text) || text;
      if (isBadParsedComment(nickname, commentBody, text)) continue;
      const profile = getProfileLink(element, nickname);
      const gender = getDisplayedGender(element) || "未知";
      const key = commentKey(nickname, regionText || filters.region, commentBody, profile);
      const engagement = commentEngagement(text, element);
      upsertComment(seen, key, {
        nickname,
        gender,
        dateText: parsed?.date ? parsed.date.toISOString().slice(0, 10) : "日期未识别",
        rawTimeText: parsed?.source || "未识别",
        ipRegion: regionText || filters.region,
        profile,
        possibleProfile: "",
        ...engagement,
        text: commentBody
      });
    }

    collectFromTextStream(filters, seen);
  }

  function genderMatchesFilter(gender, filter) {
    return filter === "all" || (filter === "male" && gender === "男") || (filter === "female" && gender === "女");
  }

  function uniqueConfirmedGenderCount(seen, filter) {
    return new Set(
      [...seen.values()]
        .filter(row => row.gender && row.gender !== "未知" && row.profile && genderMatchesFilter(row.gender, filter))
        .map(row => row.profile)
    ).size;
  }

  function mergeVerifiedProfiles(seen, updates, genderFilter) {
    const infoByUrl = new Map(
      (updates || [])
        .filter(update => update?.url && update?.info)
        .map(update => [update.url, update.info])
    );

    for (const [key, row] of seen.entries()) {
      const info = infoByUrl.get(row.profile);
      if (!info) continue;
      if (genderFilter !== "all" && info.gender && info.gender !== "未知" && !genderMatchesFilter(info.gender, genderFilter)) {
        seen.delete(key);
        continue;
      }
      seen.set(key, {
        ...row,
        gender: info.gender && info.gender !== "未知" ? info.gender : row.gender || "未知",
        profileAge: info.profileAge || row.profileAge || "",
        profileLocation: info.profileLocation || row.profileLocation || ""
      });
    }
  }

  async function verifyLoadedProfiles(filters, seen, verifiedProfiles) {
    const genderFilter = ["all", "male", "female"].includes(filters.genderFilter) ? filters.genderFilter : "all";
    const targetGenderCount = Math.max(0, Number(filters.targetGenderCount || filters.targetFemaleCount || 0));
    if (!filters.taskId) {
      return { goalReached: false, matchedCount: uniqueConfirmedGenderCount(seen, genderFilter), stopped: false };
    }

    // Apply prior profile checks again: the same commenter can appear after a later scroll.
    mergeVerifiedProfiles(
      seen,
      [...verifiedProfiles.entries()].map(([url, info]) => ({ url, info })),
      genderFilter
    );

    const targetsByUrl = new Map();
    for (const row of seen.values()) {
      if (!row.profile || verifiedProfiles.has(row.profile) || targetsByUrl.has(row.profile)) continue;
      targetsByUrl.set(row.profile, { url: row.profile, nickname: row.nickname || "" });
    }

    const targets = [...targetsByUrl.values()];
    for (let offset = 0; offset < targets.length; offset += 4) {
      if (await shouldStop()) return { goalReached: false, matchedCount: uniqueConfirmedGenderCount(seen, genderFilter), stopped: true };
      const batch = targets.slice(offset, offset + 4);
      await setProgress({
        phase: "verifying",
        message: `主页验证中：${offset + 1}-${offset + batch.length}/${targets.length}，已命中 ${uniqueConfirmedGenderCount(seen, genderFilter)} 位`,
        current: 0,
        total: 0,
        matched: seen.size
      });

      let response;
      try {
        response = await chrome.runtime.sendMessage({
          type: "VERIFY_PROFILES_DURING_SCAN",
          taskId: filters.taskId,
          targets: batch
        });
      } catch (error) {
        response = { ok: false, error: error.message || "后台主页验证未完成" };
      }

      if (response?.loginRequired) {
        await setProgress({
          phase: "waiting-login",
          message: response.message || "检测到未登录，已打开独立登录窗口，评论仍在继续抓取。",
          current: 0,
          total: 0,
          matched: seen.size
        });
        return { goalReached: false, matchedCount: uniqueConfirmedGenderCount(seen, genderFilter), stopped: false, waitingForLogin: true };
      }

      const receivedInfo = new Map((response?.updates || [])
        .filter(update => update?.url)
        .map(update => [update.url, update.info || {}]));
      // Avoid repeatedly reopening profiles which fail to expose public data.
      batch.forEach(target => verifiedProfiles.set(target.url, receivedInfo.get(target.url) || {}));
      mergeVerifiedProfiles(seen, [...verifiedProfiles.entries()].map(([url, info]) => ({ url, info })), genderFilter);
      const matchedCount = uniqueConfirmedGenderCount(seen, genderFilter);
      if (targetGenderCount > 0 && matchedCount >= targetGenderCount) {
        return { goalReached: true, matchedCount, stopped: false };
      }
      if (response?.stopped || await shouldStop()) {
        return { goalReached: false, matchedCount, stopped: true };
      }
    }

    return { goalReached: false, matchedCount: uniqueConfirmedGenderCount(seen, genderFilter), stopped: false };
  }

  async function scanComments(filters) {
    window.__dyCommentFilterStopRequested = false;
    // A content script can survive SPA route changes. Do not let comments
    // captured for the previous post enter a new scan.
    apiRows.clear();
    apiHasMore = true;
    latestApiCursor = "";
    lastApiResponseAt = 0;
    latestPostStats = null;
    if (!filters.backgroundTask) await chrome.storage.local.set({ stopRequested: false });
    const postInfo = globalThis.CommentFilterPostUtils?.getPostInfo(filters.postUrl || location.href) || {
      url: filters.postUrl || location.href,
      sourceUrl: "",
      title: ""
    };
    const postUrl = postInfo.url || filters.postUrl || location.href;
    const configuredLimit = Number(filters.scrollLimit || 0);
    // A zero page limit is a real unlimited scan. The API cursor and explicit
    // no-more response are the termination conditions; the cursor-stall guard
    // still protects against a broken platform response.
    const maxPages = configuredLimit > 0 ? Math.min(10000, configuredLimit) : 0;
    const api = globalThis.__commentFilterApiV1;
    if (api?.isVerificationPage?.()) return verificationResult(postInfo, new Map(), 0, maxPages);
    await setProgress({ phase: "preparing", message: "准备中：正在打开评论区并暂停视频...", current: 0, total: maxPages, matched: 0 });
    await preparePostPage();
    if (api?.isVerificationPage?.()) return verificationResult(postInfo, new Map(), 0, maxPages);
    // Prefer cursor pagination once the page has emitted one real comment
    // request. The request template carries the current browser session; the
    // main-world bridge lets the page regenerate any dynamic request fields.
    await waitForApiTemplate(5000);
    let paginationWarning = "未捕获可分页评论接口，仅处理当前已加载内容，未继续滚动。";
    if (latestApiRequestUrl) {
      const apiResult = await scanCommentsByApi(filters, postInfo, maxPages);
      if (apiResult.waitingForVerification) return apiResult;
      if (apiResult.ok) {
        await setProgress({
          phase: apiResult.stopped ? "stopped" : "done",
          message: apiResult.stopped
            ? `已停止：接口已抓取 ${apiResult.rows.length} 条`
            : apiResult.goalReached
              ? `达到目标：接口已命中目标性别用户，停止抓取。`
              : `爬取完成：接口已抓取 ${apiResult.rows.length} 条${apiResult.replyWarning ? `，${apiResult.replyWarning}` : ""}`,
          current: apiResult.pageCount || 0,
          total: maxPages,
          matched: apiResult.rows.length
        });
        return apiResult;
      }
      paginationWarning = "评论接口分页失败，仅处理当前已加载内容，未继续滚动。";
    }
    // The interface is the only pagination mechanism. If no replayable
    // request was captured, process what the page has already loaded and
    // leave the user's scroll position untouched.
    const seen = new Map();
    const verifiedProfiles = new Map();
    collect(filters, seen);
    const loadedVerification = await verifyLoadedProfiles(filters, seen, verifiedProfiles);
    const loadedStopped = loadedVerification.stopped || await shouldStop();
    const loadedGoalReached = loadedVerification.goalReached;
    await setProgress({
      phase: loadedStopped ? "stopped" : "done",
      message: loadedStopped
        ? `已停止：当前已加载内容命中 ${seen.size} 条`
        : loadedGoalReached
          ? `达到目标：已命中 ${loadedVerification.matchedCount} 位用户，未继续滚动。`
          : `爬取完成：${paginationWarning}共 ${seen.size} 条`,
      current: 0,
      total: maxPages,
      matched: seen.size
    });
    return {
      rows: [...seen.values()].map(row => ({ ...row, postUrl, sourcePostUrl: postInfo.sourceUrl || "", postTitle: postInfo.title || "" })),
      postUrl,
      sourceUrl: postInfo.sourceUrl || "",
      title: postInfo.title || "",
      postStats: latestPostStats,
      stopped: loadedStopped,
      goalReached: loadedGoalReached,
      paginationWarning
    };

  }

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === "DY_EXTRACT_PROFILE_PUBLIC_INFO_V28") {
      setTimeout(() => {
        try {
          sendResponse(extractProfilePublicInfo());
        } catch (error) {
          sendResponse({ error: error.message || "读取主页公开资料失败" });
        }
      }, 900);
      return true;
    }

    if (message?.type === "DY_LIKE_COMMENT_V1") {
      likeCollectedComment(message.target || {})
        .then(sendResponse)
        .catch(error => sendResponse({ ok: false, error: error.message || "评论点赞失败" }));
      return true;
    }

    if (message?.type === "DY_START_BACKGROUND_SCAN_V3") {
      const filters = { ...(message.filters || {}), backgroundTask: true };
      window.__dyCommentFilterStopRequested = false;
      Promise.resolve()
        .then(() => new Promise(resolve => setTimeout(resolve, Number(filters.initialDelay || 0))))
        .then(() => scanComments(filters))
        .then(result => chrome.runtime.sendMessage({ type: "COMMENT_SCAN_PAGE_DONE", taskId: message.taskId, result }))
        .catch(error => chrome.runtime.sendMessage({ type: "COMMENT_SCAN_PAGE_DONE", taskId: message.taskId, result: { error: error.message || "扫描失败" } }));
      sendResponse({ ok: true, started: true });
      return false;
    }

    if (message?.type === "DY_EXTRACT_PROFILE_PUBLIC_INFO_V27") {
      try {
        sendResponse(extractProfilePublicInfo());
      } catch (error) {
        sendResponse({ error: error.message || "读取主页公开资料失败" });
      }
      return false;
    }

    if (message?.type === "DY_DIAGNOSE_CONTAINER_V27") {
      try {
        sendResponse(diagnoseContainer());
      } catch (error) {
        sendResponse({ error: error.message || "诊断失败" });
      }
      return false;
    }

    if (message?.type === "DY_PREPARE_POST_V27") {
      preparePostPage()
        .then(() => sendResponse({ ok: true }))
        .catch(error => sendResponse({ error: error.message || "准备页面失败" }));
      return true;
    }

    if (message?.type === "DY_STOP_SCAN_V27") {
      window.__dyCommentFilterStopRequested = true;
      chrome.storage.local.set({ stopRequested: true });
      sendResponse({ ok: true });
      return false;
    }

    if (message?.type !== "DY_SCAN_COMMENTS_V27") return false;

    scanComments(message.filters)
      .then(result => sendResponse(result))
      .catch(error => sendResponse({ error: error.message || "扫描失败" }));

    return true;
  });
})();
