(() => {
  if (globalThis.__commentFilterDouyinSearchV1) return;
  globalThis.__commentFilterDouyinSearchV1 = true;

  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

  function walkDicts(value, seen = new Set()) {
    const result = [];
    if (!value || typeof value !== "object" || seen.has(value)) return result;
    seen.add(value);
    if (Array.isArray(value)) {
      for (const item of value) result.push(...walkDicts(item, seen));
      return result;
    }
    result.push(value);
    for (const child of Object.values(value)) {
      if (child && typeof child === "object") result.push(...walkDicts(child, seen));
    }
    return result;
  }

  function uniqueItems(items) {
    const map = new Map();
    for (const item of items || []) {
      const id = String(item?.aweme_id || item?.awemeId || "");
      if (id && !map.has(id)) map.set(id, item);
    }
    return [...map.values()];
  }

  function extractItems(data) {
    const items = [];
    for (const node of walkDicts(data)) {
      if (node.aweme_info && typeof node.aweme_info === "object") items.push(node.aweme_info);
      else if (node.aweme_detail && typeof node.aweme_detail === "object") items.push(node.aweme_detail);
      else if (node.aweme_id && (node.author || node.desc)) items.push(node);
    }
    return uniqueItems(items);
  }

  function number(value) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function normalize(item) {
    const author = item.author && typeof item.author === "object" ? item.author : {};
    const id = String(item.aweme_id || "");
    const createTime = number(item.create_time);
    return {
      id,
      url: id ? `https://www.douyin.com/video/${id}` : "",
      title: String(item.desc || item.title || "").trim(),
      nickname: String(author.nickname || author.unique_id || "").trim(),
      authorUrl: author.sec_uid ? `https://www.douyin.com/user/${author.sec_uid}` : "",
      diggCount: number(item.statistics?.digg_count ?? item.digg_count),
      commentCount: number(item.statistics?.comment_count ?? item.comment_count),
      shareCount: number(item.statistics?.share_count ?? item.share_count),
      collectCount: number(item.statistics?.collect_count ?? item.collect_count),
      createTime,
      createTimeText: createTime ? new Date(createTime * 1000).toLocaleString() : ""
    };
  }

  function isRiskPacket(packet) {
    const body = String(packet?.body || "");
    return packet?.status === 403 || /captcha|verify|验证码|访问频繁|验证中心|risk/i.test(body);
  }

  async function discover(options = {}) {
    const keyword = String(options.keyword || "").trim();
    const limit = Math.max(1, Math.min(500, Number(options.limit) || 50));
    const maxPages = Math.max(1, Math.min(100, Number(options.maxPages) || 20));
    const sort = ["relevance", "likes", "latest"].includes(options.sort) ? options.sort : "relevance";
    if (!keyword) throw new Error("请输入搜索关键词。");

    const items = new Map();
    let riskMessage = "";
    let lastPacketAt = Date.now();
    let responseCount = 0;

    const onResponse = event => {
      try {
        const packet = JSON.parse(String(event.detail || ""));
        lastPacketAt = Date.now();
        responseCount += 1;
        if (isRiskPacket(packet)) riskMessage = "抖音搜索接口触发了验证或风控。";
        const payload = JSON.parse(String(packet.body || ""));
        for (const item of extractItems(payload)) {
          const normalized = normalize(item);
          if (normalized.id) items.set(normalized.id, normalized);
        }
      } catch (error) {
        // Search pages may emit non-JSON telemetry; ignore it.
      }
    };

    window.addEventListener("comment-filter-search-response", onResponse);
    window.dispatchEvent(new CustomEvent("comment-filter-search-read"));
    try {
      for (let page = 0; page < maxPages; page += 1) {
        if (riskMessage || items.size >= limit) break;
        const before = items.size;
        window.scrollBy(0, Math.max(480, Math.floor(window.innerHeight * 0.85)));
        await sleep(Math.max(900, Number(options.waitMs) || 1400));
        const quiet = Date.now() - lastPacketAt > 3500;
        if (items.size === before && quiet && responseCount > 0) break;
      }
    } finally {
      window.removeEventListener("comment-filter-search-response", onResponse);
    }

    const results = [...items.values()].sort((left, right) => {
      if (sort === "likes") return Number(right.diggCount || 0) - Number(left.diggCount || 0);
      if (sort === "latest") return Number(right.createTime || 0) - Number(left.createTime || 0);
      return 0;
    });

    return {
      ok: true,
      keyword,
      sort,
      results: results.slice(0, limit),
      pages: Math.min(maxPages, Math.max(1, responseCount)),
      riskMessage
    };
  }

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type !== "DY_DISCOVER_SEARCH_V1") return false;
    discover(message.options || {})
      .then(sendResponse)
      .catch(error => sendResponse({ ok: false, error: error.message || "搜索帖子失败。" }));
    return true;
  });
})();
