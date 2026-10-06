(() => {
  if (globalThis.CommentFilterPostUtils) return;

  const clean = value => String(value ?? "").replace(/\s+/g, " ").trim();

  function platformFromUrl(value) {
    if (/xiaohongshu\.com/i.test(value || "")) return "xhs";
    if (/douyin\.com/i.test(value || "")) return "douyin";
    if (/kuaishou\.com/i.test(value || "")) return "kuaishou";
    return "";
  }

  function toUrl(value) {
    try {
      return new URL(String(value || ""));
    } catch (error) {
      return null;
    }
  }

  function douyinIdFromUrl(value) {
    const url = value instanceof URL ? value : toUrl(value);
    if (!url || platformFromUrl(url.href) !== "douyin") return "";
    return url.searchParams.get("modal_id") ||
      url.searchParams.get("video_id") ||
      url.searchParams.get("aweme_id") ||
      url.pathname.match(/\/(?:video|note)\/(\d+)/)?.[1] ||
      "";
  }

  function xhsNoteIdFromUrl(value) {
    const url = value instanceof URL ? value : toUrl(value);
    if (!url || platformFromUrl(url.href) !== "xhs") return "";
    return url.pathname.match(/\/explore\/([^/?#]+)/)?.[1] || "";
  }

  function kuaishouPhotoIdFromUrl(value) {
    const url = value instanceof URL ? value : toUrl(value);
    if (!url || platformFromUrl(url.href) !== "kuaishou") return "";
    return url.pathname.match(/\/short-video\/([^/?#]+)/i)?.[1] ||
      url.searchParams.get("photoId") || url.searchParams.get("photo_id") || "";
  }

  function documentUrl(doc) {
    try {
      return doc?.location?.href || globalThis.location?.href || "";
    } catch (error) {
      return "";
    }
  }

  function titleFromDocument(doc, platform) {
    if (!doc?.querySelector) return "";

    const candidates = [
      doc.querySelector('meta[property="og:title"]')?.content,
      doc.querySelector('meta[name="twitter:title"]')?.content,
      doc.querySelector('[data-e2e="video-desc"]')?.innerText,
      doc.querySelector('[data-e2e*="video-desc"]')?.innerText,
      doc.querySelector('[class*="video-desc"]')?.innerText,
      doc.querySelector('[class*="note-title"]')?.innerText,
      doc.querySelector("h1")?.innerText,
      doc.title
    ];

    for (const candidate of candidates) {
      const title = clean(candidate)
        .replace(/\s+-\s+(?:抖音|TikTok|小红书)\s*$/i, "")
        .replace(/^发现更多精彩视频\s*-\s*抖音$/i, "")
        .replace(/^小红书\s*[-|｜]\s*发现真实有趣的生活$/i, "")
        .trim();
      if (!title || title.length > 300) continue;
      if (platform === "douyin" && /^(?:抖音搜索|搜索结果|发现更多精彩视频|.*抖音搜索)$/i.test(title)) continue;
      if (platform === "xhs" && /^(?:小红书|发现真实有趣的生活)$/i.test(title)) continue;
      return title;
    }
    return "";
  }

  function idFromDocument(doc, platform) {
    if (!doc?.querySelectorAll) return "";
    const attributeNames = platform === "douyin"
      ? ["data-aweme-id", "data-video-id", "data-modal-id"]
      : ["data-note-id", "data-id"];

    for (const name of attributeNames) {
      const node = doc.querySelector(`[${name}]`);
      const value = node?.getAttribute(name) || "";
      if (platform === "douyin" ? /^\d+$/.test(value) : value) return value;
    }

    if (platform === "douyin") {
      const canonical = doc.querySelector('link[rel="canonical"]')?.href || "";
      const canonicalId = douyinIdFromUrl(canonical);
      if (canonicalId) return canonicalId;
    }

    return "";
  }

  function getPostInfo(input = "", doc = globalThis.document) {
    const inputUrl = toUrl(input);
    const pageUrl = toUrl(documentUrl(doc));
    const platform = platformFromUrl(inputUrl?.href || pageUrl?.href || "");
    const rawUrl = inputUrl?.href || pageUrl?.href || "";
    if (!platform || !rawUrl) return { platform: "", id: "", url: rawUrl, sourceUrl: "", title: "" };

    if (platform === "douyin") {
      const id = douyinIdFromUrl(inputUrl) || douyinIdFromUrl(pageUrl) || idFromDocument(doc, platform);
      const url = id ? `https://www.douyin.com/video/${id}` : rawUrl;
      const inputIsDirect = Boolean(inputUrl && /\/video\/\d+/.test(inputUrl.pathname));
      return {
        platform,
        id,
        url,
        sourceUrl: id && !inputIsDirect && inputUrl ? inputUrl.href : "",
        title: titleFromDocument(doc, platform)
      };
    }

    if (platform === "kuaishou") {
      const id = kuaishouPhotoIdFromUrl(inputUrl) || kuaishouPhotoIdFromUrl(pageUrl) || idFromDocument(doc, platform);
      return {
        platform,
        id,
        url: id ? `https://www.kuaishou.com/short-video/${encodeURIComponent(id)}` : rawUrl,
        sourceUrl: id && inputUrl && !/\/short-video\//i.test(inputUrl.pathname) ? inputUrl.href : "",
        title: titleFromDocument(doc, platform)
      };
    }

    const noteId = xhsNoteIdFromUrl(inputUrl) || xhsNoteIdFromUrl(pageUrl);
    const url = noteId ? (inputUrl?.href || pageUrl?.href || rawUrl) : rawUrl;
    return {
      platform,
      id: noteId,
      url,
      sourceUrl: "",
      title: titleFromDocument(doc, platform)
    };
  }

  function profileIdentity(value) {
    const raw = clean(value);
    if (!raw || raw === "未知昵称") return "";
    const url = toUrl(raw);
    if (url) return `${url.origin}${url.pathname}`.replace(/\/$/, "");
    return raw;
  }

  // Strict duplicate identity: relative time, region and API ids may change
  // between captures, while the post/user/text tuple remains stable.
  function strictCommentKey(row = {}, postUrl = "") {
    const info = getPostInfo(postUrl || row.postUrl || "", null);
    const platform = info.platform || platformFromUrl(postUrl || row.postUrl || "") || "unknown";
    const postKey = info.id ? `${platform}:${info.id}` : info.url || clean(postUrl || row.postUrl || "");
    const userKey = profileIdentity(row.profile || row.possibleProfile || row.nickname || "");
    const text = clean(row.text || "");
    if (!postKey || !userKey || !text) return "";
    return `${postKey}:strict:${userKey}\u001f${text}`;
  }

  globalThis.CommentFilterPostUtils = {
    clean,
    platformFromUrl,
    douyinIdFromUrl,
    xhsNoteIdFromUrl,
    kuaishouPhotoIdFromUrl,
    getPostInfo,
    strictCommentKey,
    normalizePostUrl(value) {
      return getPostInfo(value, null).url || "";
    }
  };
})();
