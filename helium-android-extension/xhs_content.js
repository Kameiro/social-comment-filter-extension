(() => {
  if (window.__xhsCommentFilterInstalledV2) return;
  window.__xhsCommentFilterInstalledV2 = true;
  window.__xhsCommentFilterStopRequested = false;

  const clean = value => String(value || "").replace(/\s+/g, " ").trim();
  const timePattern = /刚刚|分钟前|小时前|今天|昨天|\d{1,3}\s*天前|\d{1,2}\s*(周|星期)前|\d{1,2}\s*(个)?月前|\d{1,2}\s*年前|(\d{4}[-/.年])?\d{1,2}[-/.月]\d{1,2}/;
  const noisePattern = /说点什么|登录|打开APP|相关推荐|赞和收藏|分享|收起|展开|查看更多|作者|小红书精选/;
  const regionPattern = /北京|天津|上海|重庆|河北|山西|辽宁|吉林|黑龙江|江苏|浙江|安徽|福建|江西|山东|河南|湖北|湖南|广东|海南|四川|贵州|云南|陕西|甘肃|青海|台湾|内蒙古|广西|西藏|宁夏|新疆|香港|澳门|美国|英国|法国|德国|日本|韩国|加拿大|澳大利亚|新加坡|马来西亚|泰国|越南|菲律宾|文莱|西班牙|意大利|俄罗斯/;
  const metaTailPattern = /(刚刚|今天|昨天|\d{1,2}\s*分钟前|\d{1,2}\s*小时前|\d{1,3}\s*天前|\d{1,2}\s*(?:周|星期)前|\d{1,2}\s*(?:个)?月前|\d{1,2}\s*年前|(?:20\d{2}[-/.年])?\d{1,2}[-/.月]\d{1,2})\s*([^\s赞回复]*)?/;

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
      if (reference === "#female") return "女";
      if (reference === "#male") return "男";
    }
    return "未知";
  }

  function extractProfilePublicInfo() {
    return {
      gender: explicitGenderFromElement(document),
      profileAge: "",
      profileLocation: ""
    };
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
    const compact = parseCompactCommentText(text);
    const timeInfo = parseCommentTime(compact?.rawTimeText || text);
    const rawTimeText = timeInfo?.source || "";
    const ipRegion = compact?.region || regionFromText(text);
    const nickname = compact?.nickname || nicknameFromElement(element);
    const profile = getUserLink(element);
    const commentText = compact?.comment || commentTextFromElement(element, nickname, rawTimeText, ipRegion);
    const engagement = commentEngagement(text);

    if (!commentText || commentText.length < 1) return null;
    if (!rawTimeText && !ipRegion) return null;

    return {
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
    return [row.postUrl || "", row.profile || "", row.nickname || "", row.rawTimeText || "", row.ipRegion || "", row.text || ""].join("||");
  }

  function loadedComments(postUrl = location.href) {
    const map = new Map();
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

  function scrollTarget(target) {
    if (!target) return false;
    const before = target.scrollTop || window.scrollY;
    if (target === document.scrollingElement || target === document.body || target === document.documentElement) {
      window.scrollBy({ top: 700, behavior: "auto" });
      return window.scrollY !== before;
    }

    target.scrollTop += 700;
    target.scrollBy?.({ top: 700, behavior: "auto" });
    target.dispatchEvent(new WheelEvent("wheel", { bubbles: true, cancelable: true, deltaY: 700 }));
    return target.scrollTop !== before;
  }

  function scrollState(target) {
    if (!target) return "";
    if (target === document.scrollingElement || target === document.body || target === document.documentElement) {
      return `${Math.round(window.scrollY)}:${document.documentElement.scrollHeight}:${loadedComments().length}`;
    }
    return `${Math.round(target.scrollTop)}:${target.scrollHeight}:${target.clientHeight}:${loadedComments().length}`;
  }

  function isAtScrollBottom(target, tolerance = 8) {
    if (!target) return true;
    if (target === document.scrollingElement || target === document.body || target === document.documentElement) {
      return window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - tolerance;
    }
    return target.scrollHeight - target.clientHeight - target.scrollTop <= tolerance;
  }

  function isProbablyLoading() {
    const text = clean(document.querySelector(".note-scroller, .comments-container, .list-container")?.innerText || document.body?.innerText || "");
    return /加载中|正在加载|努力加载|稍等|loading/i.test(text);
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

  function setProgress(message, current, total, matched) {
    chrome.storage?.local?.set?.({
      scanProgress: {
        phase: "scanning",
        message,
        current,
        total,
        matched,
        updatedAt: Date.now()
      }
    });
  }

  function uniqueConfirmedFemaleCount(rows) {
    return new Set(
      [...rows.values()]
        .filter(row => row.gender === "女" && row.profile)
        .map(row => row.profile)
    ).size;
  }

  function mergeVerifiedProfiles(rows, profileInfoByUrl, removeConfirmedMen) {
    for (const [key, row] of rows.entries()) {
      const info = profileInfoByUrl.get(row.profile);
      if (!info) continue;
      if (removeConfirmedMen && info.gender === "男") {
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
    if (!filters.taskId) {
      return { goalReached: false, femaleCount: uniqueConfirmedFemaleCount(rows), stopped: false };
    }

    mergeVerifiedProfiles(rows, profileInfoByUrl, filters.removeConfirmedMen !== false);
    const targetsByUrl = new Map();
    for (const row of rows.values()) {
      if (!row.profile || profileInfoByUrl.has(row.profile) || targetsByUrl.has(row.profile)) continue;
      targetsByUrl.set(row.profile, { url: row.profile, nickname: row.nickname || "" });
    }

    const targetFemaleCount = Math.max(0, Number(filters.targetFemaleCount || 0));
    const targets = [...targetsByUrl.values()];
    for (let offset = 0; offset < targets.length; offset += 4) {
      if (await shouldStop()) return { goalReached: false, femaleCount: uniqueConfirmedFemaleCount(rows), stopped: true };
      const batch = targets.slice(offset, offset + 4);
      setProgress(`主页验证中：${offset + 1}-${offset + batch.length}/${targets.length}，已确认女性 ${uniqueConfirmedFemaleCount(rows)} 位`, 0, 0, rows.size);
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
        return { goalReached: false, femaleCount: uniqueConfirmedFemaleCount(rows), stopped: false, waitingForLogin: true };
      }
      const returnedInfo = new Map((response?.updates || [])
        .filter(update => update?.url)
        .map(update => [update.url, update.info || {}]));
      batch.forEach(target => profileInfoByUrl.set(target.url, returnedInfo.get(target.url) || {}));
      mergeVerifiedProfiles(rows, profileInfoByUrl, filters.removeConfirmedMen !== false);
      const femaleCount = uniqueConfirmedFemaleCount(rows);
      if (targetFemaleCount > 0 && femaleCount >= targetFemaleCount) {
        return { goalReached: true, femaleCount, stopped: false };
      }
      if (response?.stopped || await shouldStop()) {
        return { goalReached: false, femaleCount, stopped: true };
      }
    }

    return { goalReached: false, femaleCount: uniqueConfirmedFemaleCount(rows), stopped: false };
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
    const configuredLimit = Number(filters.scrollLimit || 0);
    const total = configuredLimit > 0 ? Math.min(10000, configuredLimit) : 10000;
    const limitLabel = configuredLimit > 0 ? String(total) : "自动到底";
    const rows = new Map();
    const profileInfoByUrl = new Map();
    let stopped = false;
    let goalReached = false;
    let lastState = "";
    let noChangeCount = 0;
    let lastLoadedCount = 0;

    for (let index = 0; index <= total; index += 1) {
      if (await shouldStop()) {
        stopped = true;
        break;
      }
      const postUrl = filters.postUrl || location.href;
      const loaded = loadedComments(postUrl);
      for (const row of loaded) {
        if (!passFilters(row, filters)) continue;
        // A visible #male icon is an explicit public marker, so no profile request is needed to exclude it.
        if (filters.removeConfirmedMen !== false && row.gender === "男") continue;
        rows.set(rowKey(row), row);
      }

      const verification = await verifyLoadedProfiles(filters, rows, profileInfoByUrl);
      if (verification.stopped) {
        stopped = true;
        break;
      }
      if (verification.goalReached) {
        goalReached = true;
        setProgress(`达到目标：已确认 ${verification.femaleCount} 位女性用户，停止翻页。`, index, total, rows.size);
        break;
      }

      setProgress(`爬取中：${index}/${limitLabel}，已命中 ${rows.size} 条`, index, total, rows.size);
      if (index === total) break;
      if (await shouldStop()) {
        stopped = true;
        break;
      }

      const target = findScrollTarget();
      const moved = scrollTarget(target);
      if (await waitOrStop(900)) {
        stopped = true;
        break;
      }
      if (isAtScrollBottom(target)) {
        const heightBeforeConfirm = target === document.scrollingElement || target === document.body || target === document.documentElement
          ? document.documentElement.scrollHeight
          : target.scrollHeight;
        if (await waitOrStop(1200)) {
          stopped = true;
          break;
        }
        const heightAfterConfirm = target === document.scrollingElement || target === document.body || target === document.documentElement
          ? document.documentElement.scrollHeight
          : target.scrollHeight;
        if (isAtScrollBottom(target) && heightAfterConfirm <= heightBeforeConfirm + 2 && !isProbablyLoading()) {
          setProgress(`提前结束：评论容器已滚动到底，已命中 ${rows.size} 条`, index + 1, total, rows.size);
          break;
        }
      }
      const currentState = scrollState(target);
      const loadedCount = loaded.length;
      const noChange = !moved || (currentState === lastState && loadedCount === lastLoadedCount);
      if (noChange) {
        if (isProbablyLoading()) {
          setProgress(`等待加载：${index + 1}/${limitLabel}，已命中 ${rows.size} 条`, index + 1, total, rows.size);
          if (await waitOrStop(1800)) {
            stopped = true;
            break;
          }
          noChangeCount = 0;
        } else {
          if (await waitOrStop(1200)) {
            stopped = true;
            break;
          }
          const retryState = scrollState(target);
          const retryLoadedCount = loadedComments(postUrl).length;
          if (retryState === currentState && retryLoadedCount === loadedCount) noChangeCount += 1;
          else noChangeCount = 0;
        }
      } else {
        noChangeCount = 0;
      }
      lastState = currentState;
      lastLoadedCount = loadedCount;
      if (noChangeCount >= 3) {
        setProgress(`提前结束：评论已加载到底，已命中 ${rows.size} 条`, index + 1, total, rows.size);
        break;
      }
    }

    if (!goalReached && !stopped) {
      const verification = await verifyLoadedProfiles(filters, rows, profileInfoByUrl);
      goalReached = verification.goalReached;
      stopped = verification.stopped;
    }

    setProgress(
      stopped ? `已停止：当前命中 ${rows.size} 条` : goalReached ? `达到目标：已确认 ${uniqueConfirmedFemaleCount(rows)} 位女性用户，停止翻页。` : `爬取完成：当前命中 ${rows.size} 条`,
      total,
      total,
      rows.size
    );
    return { rows: [...rows.values()].map(({ _days, ...row }) => row), stopped };
  }

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
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
