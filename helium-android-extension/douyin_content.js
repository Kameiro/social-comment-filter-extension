(() => {
  if (window.__dyCommentFilterInstalledV27) return;
  window.__dyCommentFilterInstalledV27 = true;
  window.__dyCommentFilterStopRequested = false;

  const clean = value => (value || "").replace(/\s+/g, " ").trim();
  const timePattern = /刚刚|分钟前|小时前|今天|昨天|\d{1,3}\s*天前|\d{1,2}\s*(周|星期)前|\d{1,2}\s*(个)?月前|\d{1,2}\s*年前/;
  const containerNoisePattern = /全部评论|大家都在搜|相关搜索|倍速|智能|清屏|连播|加载中|留下你的精彩评论|TA的作品|问AI|识别画面|发送/;

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
    return findCommentPanels().length > 0;
  }

  function getScrollState(targets) {
    return targets.map(target => {
      const text = clean(target.innerText || target.textContent).slice(-500);
      return `${target.scrollTop}:${target.scrollHeight}:${target.clientHeight}:${text}`;
    }).join("|");
  }

  function scrollOneTarget(target) {
    if (!target || target === document.scrollingElement || target === document.documentElement || target === document.body) return false;

    const before = target.scrollTop;
    target.scrollTop = Math.min(target.scrollTop + 780, target.scrollHeight);
    target.scrollBy?.({ top: 780, left: 0, behavior: "auto" });

    const rect = target.getBoundingClientRect?.() || { left: window.innerWidth * 0.76, top: window.innerHeight * 0.52 };
    const event = new WheelEvent("wheel", {
      bubbles: true,
      cancelable: true,
      deltaY: 780,
      clientX: Math.max(0, Math.min(window.innerWidth - 1, rect.left + Math.min(40, Math.max(10, rect.width / 2)))),
      clientY: Math.max(0, Math.min(window.innerHeight - 1, rect.top + Math.min(40, Math.max(10, rect.height / 2))))
    });
    target.dispatchEvent(event);

    return target.scrollTop !== before;
  }

  function isAtScrollBottom(target, tolerance = 8) {
    if (!target) return true;
    return target.scrollHeight - target.clientHeight - target.scrollTop <= tolerance;
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

    if (!isCommentAreaOpen()) {
      clickCommentButton();
      await new Promise(resolve => setTimeout(resolve, 500));
    }

    if (!isCommentAreaOpen()) {
      pressShortcut("x", "KeyX");
      await new Promise(resolve => setTimeout(resolve, 900));
    }

    pauseVideos();

    if (!isCommentAreaOpen()) {
      throw new Error("未检测到评论区，已停止扫描以避免切换视频。请手动打开评论区后再点开始扫描。");
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

  function commentKey(nickname, rawTimeText, regionText, commentBody) {
    return [nickname || "", rawTimeText || "", regionText || "", commentBody || ""].join("||");
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
        const key = commentKey(nickname, rawTimeText, regionText, commentBody);
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
      const key = commentKey(nickname, parsed?.source || "未识别", regionText || filters.region, commentBody);
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

  function uniqueConfirmedFemaleCount(seen) {
    return new Set(
      [...seen.values()]
        .filter(row => row.gender === "女" && row.profile)
        .map(row => row.profile)
    ).size;
  }

  function mergeVerifiedProfiles(seen, updates, removeConfirmedMen) {
    const infoByUrl = new Map(
      (updates || [])
        .filter(update => update?.url && update?.info)
        .map(update => [update.url, update.info])
    );

    for (const [key, row] of seen.entries()) {
      const info = infoByUrl.get(row.profile);
      if (!info) continue;
      if (removeConfirmedMen && info.gender === "男") {
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
    const targetFemaleCount = Math.max(0, Number(filters.targetFemaleCount || 0));
    if (!filters.taskId) {
      return { goalReached: false, femaleCount: uniqueConfirmedFemaleCount(seen), stopped: false };
    }

    // Apply prior profile checks again: the same commenter can appear after a later scroll.
    mergeVerifiedProfiles(
      seen,
      [...verifiedProfiles.entries()].map(([url, info]) => ({ url, info })),
      filters.removeConfirmedMen !== false
    );

    const targetsByUrl = new Map();
    for (const row of seen.values()) {
      if (!row.profile || verifiedProfiles.has(row.profile) || targetsByUrl.has(row.profile)) continue;
      targetsByUrl.set(row.profile, { url: row.profile, nickname: row.nickname || "" });
    }

    const targets = [...targetsByUrl.values()];
    for (let offset = 0; offset < targets.length; offset += 4) {
      if (await shouldStop()) return { goalReached: false, femaleCount: uniqueConfirmedFemaleCount(seen), stopped: true };
      const batch = targets.slice(offset, offset + 4);
      await setProgress({
        phase: "verifying",
        message: `主页验证中：${offset + 1}-${offset + batch.length}/${targets.length}，已确认女性 ${uniqueConfirmedFemaleCount(seen)} 位`,
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
        return { goalReached: false, femaleCount: uniqueConfirmedFemaleCount(seen), stopped: false, waitingForLogin: true };
      }

      const receivedInfo = new Map((response?.updates || [])
        .filter(update => update?.url)
        .map(update => [update.url, update.info || {}]));
      // Avoid repeatedly reopening profiles which fail to expose public data.
      batch.forEach(target => verifiedProfiles.set(target.url, receivedInfo.get(target.url) || {}));
      mergeVerifiedProfiles(seen, [...verifiedProfiles.entries()].map(([url, info]) => ({ url, info })), filters.removeConfirmedMen !== false);
      const femaleCount = uniqueConfirmedFemaleCount(seen);
      if (targetFemaleCount > 0 && femaleCount >= targetFemaleCount) {
        return { goalReached: true, femaleCount, stopped: false };
      }
      if (response?.stopped || await shouldStop()) {
        return { goalReached: false, femaleCount, stopped: true };
      }
    }

    return { goalReached: false, femaleCount: uniqueConfirmedFemaleCount(seen), stopped: false };
  }

  async function scanComments(filters) {
    window.__dyCommentFilterStopRequested = false;
    if (!filters.backgroundTask) await chrome.storage.local.set({ stopRequested: false });
    const configuredLimit = Number(filters.scrollLimit || 0);
    const maxScrolls = configuredLimit > 0 ? Math.min(10000, configuredLimit) : 10000;
    const limitLabel = configuredLimit > 0 ? String(maxScrolls) : "自动到底";
    await setProgress({ phase: "preparing", message: "准备中：正在打开评论区并暂停视频...", current: 0, total: maxScrolls, matched: 0 });
    await preparePostPage();
    await setProgress({ phase: "crawling", message: "爬取中：正在翻页并筛选评论...", current: 0, total: maxScrolls, matched: 0 });
    const seen = new Map();
    const verifiedProfiles = new Map();
    const scrollTargets = findScrollTargets();
    if (scrollTargets.length === 0) {
      collect(filters, seen);
      const verification = await verifyLoadedProfiles(filters, seen, verifiedProfiles);
      await setProgress({ phase: "done", message: "爬取完成：未找到可滚动评论容器，已扫描当前可见评论。", current: 0, total: maxScrolls, matched: seen.size });
      return {
        rows: [...seen.values()],
        stopped: verification.stopped
      };
    }

    let activeTargetIndex = 0;
    let lastState = "";
    let samePositionCount = 0;
    let goalReached = false;
    let lastIndex = 0;
    const exhaustedScrollTargets = new Set();

    for (let index = 0; index < maxScrolls; index += 1) {
      lastIndex = index + 1;
      if (await shouldStop()) break;
      collect(filters, seen);
      const verification = await verifyLoadedProfiles(filters, seen, verifiedProfiles);
      if (verification.stopped) break;
      if (verification.goalReached) {
        goalReached = true;
        await setProgress({
          phase: "done",
          message: `达到目标：已确认 ${verification.femaleCount} 位女性用户，停止翻页。`,
          current: index + 1,
          total: maxScrolls,
          matched: seen.size
        });
        break;
      }
      await setProgress({ phase: "crawling", message: `爬取中：第 ${index + 1}/${limitLabel} 次翻页，当前命中 ${seen.size} 条`, current: index + 1, total: maxScrolls, matched: seen.size });
      if (await shouldStop()) break;

      const target = scrollTargets[activeTargetIndex] || scrollTargets[0];
      if (!target) break;
      const movedImmediately = scrollOneTarget(target);
      if (await waitOrStop(650)) break;

      // Confirm a bottom position once more so a slow network response can extend the list.
      if (isAtScrollBottom(target)) {
        const heightBeforeConfirm = target.scrollHeight;
        if (await waitOrStop(1200)) break;
        const stillAtBottom = isAtScrollBottom(target);
        const listDidNotGrow = target.scrollHeight <= heightBeforeConfirm + 2;
        if (stillAtBottom && listDidNotGrow && !isProbablyLoadingComments()) {
          exhaustedScrollTargets.add(target);
          const nextTargetIndex = scrollTargets.findIndex(candidate => !exhaustedScrollTargets.has(candidate));
          if (nextTargetIndex === -1) {
            await setProgress({ phase: "done", message: `提前结束：评论容器已滚动到底，当前命中 ${seen.size} 条`, current: index + 1, total: maxScrolls, matched: seen.size });
            break;
          }
          activeTargetIndex = nextTargetIndex;
          lastState = "";
          samePositionCount = 0;
          continue;
        }
        exhaustedScrollTargets.delete(target);
      }

      const currentState = getScrollState(scrollTargets);
      if (currentState === lastState) {
        if (isProbablyLoadingComments()) {
          await setProgress({ phase: "loading", message: `等待加载：第 ${index + 1}/${limitLabel} 次翻页，当前命中 ${seen.size} 条`, current: index + 1, total: maxScrolls, matched: seen.size });
          if (await waitOrStop(1800)) break;
          samePositionCount = 0;
        } else {
          if (await waitOrStop(1200)) break;
          const retryState = getScrollState(scrollTargets);
          if (retryState === currentState) samePositionCount += 1;
          else samePositionCount = 0;
        }
      } else samePositionCount = 0;

      lastState = currentState;
      if (samePositionCount >= 3) {
        exhaustedScrollTargets.add(target);
        const nextTargetIndex = scrollTargets.findIndex(candidate => !exhaustedScrollTargets.has(candidate));
        if (nextTargetIndex === -1) {
          await setProgress({ phase: "done", message: `提前结束：评论已加载到底，当前命中 ${seen.size} 条`, current: index + 1, total: maxScrolls, matched: seen.size });
          break;
        }
        activeTargetIndex = nextTargetIndex;
        samePositionCount = 0;
        lastState = "";
      } else if (!movedImmediately && scrollTargets.length > 1) {
        const nextTargetIndex = scrollTargets.findIndex(candidate => candidate !== target && !exhaustedScrollTargets.has(candidate));
        if (nextTargetIndex !== -1) activeTargetIndex = nextTargetIndex;
      }
    }

    if (!goalReached) {
      collect(filters, seen);
      const verification = await verifyLoadedProfiles(filters, seen, verifiedProfiles);
      goalReached = verification.goalReached;
    }
    const stopped = await shouldStop();
    await setProgress({
      phase: stopped ? "stopped" : "done",
      message: stopped ? `已停止：当前命中 ${seen.size} 条` : goalReached ? `达到目标：已确认 ${uniqueConfirmedFemaleCount(seen)} 位女性用户，停止翻页。` : `爬取完成：当前命中 ${seen.size} 条`,
      current: lastIndex,
      total: maxScrolls,
      matched: seen.size
    });
    return {
      rows: [...seen.values()],
      stopped
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

    if (message?.type === "DY_START_BACKGROUND_SCAN_V1") {
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
