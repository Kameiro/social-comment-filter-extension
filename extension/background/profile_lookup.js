(() => {
  if (globalThis.CommentFilterProfileLookup) return;
  const ttl = 24 * 60 * 60 * 1000;
  const cacheKey = "profileLookupCacheV2";

  function identity(value) {
    try {
      const url = new URL(value);
      const id = url.pathname.match(/^\/user\/(MS4[\w-]+)\/?$/)?.[1];
      return url.origin === "https://www.douyin.com" && id ? `douyin:${id}` : "";
    } catch { return ""; }
  }

  function cachedInfo(cache, url, now = Date.now()) {
    const entry = cache?.[identity(url)];
    const elapsed = now - Number(entry?.checkedAt);
    return entry?.info && elapsed >= 0 && elapsed < ttl ? entry.info : null;
  }

  function needsLookup(row, cache, now = Date.now()) {
    const info = cachedInfo(cache, row.profile, now);
    if (!info) return !row.gender || row.gender === "未知" || !row.profileAge || !row.profileLocation;
    // An unresolved gender is not a completed profile check. The platform can
    // expose age/location in the API first and render the gender icon later,
    // so a later task must be allowed to retry it.
    if (!row.gender || row.gender === "未知" || !info.gender || info.gender === "未知") return true;
    return (info.gender !== "未知" && (!row.gender || row.gender === "未知")) ||
      Boolean(info.profileAge && info.profileAge !== row.profileAge) ||
      Boolean(info.profileLocation && info.profileLocation !== row.profileLocation);
  }

  function create({ storage, readDirect, readRendered, now = () => Date.now(), maxEntries = 1500 }) {
    const pending = new Map();
    let cacheWrites = Promise.resolve();
    async function store(key, info) {
      // Serialize read-modify-write to retain simultaneous lookups for different users.
      cacheWrites = cacheWrites.catch(() => {}).then(async () => {
        const state = await storage.get({ [cacheKey]: {} });
        const entries = { ...state[cacheKey], [key]: { checkedAt: now(), info } };
        const sorted = Object.entries(entries).filter(([, entry]) => now() - entry.checkedAt < ttl)
          .sort((a, b) => b[1].checkedAt - a[1].checkedAt).slice(0, maxEntries);
        await storage.set({ [cacheKey]: Object.fromEntries(sorted) });
      });
      await cacheWrites;
    }
    async function read(url, options = {}) {
      const key = identity(url);
      if (!key) return readRendered(url);
      if (pending.has(key)) return pending.get(key);
      const operation = (async () => {
        const state = await storage.get({ [cacheKey]: {} });
        const cached = cachedInfo(state[cacheKey], url, now());
        // Never accept a cached unknown gender as the final answer. It is
        // common for the API response to arrive before the profile SVG.
        if (cached && cached.gender !== "未知") return { ...cached };
        let info;
        try {
          info = await readDirect(url, options);
          // A worker can successfully load a profile while missing a dynamic
          // gender icon. Give the current browser session a chance to read
          // the rendered DOM before accepting an unknown result.
          if (info?.gender === "未知" && typeof readRendered === "function") {
            try {
              const rendered = await readRendered(url, options);
              info = {
                ...info,
                ...rendered,
                gender: rendered?.gender && rendered.gender !== "未知" ? rendered.gender : info.gender,
                profileAge: info.profileAge || rendered?.profileAge || "",
                profileLocation: info.profileLocation || rendered?.profileLocation || ""
              };
            } catch {}
          }
        } catch { info = await readRendered(url, options); }
        if (!info || !["男", "女", "未知"].includes(info.gender)) throw new Error("主页没有返回有效资料。");
        // Do not persist an unresolved gender. Age/location can still be
        // written to the row, while the next task gets another chance to read
        // the profile icon.
        if (info.gender !== "未知") {
          await store(key, info);
        }
        return info;
      })();
      pending.set(key, operation);
      try { return await operation; }
      finally { pending.delete(key); }
    }
    return { read };
  }

  globalThis.CommentFilterProfileLookup = { create, identity, cachedInfo, needsLookup, cacheKey, ttl };
})();
