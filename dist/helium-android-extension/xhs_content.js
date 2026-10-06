(() => {
  if (window.__xhsCommentFilterInstalledV2) return;
  window.__xhsCommentFilterInstalledV2 = true;
  window.__xhsCommentFilterStopRequested = false;

  const clean = value => String(value || "").replace(/\s+/g, " ").trim();
  const apiRows = new Map();
  let apiHasMore = true;
  let latestApiCursor = "";
  let latestApiRequestUrl = "";
  let latestApiReplyRequestUrl = "";
  let lastApiResponseAt = 0;
  let latestPostStats = null;
  const timePattern = /刚刚|分钟前|小时前|今天|昨天|\d{1,3}\s*天前|\d{1,2}\s*(周|星期)前|\d{1,2}\s*(个)?月前|\d{1,2}\s*年前|(\d{4}[-/.年])?\d{1,2}[-/.月]\d{1,2}/;
  const noisePattern = /说点什么|登录|打开APP|相关推荐|赞和收藏|分享|收起|展开|查看更多|作者|小红书精选/;
  const regionPattern = /北京|天津|上海|重庆|河北|山西|辽宁|吉林|黑龙江|江苏|浙江|安徽|福建|江西|山东|河南|湖北|湖南|广东|海南|四川|贵州|云南|陕西|甘肃|青海|台湾|内蒙古|广西|西藏|宁夏|新疆|香港|澳门|美国|英国|法国|德国|日本|韩国|加拿大|澳大利亚|新加坡|马来西亚|泰国|越南|菲律宾|文莱|西班牙|意大利|俄罗斯/;
  const metaTailPattern = /(刚刚|今天|昨天|\d{1,2}\s*分钟前|\d{1,2}\s*小时前|\d{1,3}\s*天前|\d{1,2}\s*(?:周|星期)前|\d{1,2}\s*(?:个)?月前|\d{1,2}\s*年前|(?:20\d{2}[-/.年])?\d{1,2}[-/.月]\d{1,2})\s*([^\s赞回复]*)?/;

  function installApiListener() {
    const api = globalThis.__commentFilterApiV1;
    if (!api?.listen || window.__xhsCommentFilterApiListenerV1) return;
    window.__xhsCommentFilterApiListenerV1 = true;
    api.listen(packet => {
      if (packet.platform !== "xhs") return;
      const payload = safeJson(packet.body);
      const postStats = extractPostStats(payload);
      if (postStats) latestPostStats = postStats;
      const isReplyRequest = /\/comment\/sub\/page(?:\?|$)/.test(packet.url || "");
      // Reply pagination has its own cursor and must not end the main-comment scan.
      if (isReplyRequest && payload?.success !== false) {
        latestApiReplyRequestUrl = packet.url || latestApiReplyRequestUrl;
      } else if (/\/comment\/page(?:\?|$)/.test(packet.url || "") && payload?.success !== false) {
        const pagination = api.extractPagination?.(payload);
        if (pagination) {
          if (pagination.hasMore !== null) apiHasMore = pagination.hasMore;
          if (pagination.cursor) latestApiCursor = pagination.cursor;
        }
        latestApiRequestUrl = packet.url || latestApiRequestUrl;
        lastApiResponseAt = Date.now();
      }
      for (const row of api.rowsFromPacket(packet, "xhs", location.href)) {
        apiRows.set(row.commentId ? `xhs:comment:${row.commentId}` : row._apiKey, row);
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
        const card = value.note_card || value.noteCard;
        if (card && typeof card === "object") {
          const result = walk(card);
          if (result) return result;
        }
        const noteId = value.note_id || value.noteId || value.id;
        const interact = value.interact_info || value.interactInfo || value.interaction_info || {};
        const diggCount = number(interact.liked_count ?? interact.like_count ?? value.liked_count ?? value.like_count ?? value.likes);
        const commentCount = number(interact.comment_count ?? value.comment_count ?? value.comments);
        const shareCount = number(interact.shared_count ?? interact.share_count ?? value.shared_count ?? value.share_count ?? value.shares);
        const collectCount = number(interact.collected_count ?? interact.collect_count ?? value.collected_count ?? value.collect_count ?? value.collections);
        if (noteId && [diggCount, commentCount, shareCount, collectCount].some(item => item !== null)) {
          return {
            diggCount: diggCount ?? 0,
            commentCount: commentCount ?? 0,
            shareCount: shareCount ?? 0,
            collectCount: collectCount ?? 0,
            statsSource: "xhs-feed-api",
            statsCapturedAt: Date.now()
          };
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

  installApiListener();

  function safeJson(value) {
    try { return JSON.parse(value || ""); } catch (error) { return null; }
  }

  function isMainCommentRequest(url) {
    return /\/comment\/page(?:\?|$)/i.test(String(url || "")) &&
      !/\/comment\/sub\/page(?:\?|$)/i.test(String(url || ""));
  }

  function buildApiPageUrl(template, cursor) {
    try {
      const url = new URL(template, location.href);
      url.searchParams.set("cursor", String(cursor));
      return url.href;
    } catch (error) {
      return "";
    }
  }

  function buildReplyPageUrl(template, commentId, cursor) {
    try {
      const url = new URL(template, location.href);
      url.searchParams.set("root_comment_id", String(commentId));
      url.searchParams.set("cursor", String(cursor));
      if (url.searchParams.has("num")) url.searchParams.set("num", "10");
      return url.href;
    } catch (error) {
      return "";
    }
  }

  async function waitForApiTemplate(timeout = 5000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      if (latestApiRequestUrl) return true;
      if (await shouldStop()) return false;
      await sleep(150);
    }
    return Boolean(latestApiRequestUrl);
  }

  async function scanCommentsByApi(filters, postInfo, maxPages) {
    const api = globalThis.__commentFilterApiV1;
    if (!api?.replayRequest || !isMainCommentRequest(latestApiRequestUrl)) return { ok: false };
    const templateUrl = latestApiRequestUrl;
    const templateNoteId = new URL(templateUrl, location.href).searchParams.get("note_id") || "";
    if (templateNoteId && postInfo.id && templateNoteId !== postInfo.id) return { ok: false };
    const rows = new Map();
    const profileInfoByUrl = new Map();
    let cursor = "0";
    let hasMore = true;
    let previousCursor = "";
    let pageCount = 0;
    let stopped = false;
    let goalReached = false;
    let receivedAnyResponse = false;
    let replyFailed = false;
    let rawRowsRead = 0;
    let paginationKnown = false;
    let paginationComplete = false;
    let stopReason = "unknown";
    let lastCursor = "0";

    // maxPages === 0 means "until the platform says there are no more pages".
    while (hasMore && (!maxPages || pageCount < maxPages)) {
      if (await shouldStop()) {
        stopped = true;
        break;
      }
      const url = buildApiPageUrl(templateUrl, cursor);
      if (!url) return { ok: false };
      let packet;
      try {
        packet = await api.replayRequest(url);
      } catch (error) {
        if (error.verification) return verificationResult(postInfo, rows, pageCount, maxPages);
        return { ok: false, error: error.message || "评论接口请求失败" };
      }
      const payload = safeJson(packet.body);
      const postStats = extractPostStats(payload);
      if (postStats) latestPostStats = postStats;
      const loaded = api.rowsFromPacket(packet, "xhs", location.href);
      const pagination = api.extractPagination?.(payload) || {};
      receivedAnyResponse = true;
      pageCount += 1;
      rawRowsRead += loaded.length;
      for (const row of loaded) apiRows.set(row.commentId ? `xhs:comment:${row.commentId}` : row._apiKey, row);
      collectLoadedRows(loadedComments(postInfo.url), filters, rows);

      const verification = await verifyLoadedProfiles(filters, rows, profileInfoByUrl);
      if (verification.stopped) {
        stopped = true;
        break;
      }
      if (verification.goalReached) {
        goalReached = true;
        break;
      }

      hasMore = pagination.hasMore === true || pagination.hasMore === 1;
      const nextCursor = String(pagination.cursor || latestApiCursor || "");
      const hasMoreKnown = pagination.hasMore === true || pagination.hasMore === false || pagination.hasMore === 1 || pagination.hasMore === 0;
      paginationKnown = hasMoreKnown;
      hasMore = hasMoreKnown ? (pagination.hasMore === true || pagination.hasMore === 1) : Boolean(nextCursor);
      lastCursor = nextCursor || lastCursor;
      setProgress(`接口抓取中：${api.pageCounterText(pageCount, filters.scrollLimit)}，已命中 ${rows.size} 条`, pageCount, Number(filters.scrollLimit || 0) > 0 ? maxPages : 0, rows.size, "scanning");
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
    }

    const replyCandidate = latestApiReplyRequestUrl;
    const replyCandidateNoteId = replyCandidate
      ? new URL(replyCandidate, location.href).searchParams.get("note_id") || ""
      : "";
    const replyTemplate = replyCandidate && (!replyCandidateNoteId || !postInfo.id || replyCandidateNoteId === postInfo.id)
      ? replyCandidate
      : "";
    const replyTargets = replyTemplate ? [...rows.values()]
      .filter(row => Number(row.replyCount || 0) > 0 && row.commentId)
      .slice(0, Math.max(0, Number(filters.replyTargetLimit || 10000))) : [];
    if (!replyTemplate && [...rows.values()].some(row => Number(row.replyCount || 0) > 0 && row.commentId)) {
      replyFailed = true;
    }
    for (let index = 0; index < replyTargets.length; index += 1) {
      if (await shouldStop()) {
        stopped = true;
        break;
      }
      const target = replyTargets[index];
      let replyCursor = "0";
      let replyHasMore = true;
      let previousReplyCursor = "";
      for (let replyPage = 0; replyHasMore && replyPage < 100; replyPage += 1) {
        const replyUrl = buildReplyPageUrl(replyTemplate, target.commentId, replyCursor);
        if (!replyUrl) break;
        let packet;
        try {
          packet = await api.replayRequest(replyUrl);
        } catch (error) {
          if (error.verification) return verificationResult(postInfo, rows, pageCount, maxPages);
          replyFailed = true;
          break;
        }
        const payload = safeJson(packet.body);
        const loaded = api.rowsFromPacket(packet, "xhs", location.href);
        const pagination = api.extractPagination?.(payload) || {};
        for (const row of loaded) apiRows.set(row.commentId ? `xhs:comment:${row.commentId}` : row._apiKey, row);
        collectLoadedRows(loadedComments(postInfo.url), filters, rows);
        replyHasMore = pagination.hasMore === true || pagination.hasMore === 1;
        const nextReplyCursor = String(pagination.cursor || "");
        if (!loaded.length || !replyHasMore || !nextReplyCursor || nextReplyCursor === replyCursor || nextReplyCursor === previousReplyCursor) break;
        previousReplyCursor = replyCursor;
        replyCursor = nextReplyCursor;
      }
      setProgress(`接口抓取中：已读取第 ${index + 1}/${replyTargets.length} 组回复，已命中 ${rows.size} 条`, pageCount, maxPages, rows.size, "scanning");
      const verification = await verifyLoadedProfiles(filters, rows, profileInfoByUrl);
      if (verification.stopped) {
        stopped = true;
        break;
      }
      if (verification.goalReached) {
        goalReached = true;
        break;
      }
    }

    collectLoadedRows(loadedComments(postInfo.url), filters, rows);
    const finalVerification = await verifyLoadedProfiles(filters, rows, profileInfoByUrl);
    goalReached = goalReached || finalVerification.goalReached;
    stopped = stopped || finalVerification.stopped || await shouldStop();
    if (stopped) stopReason = "stopped";
    else if (goalReached) stopReason = "goal_reached";
    else if (maxPages > 0 && pageCount >= maxPages && hasMore) stopReason = "page_limit";
    else if (paginationKnown && !hasMore && stopReason === "unknown") stopReason = "no_more";
    paginationComplete = !stopped && !goalReached && stopReason === "no_more";
    return {
      // A reply-page failure must not send the scan back to DOM expansion or
      // scrolling. Main-comment pages already fetched are still valid data.
      ok: receivedAnyResponse,
      replyWarning: replyFailed ? "部分折叠回复接口未完成，已保留当前已获取内容" : "",
      rows: [...rows.values()].map(({ _days, ...row }) => ({
        ...row,
        postUrl: postInfo.url,
        sourcePostUrl: postInfo.sourceUrl || "",
        postTitle: postInfo.title || ""
      })),
      postUrl: postInfo.url,
      sourceUrl: postInfo.sourceUrl || "",
      title: postInfo.title || "",
      postStats: latestPostStats,
      remainingReplyGroups: 0,
      stopped,
      goalReached,
      pageCount,
      rawRowsRead,
      paginationComplete,
      paginationStopReason: stopReason,
      hasMoreAtEnd: paginationKnown ? hasMore : null,
      lastCursor
    };
  }

  function verificationResult(postInfo, rows, pageCount, total) {
    const message = "平台触发了人机验证，请完成当前页面验证后继续抓取。";
    const resultRows = [...rows.values()].map(({ _days, ...row }) => ({
      ...row,
      postUrl: postInfo.url,
      sourcePostUrl: postInfo.sourceUrl || "",
      postTitle: postInfo.title || ""
    }));
    setProgress(message, pageCount, total, resultRows.length, "waiting-verification");
    return {
      ok: true,
      waitingForVerification: true,
      verificationMessage: message,
      rows: resultRows,
      postUrl: postInfo.url,
      sourceUrl: postInfo.sourceUrl || "",
      title: postInfo.title || "",
      postStats: latestPostStats,
      pageCount
    };
  }

  function getBaseDate() {
    return new Date();
  }

  function parseCommentTime(text) {
    const end = getBaseDate();
    let match = clean(text).match(/刚刚|(\d{1,2})\s*分钟前|(\d{1,2})\s*小时前/);
    if (match) {
      const date = new Date(end);
      if (match[1]) date.setMinutes(date.getMinutes() - Number(match[1]));
      if (match[2]) date.setHours(date.getHours() - Number(match[2]));
      return { date, source: match[0] };
    }

    if (/今天/.test(text)) return { date: end, source: "今天" };

    if (/昨天/.test(text)) {
      const date = new Date(end);
      date.setDate(date.getDate() - 1);
      return { date, source: "昨天" };
    }

    match = text.match(/(\d{1,3})\s*天前/);
    if (match) {
      const date = new Date(end);
      date.setDate(date.getDate() - Number(match[1]));
      return { date, source: `${match[1]}天前` };
    }

    match = text.match(/(\d{1,2})\s*(周|星期)前/);
    if (match) {
      const date = new Date(end);
      date.setDate(date.getDate() - Number(match[1]) * 7);
      return { date, source: `${match[1]}${match[2]}前` };
    }

    match = text.match(/(\d{1,2})\s*(个)?月前/);
    if (match) {
      const date = new Date(end);
      date.setMonth(date.getMonth() - Number(match[1]));
      return { date, source: `${match[1]}月前` };
    }

    match = text.match(/(\d{1,2})\s*年前/);
    if (match) {
      const date = new Date(end);
      date.setFullYear(date.getFullYear() - Number(match[1]));
      return { date, source: `${match[1]}年前` };
    }

    match = text.match(/(20\d{2})[-/年.](\d{1,2})[-/月.](\d{1,2})/);
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

  function dateText(date) {
    if (!date) return "";
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  }

  function daysBetween(date) {
    if (!date) return Infinity;
    return Math.floor((getBaseDate() - date) / 86400000);
  }

  function parseList(value) {
    return clean(value)
      .split(/[,\n，、|]+/)
      .map(item => item.trim())
      .filter(Boolean);
  }

  function matchesAny(text, terms) {
    if (!terms.length) return true;
    return terms.some(term => text.includes(term));
  }

  function matchesNone(text, terms) {
    return !terms.length || terms.every(term => !text.includes(term));
  }

  function regionFromText(text) {
    const value = clean(text);
    const afterTime = value.match(metaTailPattern);
    const regionAfterTime = afterTime ? (clean(afterTime[0]).match(regionPattern) || [])[0] : "";
    const match =
      value.match(/IP属地[:：\s]*([\u4e00-\u9fa5A-Za-z]{2,20})/) ||
      value.match(new RegExp(`(?:^|[\\s·])(${regionPattern.source})(?:\\s|赞|回复|$)`));
    return match?.[1] || regionAfterTime || "";
  }

  function parseCompactCommentText(text) {
    const value = clean(text).replace(/^共\s*\d+\s*条评论\s*/, "");
    const tail = value.match(metaTailPattern);
    if (!tail || tail.index == null) return null;

    const beforeTail = clean(value.slice(0, tail.index));
    const afterTail = clean(value.slice(tail.index));
    const parts = beforeTail.split(/\s+/).filter(Boolean);
    if (parts.length < 2) return null;

    const nickname = parts[0];
    const comment = parts.slice(1).join(" ");
    const rawTimeText = tail[1] || "";
    const region = (afterTail.match(regionPattern) || [])[0] || "";

    if (!nickname || !comment) return null;
    return { nickname, comment, rawTimeText, region };
  }

  function getUserLink(element) {
    const anchors = [...element.querySelectorAll("a[href]")];
    const userAnchor =
      anchors.find(anchor => /\/user\/profile|\/user\//.test(anchor.getAttribute("href") || "")) ||
      anchors.find(anchor => clean(anchor.innerText || anchor.textContent).length > 0);
    if (!userAnchor) return "";
    try {
      return new URL(userAnchor.getAttribute("href"), location.origin).href;
    } catch (error) {
      return userAnchor.href || "";
    }
  }

  function explicitGenderFromElement(element) {
    const icons = [...(element || document).querySelectorAll("use")];
    for (const icon of icons) {
      const reference = String(
        icon.getAttribute("href") ||
        icon.getAttribute("xlink:href") ||
        icon.getAttributeNS("http://www.w3.org/1999/xlink", "href") || ""
      ).toLowerCase();
      if (reference.endsWith("#female")) return "女";
      if (reference.endsWith("#male")) return "男";
    }
    return "未知";
  }

  function extractProfilePublicInfo() {
    return globalThis.CommentFilterProfileMetadata.extractXhsProfilePublicInfo();
  }

  function nicknameFromElement(element) {
    const selectors = [
      ".name",
      ".author",
      ".nickname",
      ".user-name",
      "[class*='name']",
      "[class*='author']",
      "a[href*='/user']"
    ];
    for (const selector of selectors) {
      const target = element.querySelector(selector);
      const value = clean(target?.innerText || target?.textContent);
      if (value && value.length <= 40 && !noisePattern.test(value)) return value;
    }

    const lines = clean(element.innerText || element.textContent).split(/\s+/).filter(Boolean);
    return lines.find(line => line.length <= 30 && !timePattern.test(line) && !noisePattern.test(line)) || "";
  }

  function commentTextFromElement(element, nickname, rawTimeText, ipRegion) {
    const selectors = [
      ".content",
      ".comment-content",
      ".note-text",
      "[class*='content']",
      "[class*='comment']"
    ];
    for (const selector of selectors) {
      const target = element.querySelector(selector);
      const value = clean(target?.innerText || target?.textContent);
      if (value && value.length > 0 && value.length < 1000 && !/^回复$/.test(value)) {
        return stripCommentMeta(value, nickname, rawTimeText, ipRegion);
      }
    }

    return stripCommentMeta(clean(element.innerText || element.textContent), nickname, rawTimeText, ipRegion);
  }

  function stripCommentMeta(text, nickname, rawTimeText, ipRegion) {
    let value = clean(text);
    [nickname, rawTimeText, ipRegion, "回复", "点赞", "分享"].filter(Boolean).forEach(part => {
      value = clean(value.replace(part, " "));
    });
    value = value.replace(/IP属地[:：\s]*[\u4e00-\u9fa5A-Za-z]{2,20}/g, "");
    value = value.replace(timePattern, "");
    value = value.replace(regionPattern, "");
    value = value.replace(/\b赞\b|\b回复\b|\d+$/g, "");
    return clean(value);
  }

  function commentEngagement(text) {
    const value = clean(text);
    const likes = [...value.matchAll(/(?:^|\s)赞\s*(\d+(?:\.\d+)?(?:万|w)?)(?=\s|$)/gi)];
    const replies = [...value.matchAll(/(?:展开\s*)?(\d+)\s*条回复/g)];
    return {
      likeCount: likes.at(-1)?.[1] || "0",
      replyCount: replies.at(-1)?.[1] || "0"
    };
  }

  function isCommentCandidate(element) {
    const text = clean(element.innerText || element.textContent);
    if (text.length < 6 || text.length > 1200) return false;
    if (!timePattern.test(text) && !/IP属地|回复/.test(text)) return false;
    if (noisePattern.test(text) && text.length < 30) return false;
    if ((text.match(timePattern) || []).length > 3 && !/comment-item|parent-comment|comment-inner/.test(String(element.className || ""))) return false;

    const rect = element.getBoundingClientRect();
    return rect.width > 160 && rect.height > 24;
  }

  function candidateCommentElements() {
    const items = [...document.querySelectorAll(".comments-container .comment-item")];
    if (items.length) return items;
    const listContainer = document.querySelector(".list-container");
    const selectors = [
      ".list-container > div",
      ".list-container .comment-item",
      ".list-container .parent-comment",
      ".comment-item",
      ".parent-comment",
      ".comment-inner-container",
      "[class*='comment-item']",
      "[class*='commentItem']",
      "[class*='parent-comment']",
      "[class*='comment-inner']"
    ];
    const elements = selectors.flatMap(selector => [...document.querySelectorAll(selector)]);
    const fallbackRoot = listContainer || document;
    const fallback = [...fallbackRoot.querySelectorAll("div, li")].filter(isCommentCandidate);
    return [...new Set([...elements, ...fallback])].filter(isCommentCandidate);
  }

  function parseCommentElement(element, postUrl = location.href) {
    const text = clean(element.innerText || element.textContent);
    const contentElement = element.querySelector(".content");
    const dateElement = element.querySelector(".date");
    const structured = Boolean(contentElement && dateElement);
    const compact = structured ? null : parseCompactCommentText(text);
    const timeInfo = parseCommentTime(structured ? clean(dateElement.textContent) : compact?.rawTimeText || text);
    const rawTimeText = timeInfo?.source || "";
    const ipRegion = structured ? clean(dateElement.querySelector(".location")?.textContent) : compact?.region || regionFromText(text);
    const nickname = structured ? clean(element.querySelector(".name")?.textContent) : compact?.nickname || nicknameFromElement(element);
    const profile = getUserLink(element);
    const commentText = structured ? clean(contentElement.textContent) : compact?.comment || commentTextFromElement(element, nickname, rawTimeText, ipRegion);
    const engagement = commentEngagement(text);
    if (structured) {
      engagement.likeCount = clean(element.querySelector(".like .count")?.textContent).match(/\d+(?:\.\d+)?(?:万|w)?/i)?.[0] || "0";
      engagement.replyCount = clean(element.querySelector(".reply .count")?.textContent).match(/\d+/)?.[0] || "0";
    }

    if (!commentText || commentText.length < 1) return null;
    if (!rawTimeText && !ipRegion) return null;

    return {
      commentId: (element.id || element.closest(".comment-item")?.id || "").replace(/^comment-/, ""),
      nickname,
      postUrl,
      gender: explicitGenderFromElement(element),
      profileAge: "",
      profileLocation: "",
      dateText: dateText(timeInfo?.date),
      rawTimeText,
      ipRegion,
      profile,
      possibleProfile: "",
      ...engagement,
      text: commentText,
      _days: daysBetween(timeInfo?.date)
    };
  }

  function rowKey(row) {
    return globalThis.CommentFilterPostUtils?.strictCommentKey?.(row, row.postUrl || location.href) ||
      (row.commentId || row.cid ? `${row.platform || "xhs"}:comment:${row.commentId || row.cid}` : "") ||
      [row.postUrl || "", row.profile || "", row.nickname || "", row.text || ""].join("||");
  }

  function loadedComments(postUrl = location.href) {
    const map = new Map();
    for (const row of apiRows.values()) {
      map.set(rowKey(row), { ...row, postUrl });
    }
    for (const element of candidateCommentElements()) {
      const row = parseCommentElement(element, postUrl);
      if (!row) continue;
      const key = rowKey(row);
      const existing = map.get(key);
      map.set(key, {
        ...existing,
        ...row,
        likeCount: row.likeCount && row.likeCount !== "0" ? row.likeCount : existing?.likeCount || row.likeCount || "0",
        replyCount: row.replyCount && row.replyCount !== "0" ? row.replyCount : existing?.replyCount || row.replyCount || "0"
      });
    }
    return [...map.values()];
  }

  function passFilters(row, filters) {
    const keywordTerms = parseList(filters.keyword);
    const excludeTerms = parseList(filters.excludeKeyword);
    const regionTerms = parseList(filters.region);
    const text = `${row.nickname} ${row.text} ${row.ipRegion}`;
    const keywordEnabled = keywordTerms.length > 0;
    const regionEnabled = regionTerms.length > 0;
    const keywordOk = !keywordEnabled || matchesAny(text, keywordTerms);
    const regionOk = !regionEnabled || matchesAny(row.ipRegion, regionTerms);
    const relationOk = filters.matchMode === "any" && (keywordEnabled || regionEnabled)
      ? (keywordOk && keywordEnabled) || (regionOk && regionEnabled)
      : keywordOk && regionOk;
    const daysLimit = Number(filters.relativeDays || 30);
    const timeOk = !Number.isFinite(row._days) || row._days <= daysLimit;

    return relationOk && timeOk && matchesNone(text, excludeTerms);
  }

  function visibleCommentPanels() {
    const panels = [...document.querySelectorAll("div, section, main, aside")]
      .filter(element => {
        const rect = element.getBoundingClientRect();
        const text = clean(element.innerText || element.textContent);
        return rect.width > 260 &&
          rect.height > 260 &&
          /评论|回复/.test(text) &&
          (text.match(timePattern) || text.includes("IP属地"));
      })
      .sort((a, b) => {
        const ar = a.getBoundingClientRect();
        const br = b.getBoundingClientRect();
        return br.width * br.height - ar.width * ar.height;
      });
    return panels.slice(0, 6);
  }

  function isScrollable(element) {
    if (!element || element === document.body || element === document.documentElement || element === document.scrollingElement) return false;
    const style = getComputedStyle(element);
    return element.scrollHeight > element.clientHeight + 60 && /(auto|scroll|overlay)/.test(`${style.overflowY} ${style.overflow}`);
  }

  function findScrollTarget() {
    const panels = visibleCommentPanels();
    const candidates = [];

    for (const panel of panels) {
      if (isScrollable(panel)) candidates.push(panel);
      candidates.push(...[...panel.querySelectorAll("div, section, main, aside")].filter(isScrollable));
    }

    const byClass = [...document.querySelectorAll("[class*='comment'], [class*='Comment']")].filter(isScrollable);
    candidates.push(...byClass);

    return [...new Set(candidates)].sort((a, b) => {
      const ar = a.getBoundingClientRect();
      const br = b.getBoundingClientRect();
      return br.height * br.width - ar.height * ar.width;
    })[0] || document.scrollingElement;
  }

  function isProbablyLoading() {
    const text = clean(document.querySelector(".note-scroller, .comments-container, .list-container")?.innerText || document.body?.innerText || "");
    return /加载中|正在加载|努力加载|稍等|loading/i.test(text);
  }

  function replyExpanders() {
    const root = document.querySelector(".comments-container, .comments-el");
    return [...(root?.querySelectorAll(".show-more, [class*='show-more']") || [])]
      .filter(element => /^展开\s*\d+\s*条回复$/.test(clean(element.textContent)));
  }

  function replyThread(button) {
    return button.closest(".parent-comment") || button.parentElement;
  }

  function replyThreadKey(button) {
    const thread = replyThread(button);
    return thread?.querySelector(".comment-item:not(.comment-item-sub)")?.id || thread;
  }

  function replyThreadSignature(thread) {
    return [...(thread?.querySelectorAll(".comment-item-sub") || [])]
      .map(element => element.id || clean(element.textContent)).join("|");
  }

  function replyAttemptSignature(button) {
    return `${replyThreadSignature(replyThread(button))}::${clean(button.textContent)}`;
  }

  function canExpandReply(button, attempts) {
    const attempt = attempts.get(replyThreadKey(button));
    return !attempt || attempt.signature !== replyAttemptSignature(button) || attempt.failures < 3;
  }

  async function waitForReplyExpansion(button, previousSignature, timeout = 6000) {
    const thread = replyThread(button);
    const startedAt = Date.now();
    while (Date.now() - startedAt < timeout) {
      if (await shouldStop()) return { stopped: true, loaded: false };
      // Only this thread's new replies acknowledge the click, not unrelated API traffic.
      if (replyThreadSignature(thread) !== previousSignature) return { stopped: false, loaded: true };
      await sleep(150);
    }
    return { stopped: false, loaded: false };
  }

  async function expandPendingReplies(current, total, matched, attempts) {
    const buttons = replyExpanders().filter(button => canExpandReply(button, attempts)).slice(0, 8);
    const scroller = findScrollTarget();
    const previousTop = scroller?.scrollTop;
    let clicked = 0;
    let expanded = 0;
    let waiting = 0;

    try {
      for (const button of buttons) {
        if (await shouldStop()) return { clicked, expanded, waiting, stopped: true };
        if (!button.isConnected) continue;

        const key = replyThreadKey(button);
        const signature = replyAttemptSignature(button);
        let attempt = attempts.get(key);
        if (!attempt || attempt.signature !== signature) attempt = { signature, failures: 0, retryAt: 0 };
        attempts.set(key, attempt);
        if (attempt.retryAt > Date.now()) {
          waiting += 1;
          continue;
        }

        const previousSignature = replyThreadSignature(replyThread(button));
        button.scrollIntoView({ block: "center", inline: "nearest" });
        setProgress(`展开回复中：正在处理第 ${clicked + 1} 组`, current, total, matched, "expanding");

        try {
          button.click();
          clicked += 1;
        } catch (error) {
          attempt.failures += 1;
          attempt.retryAt = Date.now() + 1000;
          if (attempt.failures < 3) waiting += 1;
          continue;
        }

        const result = await waitForReplyExpansion(button, previousSignature);
        if (result.stopped) return { clicked, expanded, waiting, stopped: true };
        if (result.loaded) {
          attempts.delete(key);
          expanded += 1;
        } else {
          // 网络慢时保留待重试状态，不能把一次超时当作“没有回复”。
          attempt.failures += 1;
          attempt.retryAt = Date.now() + 1000;
          if (attempt.failures < 3) waiting += 1;
          setProgress("等待回复加载：网络响应较慢，暂不结束抓取", current, total, matched, "loading_replies");
        }
      }
    } finally {
      // Expanding an earlier thread must not move the main pagination cursor backwards.
      if (scroller && Number.isFinite(previousTop)) scroller.scrollTop = previousTop;
    }

    return {
      clicked,
      expanded,
      waiting,
      remaining: replyExpanders().length,
      stopped: false
    };
  }

  function collectLoadedRows(loaded, filters, rows) {
    for (const row of loaded) {
      if (!passFilters(row, filters)) continue;
      // A visible #male icon is an explicit public marker, so no profile request is needed to exclude it.
      if (filters.genderFilter === "male" && row.gender === "女") continue;
      if (filters.genderFilter === "female" && row.gender === "男") continue;
      rows.set(rowKey(row), row);
    }
  }

  async function drainPendingReplies(filters, rows, profileInfoByUrl, postUrl, current, total, attempts) {
    for (let round = 0; round < 30; round += 1) {
      if (await shouldStop()) return { stopped: true, goalReached: false };
      const before = replyExpanders().filter(button => canExpandReply(button, attempts)).length;
      if (!before) return { stopped: false, goalReached: false };

      const expansion = await expandPendingReplies(current, total, rows.size, attempts);
      if (expansion.stopped) return { stopped: true, goalReached: false };
      collectLoadedRows(loadedComments(postUrl), filters, rows);

      const verification = await verifyLoadedProfiles(filters, rows, profileInfoByUrl);
      if (verification.stopped) return { stopped: true, goalReached: false };
      if (verification.goalReached) return { stopped: false, goalReached: true };

      const after = replyExpanders().filter(button => canExpandReply(button, attempts)).length;
      if (after === 0) return { stopped: false, goalReached: false };
      if (!expansion.clicked && !expansion.waiting) {
        setProgress("等待回复加载：仍有折叠回复未展开", current, total, rows.size, "loading_replies");
      }
      if (await waitOrStop(expansion.waiting ? 1200 : 500)) return { stopped: true, goalReached: false };
    }
    setProgress("回复展开达到重试上限，已保存当前已获取内容", current, total, rows.size, "reply_timeout");
    return { stopped: false, goalReached: false };
  }

  function diagnoseContainer() {
    const target = findScrollTarget();
    const panels = visibleCommentPanels().map(element => {
      const rect = element.getBoundingClientRect();
      return {
        tag: element.tagName.toLowerCase(),
        cls: String(element.className || "").slice(0, 160),
        x: Math.round(rect.x),
        y: Math.round(rect.y),
        w: Math.round(rect.width),
        h: Math.round(rect.height),
        sample: clean(element.innerText || element.textContent).slice(0, 220)
      };
    });
    return {
      url: location.href,
      loadedCommentCount: loadedComments().length,
      scrollTarget: target ? {
        tag: target.tagName?.toLowerCase?.() || "window",
        cls: String(target.className || "").slice(0, 160),
        scrollTop: Math.round(target.scrollTop || window.scrollY),
        clientHeight: target.clientHeight || window.innerHeight,
        scrollHeight: target.scrollHeight || document.documentElement.scrollHeight
      } : null,
      panels
    };
  }

  function setProgress(message, current, total, matched, phase = "scanning") {
    chrome.storage?.local?.set?.({
      scanProgress: {
        phase,
        message,
        current,
        total,
        matched,
        updatedAt: Date.now()
      }
    });
  }

  function genderMatchesFilter(gender, filter) {
    return filter === "all" || (filter === "male" && gender === "男") || (filter === "female" && gender === "女");
  }

  function uniqueConfirmedGenderCount(rows, filter) {
    return new Set(
      [...rows.values()]
        .filter(row => row.gender && row.gender !== "未知" && row.profile && genderMatchesFilter(row.gender, filter))
        .map(row => row.profile)
    ).size;
  }

  function mergeVerifiedProfiles(rows, profileInfoByUrl, genderFilter) {
    for (const [key, row] of rows.entries()) {
      const info = profileInfoByUrl.get(row.profile);
      if (!info) continue;
      if (genderFilter !== "all" && info.gender && info.gender !== "未知" && !genderMatchesFilter(info.gender, genderFilter)) {
        rows.delete(key);
        continue;
      }
      rows.set(key, {
        ...row,
        gender: info.gender && info.gender !== "未知" ? info.gender : row.gender || "未知",
        profileAge: info.profileAge || row.profileAge || "",
        profileLocation: info.profileLocation || row.profileLocation || ""
      });
    }
  }

  async function verifyLoadedProfiles(filters, rows, profileInfoByUrl) {
    const genderFilter = ["all", "male", "female"].includes(filters.genderFilter) ? filters.genderFilter : "all";
    if (!filters.taskId) {
      return { goalReached: false, matchedCount: uniqueConfirmedGenderCount(rows, genderFilter), stopped: false };
    }

    mergeVerifiedProfiles(rows, profileInfoByUrl, genderFilter);
    const targetsByUrl = new Map();
    for (const row of rows.values()) {
      if (!row.profile || profileInfoByUrl.has(row.profile) || targetsByUrl.has(row.profile)) continue;
      targetsByUrl.set(row.profile, { url: row.profile, nickname: row.nickname || "" });
    }

    const targetGenderCount = Math.max(0, Number(filters.targetGenderCount || filters.targetFemaleCount || 0));
    const targets = [...targetsByUrl.values()];
    for (let offset = 0; offset < targets.length; offset += 4) {
      if (await shouldStop()) return { goalReached: false, matchedCount: uniqueConfirmedGenderCount(rows, genderFilter), stopped: true };
      const batch = targets.slice(offset, offset + 4);
      setProgress(`主页验证中：${offset + 1}-${offset + batch.length}/${targets.length}，已命中 ${uniqueConfirmedGenderCount(rows, genderFilter)} 位`, 0, 0, rows.size);
      let response;
      try {
        response = await chrome.runtime.sendMessage({
          type: "VERIFY_PROFILES_DURING_SCAN",
          taskId: filters.taskId,
          targets: batch
        });
      } catch (error) {
        response = { ok: false };
      }
      if (response?.loginRequired) {
        setProgress(response.message || "检测到未登录，已打开独立登录窗口，评论仍在继续抓取。", 0, 0, rows.size);
        return { goalReached: false, matchedCount: uniqueConfirmedGenderCount(rows, genderFilter), stopped: false, waitingForLogin: true };
      }
      const returnedInfo = new Map((response?.updates || [])
        .filter(update => update?.url)
        .map(update => [update.url, update.info || {}]));
      batch.forEach(target => profileInfoByUrl.set(target.url, returnedInfo.get(target.url) || {}));
      mergeVerifiedProfiles(rows, profileInfoByUrl, genderFilter);
      const matchedCount = uniqueConfirmedGenderCount(rows, genderFilter);
      if (targetGenderCount > 0 && matchedCount >= targetGenderCount) {
        return { goalReached: true, matchedCount, stopped: false };
      }
      if (response?.stopped || await shouldStop()) {
        return { goalReached: false, matchedCount, stopped: true };
      }
    }

    return { goalReached: false, matchedCount: uniqueConfirmedGenderCount(rows, genderFilter), stopped: false };
  }

  function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  async function shouldStop() {
    if (window.__xhsCommentFilterStopRequested) return true;
    const stored = await chrome.storage.local.get({ stopRequested: false });
    return Boolean(stored.stopRequested);
  }

  async function waitOrStop(ms) {
    let elapsed = 0;
    while (elapsed < ms) {
      if (await shouldStop()) return true;
      const step = Math.min(100, ms - elapsed);
      await sleep(step);
      elapsed += step;
    }
    return shouldStop();
  }

  window.addEventListener("comment-filter-stop", () => {
    window.__xhsCommentFilterStopRequested = true;
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes.stopRequested?.newValue) {
      window.__xhsCommentFilterStopRequested = true;
    }
  });

  async function scanComments(filters) {
    if (!filters.backgroundTask) window.__xhsCommentFilterStopRequested = false;
    // XHS keeps the content script alive across client-side route changes.
    // Previous-post API rows must never be mixed into the next task.
    apiRows.clear();
    apiHasMore = true;
    latestApiCursor = "";
    lastApiResponseAt = 0;
    const postInfo = globalThis.CommentFilterPostUtils?.getPostInfo(filters.postUrl || location.href) || {
      url: filters.postUrl || location.href,
      sourceUrl: "",
      title: ""
    };
    const resolvedPostUrl = postInfo.url || filters.postUrl || location.href;
    const configuredLimit = Number(filters.scrollLimit || 0);
    // A zero page limit is a real unlimited scan. The API cursor and explicit
    // no-more response are the termination conditions.
    const total = configuredLimit > 0 ? Math.min(10000, configuredLimit) : 0;
    const rows = new Map();
    const profileInfoByUrl = new Map();
    latestPostStats = null;
    let stopped = false;
    let goalReached = false;

    const api = globalThis.__commentFilterApiV1;
    if (api?.isVerificationPage?.()) return verificationResult(postInfo, rows, 0, total);

    // Reuse the first real comment request emitted by the page and paginate
    // it inside the same session. The page is never scrolled as a fallback.
    if (api?.isVerificationPage?.()) return verificationResult(postInfo, rows, 0, total);
    await waitForApiTemplate(5000);
    if (api?.isVerificationPage?.()) return verificationResult(postInfo, rows, 0, total);
    let paginationWarning = "未捕获可分页评论接口，仅处理当前已加载内容，未继续滚动或展开回复。";
    if (latestApiRequestUrl) {
      const apiResult = await scanCommentsByApi(filters, postInfo, total);
      if (apiResult.waitingForVerification) return apiResult;
      if (apiResult.ok) {
        setProgress(
          apiResult.stopped ? `已停止：接口已抓取 ${apiResult.rows.length} 条` :
            apiResult.goalReached ? "达到目标：接口已命中目标性别用户，停止抓取。" :
            `爬取完成：接口已抓取 ${apiResult.rows.length} 条${apiResult.replyWarning ? `，${apiResult.replyWarning}` : ""}`,
          apiResult.pageCount || 0,
          total,
          apiResult.rows.length,
          apiResult.stopped ? "stopped" : "completed"
        );
        return apiResult;
      }
      paginationWarning = "评论接口分页失败，仅处理当前已加载内容，未继续滚动或展开回复。";
    }

    // No replayable request was captured. Only process comments already
    // present in the page; do not scroll or expand replies through the DOM.
    collectLoadedRows(loadedComments(resolvedPostUrl), filters, rows);
    const verification = await verifyLoadedProfiles(filters, rows, profileInfoByUrl);
    goalReached = verification.goalReached;
    stopped = verification.stopped || await shouldStop();
    const replyWarning = goalReached || stopped ? "" : `，${paginationWarning}`;
    setProgress(
      stopped ? `已停止：当前已加载内容命中 ${rows.size} 条` : goalReached ? `达到目标：已命中目标性别用户，未继续滚动。` : `爬取完成：当前已加载内容命中 ${rows.size} 条${replyWarning}`,
      0,
      total,
      rows.size,
      stopped ? "stopped" : "completed"
    );
    return {
      rows: [...rows.values()].map(({ _days, ...row }) => ({
        ...row,
        postUrl: resolvedPostUrl,
        sourcePostUrl: postInfo.sourceUrl || "",
        postTitle: postInfo.title || ""
      })),
      postUrl: resolvedPostUrl,
      sourceUrl: postInfo.sourceUrl || "",
      title: postInfo.title || "",
      postStats: latestPostStats,
      remainingReplyGroups: 0,
      stopped,
      paginationWarning
    };
  }

  async function openCommentsView() {
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline) {
      const panels = visibleCommentPanels();
      const root = document.querySelector(".comments-container, .comments-el, .list-container");
      if (panels.length || root) {
        const target = findScrollTarget();
        if (target && target !== document.scrollingElement) target.scrollTop = 0;
        (panels[0] || root)?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
        return { ok: true };
      }
      await sleep(250);
    }
    return { ok: false, error: "评论区加载超时，请确认帖子页面已打开后重试。" };
  }

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === "XHS_OPEN_COMMENTS_V1") {
      openCommentsView().then(sendResponse).catch(error => sendResponse({ ok: false, error: error.message || "无法定位评论区。" }));
      return true;
    }
    if (message?.type === "XHS_START_BACKGROUND_SCAN_V2") {
      const filters = { ...(message.filters || {}), backgroundTask: true };
      window.__xhsCommentFilterStopRequested = false;
      Promise.resolve()
        .then(() => sleep(Number(filters.initialDelay || 0)))
        .then(() => scanComments(filters))
        .then(result => chrome.runtime.sendMessage({ type: "COMMENT_SCAN_PAGE_DONE", taskId: message.taskId, result }))
        .catch(error => chrome.runtime.sendMessage({ type: "COMMENT_SCAN_PAGE_DONE", taskId: message.taskId, result: { error: error.message || "扫描失败" } }));
      sendResponse({ ok: true, started: true });
      return false;
    }

    if (message?.type === "XHS_STOP_SCAN_V2") {
      window.__xhsCommentFilterStopRequested = true;
      chrome.storage.local.set({ stopRequested: true });
      sendResponse({ ok: true });
      return false;
    }

    if (message?.type === "XHS_DIAGNOSE_CONTAINER_V2") {
      sendResponse(diagnoseContainer());
      return false;
    }

    if (message?.type === "XHS_EXTRACT_PROFILE_PUBLIC_INFO_V2") {
      setTimeout(() => {
        try {
          sendResponse(extractProfilePublicInfo());
        } catch (error) {
          sendResponse({ error: error.message || "读取主页公开资料失败" });
        }
      }, 900);
      return true;
    }

    if (message?.type === "XHS_SCAN_COMMENTS_V2") {
      scanComments(message.filters || {})
        .then(result => sendResponse(result))
        .catch(error => sendResponse({ error: error.message || "扫描失败" }));
      return true;
    }

    return false;
  });
})();
