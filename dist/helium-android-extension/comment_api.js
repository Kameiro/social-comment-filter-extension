(() => {
  if (globalThis.__commentFilterApiV1) return;

  const clean = value => String(value ?? "").replace(/\s+/g, " ").trim();
  const verificationPattern = /依次点击文字|请依次点击|点击图中|安全验证|人机验证|滑块验证|verify.?challenge|verifycenter|sec_verify|captcha/i;
  const replayMinInterval = 1500;
  let lastReplayAt = 0;
  let replayQueue = Promise.resolve();

  function isVerificationText(value) {
    return verificationPattern.test(clean(value).slice(0, 20000));
  }

  function isVerificationPacket(packet) {
    const status = Number(packet?.status || 0);
    const body = String(packet?.body || "");
    const url = String(packet?.url || "");
    if (isVerificationText(body) || isVerificationText(url)) return true;
    if (/验证码|安全校验|人机校验|风险控制|请完成验证|依次点击|滑块|captcha|verify.?challenge|verifycenter|sec_verify|challenge_url|captcha_url/i.test(body)) return true;
    if (/captcha|verifycenter|sec_verify|challenge/i.test(url)) return true;
    // HTTP status alone is not enough: ordinary session expiry, rate limits,
    // or a rejected replay can also be 401/403/429. Only classify it as a
    // challenge when the page visibly contains a challenge at this moment.
    return (status === 401 || status === 403 || status === 429) && isVerificationPage();
  }

  function isVerificationPage() {
    const url = String(location.href || "");
    const body = clean(document.body?.innerText || document.body?.textContent || "");
    const challengeNodes = [...(document.querySelectorAll?.(
      "iframe[src*='captcha'], iframe[src*='verify'], [class*='captcha'], [class*='captcha-verify'], [class*='verifycenter'], [class*='sec-captcha'], [id*='captcha'], [id*='captcha-verify'], [id*='captcha_container'], [id*='verifycenter']"
    ) || [])];
    const visibleChallenge = challengeNodes.some(challengeNode => {
      const rect = challengeNode.getBoundingClientRect?.();
      const style = globalThis.getComputedStyle?.(challengeNode);
      const marker = `${challengeNode.getAttribute?.("class") || ""} ${challengeNode.getAttribute?.("id") || ""} ${challengeNode.getAttribute?.("src") || ""} ${challengeNode.innerText || ""}`;
      return Boolean(
        rect?.width && rect?.height &&
        style?.display !== "none" && style?.visibility !== "hidden" && style?.opacity !== "0" &&
        (/captcha|verifycenter|sec-captcha|依次点击|安全验证|人机验证|滑块验证/i.test(marker))
      );
    });
    return isVerificationText(`${url} ${body}`) || /(?:^|\/)verify(?:\/|\?|$)|captcha/i.test(url) || Boolean(visibleChallenge);
  }

  function verificationError(message = "平台触发了人机验证，请完成页面验证后再继续。") {
    const error = new Error(message);
    error.code = "PLATFORM_VERIFICATION";
    error.verification = true;
    return error;
  }

  function pageCounterText(currentPage, configuredLimit) {
    const current = Math.max(0, Number(currentPage) || 0);
    const limit = Math.max(0, Number(configuredLimit) || 0);
    return limit > 0 ? `第 ${current}/${limit} 页` : `第 ${current} 页`;
  }

  function platformFromUrl(url) {
    if (/douyin\.com/i.test(url || "")) return "douyin";
    if (/xiaohongshu\.com/i.test(url || "")) return "xhs";
    if (/kuaishou\.com/i.test(url || "")) return "kuaishou";
    return "";
  }

  function firstValue(...values) {
    return values.find(value => value !== undefined && value !== null && value !== "") ?? "";
  }

  function dateFromTimestamp(value) {
    const number = Number(value);
    if (!Number.isFinite(number) || number <= 0) return null;
    const milliseconds = number < 100000000000 ? number * 1000 : number;
    const date = new Date(milliseconds);
    return Number.isNaN(date.getTime()) ? null : date;
  }

  function relativeTime(date) {
    if (!date) return "";
    const elapsed = Math.max(0, Date.now() - date.getTime());
    const minutes = Math.floor(elapsed / 60000);
    const hours = Math.floor(elapsed / 3600000);
    const days = Math.floor(elapsed / 86400000);
    if (minutes < 1) return "刚刚";
    if (minutes < 60) return `${minutes}分钟前`;
    if (hours < 24) return `${hours}小时前`;
    if (days < 7) return `${days}天前`;
    if (days < 30) return `${Math.max(1, Math.floor(days / 7))}周前`;
    if (days < 365) return `${Math.max(1, Math.floor(days / 30))}月前`;
    return `${Math.max(1, Math.floor(days / 365))}年前`;
  }

  function profileLink(platform, user) {
    if (!user || typeof user !== "object") return "";
    if (platform === "douyin") {
      const secUid = firstValue(user.sec_uid, user.secUid, user.sec_user_id);
      return secUid ? `https://www.douyin.com/user/${encodeURIComponent(secUid)}` : "";
    }
    if (platform === "kuaishou") {
      const userId = firstValue(user.userId, user.user_id, user.id, user.userPrincipalId);
      return userId ? `https://www.kuaishou.com/profile/${encodeURIComponent(userId)}` : "";
    }
    const userId = firstValue(user.user_id, user.userId, user.id);
    if (!userId) return "";
    const token = firstValue(user.xsec_token, user.xsecToken);
    const query = token
      ? `?xsec_token=${encodeURIComponent(token)}&xsec_source=pc_note`
      : "";
    return `https://www.xiaohongshu.com/user/profile/${encodeURIComponent(userId)}${query}`;
  }

  function userFromObject(object) {
    const nested = object?.user || object?.user_info || object?.userInfo || object?.comment_user || object?.author;
    if (nested && typeof nested === "object") return nested;
    // 快手部分响应会把作者字段直接放在评论对象上，而不是包在 user/author 中。
    if (object && typeof object === "object" && [
      object.userName, object.user_name, object.userId, object.user_id,
      object.headUrl, object.avatar, object.avatarUrl, object.userPrincipalId
    ].some(value => value !== undefined && value !== null && value !== "")) return object;
    return null;
  }

  function looksLikeComment(object, platform) {
    if (!object || typeof object !== "object" || Array.isArray(object)) return false;
    const user = userFromObject(object);
    const text = firstValue(object.text, object.content, object.comment_text, object.commentText, object.comment);
    if (!user || typeof text !== "string" || !clean(text)) return false;
    const hasMetadata = [
      object.create_time, object.createTime, object.create_time_ms,
      object.digg_count, object.like_count, object.sub_comment_count,
      object.reply_comment_total, object.ip_location, object.ip_label,
      object.cid, object.comment_id, object.commentId, object.id, object.photoId
    ].some(value => value !== undefined && value !== null);
    if (!hasMetadata) return false;
    return platform === "xhs" ? Boolean(object.user_info || object.ip_location || object.note_id) : true;
  }

  function findComments(payload, platform) {
    const output = [];
    const visited = new Set();

    function walk(value, depth = 0) {
      if (!value || typeof value !== "object" || depth > 12 || visited.has(value)) return;
      visited.add(value);
      if (looksLikeComment(value, platform)) output.push(value);
      if (Array.isArray(value)) {
        for (const item of value) walk(item, depth + 1);
      } else {
        for (const child of Object.values(value)) walk(child, depth + 1);
      }
    }

    walk(payload);
    return output;
  }

  function normalizeComment(object, platform, postUrl) {
    const user = userFromObject(object) || {};
    const date = dateFromTimestamp(firstValue(object.create_time_ms, object.create_time, object.createTime, object.timestamp));
    const nickname = clean(firstValue(user.nickname, user.nick_name, user.nickName, user.name, object.nickname, object.userName, object.name));
    const text = clean(firstValue(object.text, object.content, object.comment_text, object.commentText, object.comment));
    const ipRegion = clean(firstValue(object.ip_location, object.ip_label, object.ipLabel, object.ipRegion, user.ip_location, user.ip_label, user.location));
    const profile = profileLink(platform, user);
    const id = clean(firstValue(object.cid, object.comment_id, object.commentId, object.commentID, object.id));
    const rawTimeText = relativeTime(date);
    const dateText = date ? date.toISOString().slice(0, 10) : "日期未识别";
    const likeCount = String(firstValue(object.digg_count, object.like_count, object.likeCount, object.liked_count, object.likeNum, object.likeCountV2, "0"));
    const replyCount = String(firstValue(object.reply_comment_total, object.sub_comment_count, object.reply_count, object.replyCount, object.subCommentCount, object.subCommentCountV2, "0"));
    const key = id
      ? `${platform}:comment:${id}`
      : [platform, nickname, rawTimeText, ipRegion, text].join("||");
    return {
      commentId: id,
      cid: id,
      nickname: nickname || "未知昵称",
      gender: "未知",
      profileAge: "",
      profileLocation: "",
      dateText,
      rawTimeText,
      ipRegion,
      profile,
      possibleProfile: profile ? "" : "",
      likeCount,
      replyCount,
      text,
      _days: date ? Math.max(0, Math.floor((Date.now() - date.getTime()) / 86400000)) : Infinity,
      _apiKey: key,
      postUrl
    };
  }

  function extractPagination(payload) {
    const candidates = [
      payload,
      payload?.data,
      payload?.data?.data,
      payload?.result,
      payload?.result?.data
    ].filter(value => value && typeof value === "object");
    let hasMore = null;
    let cursor = "";
    for (const value of candidates) {
      if (hasMore === null) {
        const raw = firstValue(value.has_more, value.hasMore, value.has_next, value.hasNext);
        if (raw !== "") hasMore = Boolean(raw === true || raw === 1 || raw === "1" || raw === "true");
      }
      if (!cursor) cursor = clean(firstValue(value.cursor, value.next_cursor, value.nextCursor, value.next_cursor_str, value.pcursor, value.pcursorV2));
    }
    if (cursor === "no_more") hasMore = false;
    else if (hasMore === null && cursor) hasMore = true;
    return { hasMore, cursor };
  }

  function rowsFromPacket(packet, platform, postUrl) {
    if (!packet || packet.platform && packet.platform !== platform) return [];
    let payload;
    try {
      payload = JSON.parse(packet.body || "");
    } catch (error) {
      return [];
    }
    return findComments(payload, platform)
      .map(object => normalizeComment(object, platform, postUrl))
      .filter(row => row.text && row.text.length <= 1200 && row.nickname);
  }

  function listen(callback) {
    if (typeof callback !== "function" || globalThis.__commentFilterApiListenerV1) return;
    globalThis.__commentFilterApiListenerV1 = true;
    window.addEventListener("comment-filter-network-response", event => {
      try {
        const packet = JSON.parse(String(event.detail || ""));
        packet.platform = platformFromUrl(packet.url);
        callback(packet);
      } catch (error) {
        // Ignore malformed or non-JSON packets.
      }
    });
    window.dispatchEvent(new CustomEvent("comment-filter-network-read"));
  }

  function replayRequest(url, { timeout = 18000 } = {}) {
    const requestId = `extension-comment-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const request = replayQueue.then(async () => {
      if (isVerificationPage()) throw verificationError();
      const wait = Math.max(0, replayMinInterval - (Date.now() - lastReplayAt));
      if (wait) await new Promise(resolve => setTimeout(resolve, wait));
      if (isVerificationPage()) throw verificationError();
      lastReplayAt = Date.now();
      return new Promise((resolve, reject) => {
      let timer;
      const onResult = event => {
        let packet;
        try {
          packet = JSON.parse(String(event.detail || ""));
        } catch (error) {
          return;
        }
        if (packet?.requestId !== requestId) return;
        window.removeEventListener("comment-filter-network-fetch-result", onResult);
        clearTimeout(timer);
        if (packet.error) reject(new Error(packet.error));
        else if (isVerificationPacket(packet)) reject(verificationError());
        else resolve(packet);
      };
      window.addEventListener("comment-filter-network-fetch-result", onResult);
      timer = setTimeout(() => {
        window.removeEventListener("comment-filter-network-fetch-result", onResult);
        reject(new Error("评论接口响应超时"));
      }, timeout);
      window.dispatchEvent(new CustomEvent("comment-filter-network-fetch", {
        detail: JSON.stringify({ requestId, url })
      }));
      });
    });
    replayQueue = request.catch(() => {});
    return request;
  }

  globalThis.__commentFilterApiV1 = {
    listen,
    rowsFromPacket,
    extractPagination,
    replayRequest,
    isVerificationPacket,
    isVerificationPage,
    verificationError
    ,pageCounterText
  };
})();
