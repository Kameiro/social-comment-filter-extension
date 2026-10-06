(() => {
  const DB_NAME = "comment-filter-local-database";
  const DB_VERSION = 3;
  const MIGRATION_KEY = "commentDatabaseMigratedV1";
  const URL_MIGRATION_KEY = "commentDatabasePostUrlsMigratedV2";
  const TASK_ASSOCIATION_MIGRATION_KEY = "commentDatabaseTaskAssociationsV1";
  const NUMBERING_MIGRATION_KEY = "commentDatabaseNumberingV1";
  const STRICT_DEDUP_MIGRATION_KEY = "commentDatabaseStrictDedupV1";
  let dbPromise;
  let postWriteQueue = Promise.resolve();

  function queuePostWrite(operation) {
    const next = postWriteQueue.then(operation);
    postWriteQueue = next.catch(() => {});
    return next;
  }

  function openDatabase() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains("posts")) {
          const posts = db.createObjectStore("posts", { keyPath: "id" });
          posts.createIndex("byPlatform", "platform");
          posts.createIndex("byLastScanned", "lastScannedAt");
        }

        if (!db.objectStoreNames.contains("comments")) {
          const comments = db.createObjectStore("comments", { keyPath: "id" });
          comments.createIndex("byPostId", "postId");
          comments.createIndex("byProfile", "profile");
          comments.createIndex("byGender", "gender");
          comments.createIndex("byCollectedAt", "collectedAt");
        }

        if (!db.objectStoreNames.contains("users")) {
          const users = db.createObjectStore("users", { keyPath: "profile" });
          users.createIndex("byGender", "gender");
          users.createIndex("byNickname", "nickname");
        }

        if (!db.objectStoreNames.contains("discoveredPosts")) {
          const discovered = db.createObjectStore("discoveredPosts", { keyPath: "id" });
          discovered.createIndex("byPlatform", "platform");
          discovered.createIndex("byDiscoveredAt", "discoveredAt");
          discovered.createIndex("byKeyword", "keyword");
        }

        if (!db.objectStoreNames.contains("analysisScopes")) {
          const scopes = db.createObjectStore("analysisScopes", { keyPath: "id" });
          scopes.createIndex("byUpdatedAt", "updatedAt");
        }
      };
      request.onsuccess = () => {
        const db = request.result;
        db.onversionchange = () => {
          db.close();
          dbPromise = null;
        };
        resolve(db);
      };
      request.onerror = () => reject(request.error || new Error("打开本地数据库失败。"));
      request.onblocked = () => reject(new Error("数据库正在被另一个页面升级，请关闭旧的数据库页面后重试。"));
    });
    return dbPromise;
  }

  function platformFromUrl(url) {
    if (globalThis.CommentFilterPostUtils?.platformFromUrl) {
      return globalThis.CommentFilterPostUtils.platformFromUrl(url) || "unknown";
    }
    if (/xiaohongshu\.com/i.test(url || "")) return "xhs";
    if (/douyin\.com/i.test(url || "")) return "douyin";
    if (/kuaishou\.com/i.test(url || "")) return "kuaishou";
    return "unknown";
  }

  function postIdentity(url) {
    const info = globalThis.CommentFilterPostUtils?.getPostInfo?.(url, null);
    const normalizedUrl = info?.url || url;
    const platform = platformFromUrl(normalizedUrl);
    try {
      const parsed = new URL(normalizedUrl);
      if (platform === "xhs") {
        const noteId = parsed.pathname.match(/\/explore\/([^/]+)/)?.[1];
        if (noteId) return `${platform}:${noteId}`;
      }
      if (platform === "douyin") {
        const modalId = parsed.searchParams.get("modal_id");
        const videoId = parsed.pathname.match(/\/(?:video|note)\/(\d+)/)?.[1];
        if (modalId || videoId) return `${platform}:${modalId || videoId}`;
      }
      if (platform === "kuaishou") {
        const photoId = parsed.pathname.match(/\/short-video\/([^/]+)/i)?.[1] || parsed.searchParams.get("photoId");
        if (photoId) return `${platform}:${photoId}`;
      }
      return `${platform}:${parsed.origin}${parsed.pathname}`;
    } catch (error) {
      return `${platform}:${normalizedUrl}`;
    }
  }

  function commentIdentity(row, postId) {
    const strictKey = globalThis.CommentFilterPostUtils?.strictCommentKey?.(row, row.postUrl || "");
    if (strictKey) return strictKey;
    const stableId = row.commentId || row.cid || "";
    if (stableId) return `${postId}:comment:${stableId}`;
    return `${postId}:row:${[
      row.profile || row.possibleProfile || row.nickname || "",
      row.text || ""
    ].join("\u001f")}`;
  }

  function knownValue(incoming, existing, fallback = "") {
    if (incoming && incoming !== "未知" && incoming !== "0") return incoming;
    return existing || incoming || fallback;
  }

  function knownNumber(incoming, existing, fallback = 0) {
    const value = Number(incoming);
    if (Number.isFinite(value) && value > 0) return value;
    const previous = Number(existing);
    return Number.isFinite(previous) && previous > 0 ? previous : fallback;
  }

  function taskIdsForRow(value) {
    const values = Array.isArray(value) ? value : [];
    return [...new Set(values.map(item => String(item || "").trim()).filter(Boolean))];
  }

  function positiveInteger(value) {
    const number = Number(value);
    return Number.isInteger(number) && number > 0 ? number : 0;
  }

  function nextNumber(records, field) {
    return records.reduce((highest, record) => Math.max(highest, positiveInteger(record?.[field])), 0) + 1;
  }

  function stableRecordOrder(a, b) {
    return Number(a.firstSeenAt || a.lastScannedAt || 0) - Number(b.firstSeenAt || b.lastScannedAt || 0) ||
      Number(a.lastScannedAt || 0) - Number(b.lastScannedAt || 0) ||
      String(a.id || "").localeCompare(String(b.id || ""));
  }

  function mergeComment(existing, incoming) {
    return {
      ...existing,
      ...incoming,
      nickname: knownValue(incoming.nickname, existing?.nickname, "未知昵称"),
      gender: knownValue(incoming.gender, existing?.gender, "未知"),
      profileAge: knownValue(incoming.profileAge, existing?.profileAge),
      profileLocation: knownValue(incoming.profileLocation, existing?.profileLocation),
      ipRegion: knownValue(incoming.ipRegion, existing?.ipRegion),
      profile: knownValue(incoming.profile, existing?.profile),
      possibleProfile: knownValue(incoming.possibleProfile, existing?.possibleProfile),
      likeCount: knownValue(incoming.likeCount, existing?.likeCount, "0"),
      replyCount: knownValue(incoming.replyCount, existing?.replyCount, "0"),
      filterTaskIds: taskIdsForRow(existing?.filterTaskIds).concat(taskIdsForRow(incoming.filterTaskIds)).filter((value, index, values) => values.indexOf(value) === index)
    };
  }

  function mergeUser(existing, incoming, commentDelta, now) {
    return {
      profile: incoming.profile,
      nickname: knownValue(incoming.nickname, existing?.nickname, "未知昵称"),
      gender: knownValue(incoming.gender, existing?.gender, "未知"),
      profileAge: knownValue(incoming.profileAge, existing?.profileAge),
      profileLocation: knownValue(incoming.profileLocation, existing?.profileLocation),
      ipRegion: knownValue(incoming.ipRegion, existing?.ipRegion),
      profileUid: knownValue(incoming.profileUid, existing?.profileUid),
      profileSecUid: knownValue(incoming.profileSecUid, existing?.profileSecUid),
      followerCount: knownNumber(incoming.followerCount, existing?.followerCount),
      followingCount: knownNumber(incoming.followingCount, existing?.followingCount),
      totalFavorited: knownNumber(incoming.totalFavorited, existing?.totalFavorited),
      awemeCount: knownNumber(incoming.awemeCount, existing?.awemeCount),
      commentCount: Math.max(0, Number(existing?.commentCount || 0) + commentDelta),
      firstSeenAt: existing?.firstSeenAt || now,
      lastSeenAt: now
    };
  }

  function normalizeFilterTask(task = {}) {
    const filters = task.filters || {};
    return {
      id: String(task.id || `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`),
      taskNumber: positiveInteger(task.taskNumber),
      postId: String(task.postId || ""),
      postNumber: positiveInteger(task.postNumber),
      filters: {
        keyword: String(filters.keyword || ""),
        excludeKeyword: String(filters.excludeKeyword || ""),
        region: String(filters.region || ""),
        matchMode: filters.matchMode === "any" ? "any" : "all",
        relativeDays: Number(filters.relativeDays || 0),
        genderFilter: ["male", "female", "all"].includes(filters.genderFilter) ? filters.genderFilter : "all",
        targetGenderCount: Math.max(0, Number(filters.targetGenderCount || filters.targetFemaleCount || 0)),
        scrollLimit: Math.max(0, Number(filters.scrollLimit || 0)),
        scanMode: filters.scanMode === "replace" ? "replace" : "append"
      },
      matchedCount: Math.max(0, Number(task.matchedCount || 0)),
      status: String(task.status || "completed"),
      completionState: task.completionState === "partial" ? "partial" : "complete",
      profileStatus: String(task.profileStatus || "not-required"),
      profileEnrichedCount: Math.max(0, Number(task.profileEnrichedCount || 0)),
      profileTargetCount: Math.max(0, Number(task.profileTargetCount || 0)),
      profileErrors: Array.isArray(task.profileErrors) ? task.profileErrors.slice(0, 20).map(error => ({
        url: String(error?.url || ""),
        message: String(error?.message || "主页资料核验失败")
      })) : [],
      message: String(task.message || ""),
      pagination: task.pagination && typeof task.pagination === "object" ? {
        postUrl: String(task.pagination.postUrl || ""),
        platformCommentCount: Math.max(0, Number(task.pagination.platformCommentCount || 0)),
        apiPagesRead: Math.max(0, Number(task.pagination.apiPagesRead || 0)),
        rawRowsRead: Math.max(0, Number(task.pagination.rawRowsRead || 0)),
        uniqueRowsRead: Math.max(0, Number(task.pagination.uniqueRowsRead || 0)),
        hasMoreAtEnd: task.pagination.hasMoreAtEnd === null || task.pagination.hasMoreAtEnd === undefined ? null : Boolean(task.pagination.hasMoreAtEnd),
        paginationComplete: task.pagination.paginationComplete === true,
        stopReason: String(task.pagination.stopReason || ""),
        lastCursor: String(task.pagination.lastCursor || ""),
        replyGroupsTotal: Math.max(0, Number(task.pagination.replyGroupsTotal || 0)),
        replyGroupsCompleted: Math.max(0, Number(task.pagination.replyGroupsCompleted || 0)),
        replyGroupsFailed: Math.max(0, Number(task.pagination.replyGroupsFailed || 0)),
        capturedAt: Number(task.pagination.capturedAt || 0)
      } : null,
      startedAt: Number(task.startedAt || Date.now()),
      completedAt: Number(task.completedAt || Date.now())
    };
  }

  function withFilterTask(post, task) {
    if (!task?.id) return post;
    const nextTask = normalizeFilterTask(task);
    const tasks = Array.isArray(post?.filterTasks) ? post.filterTasks : [];
    const index = tasks.findIndex(item => item.id === nextTask.id);
    if (index >= 0) tasks[index] = { ...tasks[index], ...nextTask, filters: { ...tasks[index].filters, ...nextTask.filters } };
    else tasks.push(nextTask);
    tasks.sort((a, b) => Number(b.completedAt || 0) - Number(a.completedAt || 0));
    return { ...post, filterTasks: tasks.slice(0, 100) };
  }

  function recordPostScan(url, title = "", sourceUrl = "", task = null) {
    return queuePostWrite(() => recordPostScanInternal(url, title, sourceUrl, task));
  }

  function recordPostScanInternal(url, title = "", sourceUrl = "", task = null) {
    const info = globalThis.CommentFilterPostUtils?.getPostInfo?.(url, null) || {};
    const normalizedUrl = info.url || url;
    const normalizedSourceUrl = sourceUrl || info.sourceUrl || "";
    const id = postIdentity(normalizedUrl);
    const platform = platformFromUrl(normalizedUrl);
    return openDatabase().then(db => new Promise((resolve, reject) => {
      const transaction = db.transaction("posts", "readwrite");
      const store = transaction.objectStore("posts");
      const request = store.get(id);
      const allRequest = store.getAll();
      let existing;
      let allPosts;
      let loaded = 0;
      const save = () => {
        if (loaded < 2) return;
        const now = Date.now();
        const postNumber = positiveInteger(existing?.postNumber) || nextNumber(allPosts, "postNumber");
        let nextPost = {
          ...existing,
          id,
          postNumber,
          url: normalizedUrl,
          sourceUrl: normalizedSourceUrl || existing?.sourceUrl || "",
          title: title || existing?.title || "",
          platform,
          commentCount: Number(existing?.commentCount || 0),
          platformCommentCount: Math.max(0, Number(task?.pagination?.platformCommentCount || existing?.platformCommentCount || 0)),
          pagination: task?.pagination || existing?.pagination || null,
          firstSeenAt: existing?.firstSeenAt || now,
          lastScannedAt: now,
          filterTasks: Array.isArray(existing?.filterTasks) ? existing.filterTasks : []
        };
        nextPost = withFilterTask(nextPost, task ? { ...task, postId: id, postNumber } : null);
        store.put(nextPost);
      };
      request.onsuccess = () => {
        existing = request.result;
        loaded += 1;
        save();
      };
      allRequest.onsuccess = () => {
        allPosts = allRequest.result || [];
        loaded += 1;
        save();
      };
      transaction.oncomplete = () => resolve(id);
      transaction.onerror = () => reject(transaction.error || new Error("记录帖子失败。"));
    }));
  }

  async function updateUserProfile(profile, info = {}) {
    if (!profile) return 0;
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(["comments", "users"], "readwrite");
      const comments = transaction.objectStore("comments");
      const users = transaction.objectStore("users");
      const index = comments.index("byProfile");
      const request = index.getAll(profile);
      request.onsuccess = () => {
        const rows = request.result || [];
        const first = rows[0] || {};
        const now = Date.now();
        const user = users.get(profile);
        user.onsuccess = () => {
          users.put({
            ...mergeUser(user.result, {
              profile,
              nickname: info.profileNickname || first.nickname || user.result?.nickname || "未知昵称",
              gender: info.gender,
              profileAge: info.profileAge,
              profileLocation: info.profileLocation,
              ipRegion: first.ipRegion || user.result?.ipRegion,
              profileUid: info.profileUid,
              profileSecUid: info.profileSecUid,
              followerCount: info.profileStats?.followerCount,
              followingCount: info.profileStats?.followingCount,
              totalFavorited: info.profileStats?.totalFavorited,
              awemeCount: info.profileStats?.awemeCount
            }, 0, now),
            commentCount: rows.length || Number(user.result?.commentCount || 0)
          });
        };
        for (const row of rows) {
          comments.put({
            ...row,
            nickname: info.profileNickname || row.nickname || "未知昵称",
            gender: knownValue(info.gender, row.gender, "未知"),
            profileAge: knownValue(info.profileAge, row.profileAge),
            profileLocation: knownValue(info.profileLocation, row.profileLocation),
            updatedAt: now
          });
        }
      };
      transaction.oncomplete = () => resolve(request.result?.length || 0);
      transaction.onerror = () => reject(transaction.error || new Error("更新用户资料失败。"));
    });
  }

  async function updateUserGender(profile, gender) {
    const normalizedGender = ["男", "女", "未知"].includes(gender) ? gender : "未知";
    if (!profile) throw new Error("缺少用户主页链接。");
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(["comments", "users"], "readwrite");
      const comments = transaction.objectStore("comments");
      const users = transaction.objectStore("users");
      const commentRequest = comments.index("byProfile").getAll(profile);
      const userRequest = users.get(profile);
      const now = Date.now();

      commentRequest.onsuccess = () => {
        for (const row of commentRequest.result || []) {
          comments.put({ ...row, gender: normalizedGender, updatedAt: now });
        }
      };
      userRequest.onsuccess = () => {
        if (userRequest.result) users.put({ ...userRequest.result, gender: normalizedGender, lastSeenAt: now });
      };
      transaction.oncomplete = () => resolve({ profile, gender: normalizedGender });
      transaction.onerror = () => reject(transaction.error || new Error("更新用户性别失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("更新用户性别已中断。"));
    });
  }

  function toRecords(rows) {
    const deduped = new Map();
    for (const source of rows || []) {
      const inputPostUrl = String(source?.postUrl || "");
      if (!inputPostUrl || !source?.text) continue;
      const info = globalThis.CommentFilterPostUtils?.getPostInfo?.(inputPostUrl, null) || {};
      const postUrl = info.url || inputPostUrl;
      const postId = postIdentity(postUrl);
      const id = commentIdentity(source, postId);
      const { _days, _apiKey, ...cleanRow } = source;
      const incoming = {
        ...cleanRow,
        id,
        postId,
        postUrl,
        sourcePostUrl: source.sourcePostUrl || info.sourceUrl || "",
        platform: platformFromUrl(postUrl),
        collectedAt: Number(source.collectedAt || Date.now()),
        updatedAt: Date.now(),
        filterTaskIds: taskIdsForRow(source.filterTaskIds)
      };
      deduped.set(id, mergeComment(deduped.get(id), incoming));
    }
    return [...deduped.values()];
  }

  function upsertRows(rows, options = {}) {
    return queuePostWrite(() => upsertRowsInternal(rows, options));
  }

  function upsertRowsInternal(rows, options = {}) {
    const records = toRecords(rows);
    if (!records.length) return Promise.resolve(0);

    return openDatabase().then(db => new Promise((resolve, reject) => {
      const transaction = db.transaction(["posts", "comments", "users"], "readwrite");
      const posts = transaction.objectStore("posts");
      const comments = transaction.objectStore("comments");
      const users = transaction.objectStore("users");
      const existingComments = new Map();
      let allPosts = [];
      let postsRead = false;
      let pendingReads = records.length;
      const now = Date.now();

      transaction.oncomplete = () => resolve(records.length);
      transaction.onerror = () => reject(transaction.error || new Error("写入本地数据库失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("写入本地数据库已中断。"));

      const finishReads = () => {
        if (!postsRead || pendingReads !== 0) return;
        const postUpdates = new Map();
        const userUpdates = new Map();

        for (const record of records) {
          const existing = existingComments.get(record.id);
          comments.put({
            ...mergeComment(existing, record),
            collectedAt: existing?.collectedAt || record.collectedAt || now,
            updatedAt: now
          });

          const post = postUpdates.get(record.postId) || {
            url: record.postUrl,
            sourceUrl: record.sourcePostUrl || "",
            platform: record.platform,
            newCount: 0,
            title: ""
          };
          if (!existing) post.newCount += 1;
          post.title = record.postTitle || record.title || post.title;
          post.sourceUrl = record.sourcePostUrl || post.sourceUrl || "";
          postUpdates.set(record.postId, post);

          if (record.profile) {
            const user = userUpdates.get(record.profile) || {
              profile: record.profile,
              nickname: record.nickname,
              gender: record.gender,
              profileAge: record.profileAge,
              profileLocation: record.profileLocation,
              ipRegion: record.ipRegion,
              commentDelta: 0
            };
            if (!existing) user.commentDelta += 1;
            userUpdates.set(record.profile, user);
          }
        }

        let nextPostNumber = nextNumber(allPosts, "postNumber");
        for (const [postId, incoming] of postUpdates) {
          const request = posts.get(postId);
          request.onsuccess = () => {
            const existing = request.result;
            const postNumber = positiveInteger(existing?.postNumber) || nextPostNumber++;
            posts.put({
              ...existing,
              id: postId,
              postNumber,
              url: incoming.url || existing?.url || "",
              sourceUrl: incoming.sourceUrl || existing?.sourceUrl || "",
              title: incoming.title || existing?.title || "",
              platform: incoming.platform || existing?.platform || "unknown",
              commentCount: Math.max(0, Number(existing?.commentCount || 0) + incoming.newCount),
              firstSeenAt: existing?.firstSeenAt || now,
              lastScannedAt: now
            });
          };
        }

        for (const [profile, incoming] of userUpdates) {
          const request = users.get(profile);
          request.onsuccess = () => users.put(mergeUser(request.result, incoming, incoming.commentDelta, now));
        }
      };

      const allPostsRequest = posts.getAll();
      allPostsRequest.onsuccess = () => {
        allPosts = allPostsRequest.result || [];
        postsRead = true;
        finishReads();
      };

      for (const record of records) {
        const request = comments.get(record.id);
        request.onsuccess = () => {
          existingComments.set(record.id, request.result || null);
          pendingReads -= 1;
          finishReads();
        };
      }
    }));
  }

  function getAll(storeName) {
    return openDatabase().then(db => new Promise((resolve, reject) => {
      const transaction = db.transaction(storeName, "readonly");
      const request = transaction.objectStore(storeName).getAll();
      request.onsuccess = () => resolve(request.result || []);
      request.onerror = () => reject(request.error || new Error("读取本地数据库失败。"));
    }));
  }

  function stringList(value) {
    const values = Array.isArray(value) ? value : String(value || "").split(/[\n,，、]+/);
    return [...new Set(values.map(item => String(item || "").trim()).filter(Boolean))];
  }

  function normalizeAnalysisScope(source = {}) {
    const now = Date.now();
    const filters = source.filters || {};
    const platform = ["all", "douyin", "xhs", "kuaishou"].includes(filters.platform) ? filters.platform : "all";
    const genderFilter = ["all", "male", "female"].includes(filters.genderFilter) ? filters.genderFilter : "all";
    const rawDecisions = source.userDecisions && typeof source.userDecisions === "object" ? source.userDecisions : {};
    const userDecisions = Object.fromEntries(Object.entries(rawDecisions).map(([profile, value]) => [String(profile), {
      status: ["pending", "candidate", "excluded", "contacted"].includes(value?.status) ? value.status : "pending",
      note: String(value?.note || "").slice(0, 500),
      updatedAt: Number(value?.updatedAt || now)
    }]));
    return {
      id: String(source.id || `scope-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`),
      name: String(source.name || "未命名分析范围").trim().slice(0, 120) || "未命名分析范围",
      filters: {
        includeKeywords: stringList(filters.includeKeywords),
        excludeKeywords: stringList(filters.excludeKeywords),
        region: String(filters.region || "").trim(),
        platform,
        matchMode: filters.matchMode === "any" ? "any" : "all",
        genderFilter,
        relativeDays: Math.max(0, Number(filters.relativeDays || 0)),
        excludePhrases: stringList(filters.excludePhrases),
        selectedPostIds: stringList(filters.selectedPostIds)
      },
      userDecisions,
      createdAt: Number(source.createdAt || now),
      updatedAt: Number(source.updatedAt || now)
    };
  }

  async function getAnalysisScopes() {
    const scopes = await getAll("analysisScopes");
    return scopes.map(normalizeAnalysisScope).sort((a, b) => Number(b.updatedAt || 0) - Number(a.updatedAt || 0));
  }

  async function saveAnalysisScope(source = {}) {
    const scope = normalizeAnalysisScope({ ...source, updatedAt: Date.now() });
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction("analysisScopes", "readwrite");
      transaction.objectStore("analysisScopes").put(scope);
      transaction.oncomplete = () => resolve(scope);
      transaction.onerror = () => reject(transaction.error || new Error("保存分析范围失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("保存分析范围已中断。"));
    });
  }

  async function setAnalysisUserDecision(scopeId, profile, status, note = "") {
    const normalizedStatus = ["pending", "candidate", "excluded", "contacted"].includes(status) ? status : "pending";
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction("analysisScopes", "readwrite");
      const store = transaction.objectStore("analysisScopes");
      const request = store.get(String(scopeId || ""));
      request.onsuccess = () => {
        const existing = normalizeAnalysisScope(request.result || { id: scopeId });
        existing.userDecisions[String(profile || "")] = {
          status: normalizedStatus,
          note: String(note || "").slice(0, 500),
          updatedAt: Date.now()
        };
        existing.updatedAt = Date.now();
        store.put(existing);
      };
      transaction.oncomplete = () => resolve({ scopeId, profile, status: normalizedStatus });
      transaction.onerror = () => reject(transaction.error || new Error("更新用户候选状态失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("更新用户候选状态已中断。"));
    });
  }

  async function deleteAnalysisScope(scopeId) {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction("analysisScopes", "readwrite");
      transaction.objectStore("analysisScopes").delete(String(scopeId || ""));
      transaction.oncomplete = () => resolve(true);
      transaction.onerror = () => reject(transaction.error || new Error("删除分析范围失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("删除分析范围已中断。"));
    });
  }

  async function getDashboardData() {
    await ensureLegacyMigration();
    const [posts, comments, users, discoveredPosts, analysisScopes] = await Promise.all([
      getAll("posts"), getAll("comments"), getAll("users"), getAll("discoveredPosts"), getAnalysisScopes()
    ]);
    posts.sort((a, b) => Number(b.lastScannedAt || 0) - Number(a.lastScannedAt || 0));
    comments.sort((a, b) => Number(b.collectedAt || 0) - Number(a.collectedAt || 0));
    users.sort((a, b) => Number(b.lastSeenAt || 0) - Number(a.lastSeenAt || 0));
    const discoveredById = new Map();
    for (const rawPost of discoveredPosts) {
      const normalized = normalizeDiscoveredPost(rawPost);
      if (!normalized) continue;
      discoveredById.set(normalized.id, mergeDiscoveredPost(discoveredById.get(normalized.id), normalized));
    }
    for (const post of posts) {
      const discovered = normalizeDiscoveredPost({
        id: post.id,
        platform: post.platform,
        url: post.url,
        title: post.title,
        discoveredAt: post.firstSeenAt,
        lastSeenAt: post.lastScannedAt
      });
      if (!discovered) continue;
      const existing = discoveredById.get(discovered.id);
      discoveredById.set(discovered.id, mergeDiscoveredPost(existing, discovered));
    }
    const aggregatedDiscoveredPosts = [...discoveredById.values()];
    aggregatedDiscoveredPosts.sort((a, b) => Number(b.lastSeenAt || b.discoveredAt || 0) - Number(a.lastSeenAt || a.discoveredAt || 0));
    return { posts, comments, users, discoveredPosts: aggregatedDiscoveredPosts, analysisScopes };
  }

  function normalizeDiscoveredPost(source = {}) {
    const rawUrl = String(source.url || "").trim();
    const info = globalThis.CommentFilterPostUtils?.getPostInfo?.(rawUrl, null) || {};
    const url = info.url || rawUrl;
    const platform = String(source.platform || info.platform || platformFromUrl(url));
    const rawId = String(source.id || info.id || postIdentity(url)).trim();
    const id = rawId.replace(new RegExp(`^${platform}:`, "i"), "");
    if (!url || !id || !platform) return null;
    return {
      id: `${platform}:${id}`,
      platform,
      url,
      title: String(source.title || "").trim(),
      nickname: String(source.nickname || "").trim(),
      authorId: String(source.authorId || "").trim(),
      authorSecUid: String(source.authorSecUid || "").trim(),
      authorUrl: String(source.authorUrl || "").trim(),
      xsecToken: String(source.xsecToken || "").trim(),
      xsecSource: String(source.xsecSource || "").trim(),
      diggCount: Math.max(0, Number(source.diggCount || 0)),
      commentCount: Math.max(0, Number(source.commentCount || 0)),
      shareCount: Math.max(0, Number(source.shareCount || 0)),
      collectCount: Math.max(0, Number(source.collectCount || 0)),
      createTime: Number(source.createTime || 0),
      createTimeText: String(source.createTimeText || ""),
      keyword: String(source.keyword || "").trim(),
      keywords: [...new Set((Array.isArray(source.keywords) ? source.keywords : [source.keyword]).map(value => String(value || "").trim()).filter(Boolean))],
      statsSource: String(source.statsSource || ""),
      statsCapturedAt: Number(source.statsCapturedAt || 0),
      discoveredAt: Number(source.discoveredAt || Date.now()),
      lastSeenAt: Number(source.lastSeenAt || source.discoveredAt || Date.now())
    };
  }

  function mergeDiscoveredPost(existing = {}, record = {}) {
    const existingStatsAt = Number(existing.statsCapturedAt || 0);
    const recordStatsAt = Number(record.statsCapturedAt || 0);
    const useRecordStats = recordStatsAt > 0 && recordStatsAt >= existingStatsAt;
    const stats = useRecordStats ? record : existingStatsAt > 0 ? existing : null;
    return {
      ...existing,
      ...record,
      title: record.title || existing.title || "",
      nickname: record.nickname || existing.nickname || "",
      authorId: record.authorId || existing.authorId || "",
      authorSecUid: record.authorSecUid || existing.authorSecUid || "",
      authorUrl: record.authorUrl || existing.authorUrl || "",
      xsecToken: record.xsecToken || existing.xsecToken || "",
      xsecSource: record.xsecSource || existing.xsecSource || "",
      createTime: Number(record.createTime || existing.createTime || 0),
      createTimeText: record.createTimeText || existing.createTimeText || "",
      keyword: record.keyword || existing.keyword || "",
      keywords: [...new Set([...(Array.isArray(existing.keywords) ? existing.keywords : existing.keyword ? [existing.keyword] : []), ...(record.keywords || []), record.keyword].filter(Boolean))],
      statsSource: stats?.statsSource || "",
      statsCapturedAt: Number(stats?.statsCapturedAt || 0),
      diggCount: Math.max(0, Number(stats?.diggCount || 0)),
      commentCount: Math.max(0, Number(stats?.commentCount || 0)),
      shareCount: Math.max(0, Number(stats?.shareCount || 0)),
      collectCount: Math.max(0, Number(stats?.collectCount || 0)),
      discoveredAt: Math.min(Number(existing.discoveredAt || record.discoveredAt || Date.now()), Number(record.discoveredAt || existing.discoveredAt || Date.now())),
      lastSeenAt: Math.max(Number(existing.lastSeenAt || 0), Number(record.lastSeenAt || 0))
    };
  }

  async function upsertDiscoveredPosts(records = []) {
    const normalized = records.map(normalizeDiscoveredPost).filter(Boolean);
    if (!normalized.length) return 0;
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction("discoveredPosts", "readwrite");
      const store = transaction.objectStore("discoveredPosts");
      for (const record of normalized) {
        const request = store.get(record.id);
        request.onsuccess = () => {
          const existing = request.result || {};
          store.put(mergeDiscoveredPost(existing, record));
        };
      }
      transaction.oncomplete = () => resolve(normalized.length);
      transaction.onerror = () => reject(transaction.error || new Error("保存发现帖子失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("保存发现帖子已中断。"));
    });
  }

  async function migrateStoredPostUrls() {
    const db = await openDatabase();
    const snapshot = await new Promise((resolve, reject) => {
      const transaction = db.transaction(["posts", "comments"], "readonly");
      const postsRequest = transaction.objectStore("posts").getAll();
      const commentsRequest = transaction.objectStore("comments").getAll();
      let posts;
      let comments;
      postsRequest.onsuccess = () => { posts = postsRequest.result || []; };
      commentsRequest.onsuccess = () => { comments = commentsRequest.result || []; };
      transaction.oncomplete = () => resolve({ posts: posts || [], comments: comments || [] });
      transaction.onerror = () => reject(transaction.error || new Error("读取帖子链接失败。"));
    });

    const normalizedPostsById = new Map();
    for (const post of snapshot.posts) {
      const info = globalThis.CommentFilterPostUtils?.getPostInfo?.(post.url || "", null) || {};
      const url = info.url || post.url || "";
      const normalized = {
        ...post,
        id: postIdentity(url),
        url,
        sourceUrl: post.sourceUrl || info.sourceUrl || ""
      };
      const existing = normalizedPostsById.get(normalized.id);
      normalizedPostsById.set(normalized.id, {
        ...existing,
        ...normalized,
        title: normalized.title || existing?.title || "",
        sourceUrl: normalized.sourceUrl || existing?.sourceUrl || "",
        commentCount: Math.max(Number(existing?.commentCount || 0), Number(normalized.commentCount || 0)),
        firstSeenAt: Math.min(Number(existing?.firstSeenAt || Infinity), Number(normalized.firstSeenAt || Infinity)),
        lastScannedAt: Math.max(Number(existing?.lastScannedAt || 0), Number(normalized.lastScannedAt || 0))
      });
    }
    const normalizedPosts = [...normalizedPostsById.values()].map(post => ({
      ...post,
      firstSeenAt: Number.isFinite(post.firstSeenAt) ? post.firstSeenAt : Date.now()
    }));

    const normalizedCommentsById = new Map();
    const postTitles = new Map(normalizedPosts.map(post => [post.id, post.title || ""]));
    for (const row of snapshot.comments) {
      const info = globalThis.CommentFilterPostUtils?.getPostInfo?.(row.postUrl || "", null) || {};
      const postUrl = info.url || row.postUrl || "";
      const postId = postIdentity(postUrl);
      const next = {
        ...row,
        id: commentIdentity(row, postId),
        postId,
        postUrl,
        postTitle: row.postTitle || postTitles.get(postId) || "",
        sourcePostUrl: row.sourcePostUrl || info.sourceUrl || ""
      };
      normalizedCommentsById.set(next.id, mergeComment(normalizedCommentsById.get(next.id), next));
    }
    const normalizedComments = [...normalizedCommentsById.values()];
    const commentCounts = new Map();
    for (const row of normalizedComments) {
      commentCounts.set(row.postId, (commentCounts.get(row.postId) || 0) + 1);
    }
    for (const post of normalizedPosts) {
      post.commentCount = commentCounts.get(post.id) || 0;
    }
    const changed = normalizedPosts.length !== snapshot.posts.length ||
      normalizedPosts.some((post, index) => JSON.stringify(post) !== JSON.stringify(snapshot.posts[index])) ||
      normalizedComments.length !== snapshot.comments.length ||
      normalizedComments.some((row, index) => JSON.stringify(row) !== JSON.stringify(snapshot.comments[index]));
    if (!changed) return;

    await new Promise((resolve, reject) => {
      const transaction = db.transaction(["posts", "comments"], "readwrite");
      const posts = transaction.objectStore("posts");
      const comments = transaction.objectStore("comments");
      const nextPostIds = new Set(normalizedPosts.map(post => post.id));
      const nextCommentIds = new Set(normalizedComments.map(row => row.id));
      for (const post of snapshot.posts) {
        if (!nextPostIds.has(post.id)) posts.delete(post.id);
      }
      for (const post of normalizedPosts) posts.put(post);
      for (const row of snapshot.comments) {
        if (!nextCommentIds.has(row.id)) comments.delete(row.id);
      }
      for (const row of normalizedComments) comments.put(row);
      transaction.oncomplete = resolve;
      transaction.onerror = () => reject(transaction.error || new Error("修正帖子链接失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("修正帖子链接已中断。"));
    });
  }

  async function reconcileUserCommentCounts() {
    const [comments, users] = await Promise.all([getAll("comments"), getAll("users")]);
    const counts = new Map();
    for (const row of comments) {
      if (row.profile) counts.set(row.profile, (counts.get(row.profile) || 0) + 1);
    }
    const updates = users.filter(user => Number(user.commentCount || 0) !== Number(counts.get(user.profile) || 0));
    if (!updates.length) return;
    const db = await openDatabase();
    await new Promise((resolve, reject) => {
      const transaction = db.transaction("users", "readwrite");
      const store = transaction.objectStore("users");
      for (const user of updates) store.put({ ...user, commentCount: counts.get(user.profile) || 0 });
      transaction.oncomplete = resolve;
      transaction.onerror = () => reject(transaction.error || new Error("修正用户评论数量失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("修正用户评论数量已中断。"));
    });
  }

  async function migrateTaskAssociations() {
    const db = await openDatabase();
    const snapshot = await new Promise((resolve, reject) => {
      const transaction = db.transaction(["posts", "comments"], "readonly");
      const postsRequest = transaction.objectStore("posts").getAll();
      const commentsRequest = transaction.objectStore("comments").getAll();
      let posts;
      let comments;
      postsRequest.onsuccess = () => { posts = postsRequest.result || []; };
      commentsRequest.onsuccess = () => { comments = commentsRequest.result || []; };
      transaction.oncomplete = () => resolve({ posts: posts || [], comments: comments || [] });
      transaction.onerror = () => reject(transaction.error || new Error("读取任务关联失败。"));
    });
    const updates = [];
    for (const post of snapshot.posts) {
      const tasks = Array.isArray(post.filterTasks) ? post.filterTasks : [];
      const rows = snapshot.comments.filter(row => row.postId === post.id);
      const unassigned = rows.filter(row => !Array.isArray(row.filterTaskIds) || row.filterTaskIds.length === 0);
      const latest = tasks[0];
      if (!latest || !rows.length || unassigned.length !== rows.length) continue;
      const latestIsExactReplacement = latest.filters?.scanMode === "replace" && Number(latest.matchedCount || 0) === rows.length;
      const onlyTaskMatches = tasks.length === 1 && Number(latest.matchedCount || 0) === rows.length;
      if (latestIsExactReplacement || onlyTaskMatches) {
        updates.push(...rows.map(row => ({ ...row, filterTaskIds: [String(latest.id)] })));
      }
    }
    if (!updates.length) return;
    await new Promise((resolve, reject) => {
      const transaction = db.transaction("comments", "readwrite");
      const comments = transaction.objectStore("comments");
      for (const row of updates) comments.put(row);
      transaction.oncomplete = resolve;
      transaction.onerror = () => reject(transaction.error || new Error("保存任务关联失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("保存任务关联已中断。"));
    });
  }

  async function ensurePersistentNumbers() {
    const db = await openDatabase();
    const posts = await getAll("posts");
    const orderedPosts = [...posts].sort(stableRecordOrder);
    const usedPostNumbers = new Set();
    let nextPost = nextNumber(posts, "postNumber");
    const postNumbers = new Map();
    for (const post of orderedPosts) {
      const number = positiveInteger(post.postNumber);
      if (number && !usedPostNumbers.has(number)) {
        usedPostNumbers.add(number);
        postNumbers.set(post.id, number);
      } else {
        while (usedPostNumbers.has(nextPost)) nextPost += 1;
        postNumbers.set(post.id, nextPost++);
        usedPostNumbers.add(postNumbers.get(post.id));
      }
    }

    const taskEntries = [];
    for (const post of orderedPosts) {
      const tasks = Array.isArray(post.filterTasks) ? post.filterTasks : [];
      tasks.forEach((task, index) => taskEntries.push({
        post,
        task,
        index,
        id: String(task.id || `${post.id}:legacy-task-${index + 1}`)
      }));
    }
    taskEntries.sort((a, b) => Number(a.task.startedAt || a.task.completedAt || a.post.lastScannedAt || 0) - Number(b.task.startedAt || b.task.completedAt || b.post.lastScannedAt || 0) || String(a.id).localeCompare(String(b.id)));
    const taskNumbers = new Map();
    const usedTaskNumbers = new Set();
    let nextTask = taskEntries.reduce((highest, entry) => Math.max(highest, positiveInteger(entry.task.taskNumber)), 0) + 1;
    for (const entry of taskEntries) {
      if (taskNumbers.has(entry.id)) continue;
      const number = positiveInteger(entry.task.taskNumber);
      if (number && !usedTaskNumbers.has(number)) {
        taskNumbers.set(entry.id, number);
        usedTaskNumbers.add(number);
      } else {
        while (usedTaskNumbers.has(nextTask)) nextTask += 1;
        taskNumbers.set(entry.id, nextTask++);
        usedTaskNumbers.add(taskNumbers.get(entry.id));
      }
    }

    const nextPosts = orderedPosts.map(post => {
      const postNumber = postNumbers.get(post.id);
      const filterTasks = (Array.isArray(post.filterTasks) ? post.filterTasks : []).map((task, index) => {
        const id = String(task.id || `${post.id}:legacy-task-${index + 1}`);
        return normalizeFilterTask({
          ...task,
          id,
          taskNumber: taskNumbers.get(id),
          postId: post.id,
          postNumber
        });
      });
      return { ...post, postNumber, filterTasks };
    });
    const changed = nextPosts.some((post, index) => JSON.stringify(post) !== JSON.stringify(posts.find(item => item.id === post.id) || {}));
    if (changed) {
      await new Promise((resolve, reject) => {
        const transaction = db.transaction("posts", "readwrite");
        const store = transaction.objectStore("posts");
        for (const post of nextPosts) store.put(post);
        transaction.oncomplete = resolve;
        transaction.onerror = () => reject(transaction.error || new Error("保存帖子和任务编号失败。"));
        transaction.onabort = () => reject(transaction.error || new Error("保存帖子和任务编号已中断。"));
      });
    }
    const storage = globalThis.chrome?.storage?.local;
    if (storage && taskEntries.length) {
      const current = await storage.get({ nextTaskNumber: 0 });
      await storage.set({ nextTaskNumber: Math.max(Number(current.nextTaskNumber) || 0, ...taskNumbers.values()) });
    }
  }

  async function ensureLegacyMigration() {
    const storage = globalThis.chrome?.storage?.local;
    if (!storage) return;
    const values = await storage.get({ [MIGRATION_KEY]: false, [URL_MIGRATION_KEY]: false, [TASK_ASSOCIATION_MIGRATION_KEY]: false, [NUMBERING_MIGRATION_KEY]: false, [STRICT_DEDUP_MIGRATION_KEY]: false, rows: [] });
    if (!values[MIGRATION_KEY]) {
      if (values.rows?.length) await upsertRows(values.rows);
      await storage.set({ [MIGRATION_KEY]: true });
    }
    if (!values[URL_MIGRATION_KEY]) {
      await migrateStoredPostUrls();
      await storage.set({ [URL_MIGRATION_KEY]: true });
    }
    if (!values[STRICT_DEDUP_MIGRATION_KEY]) {
      await migrateStoredPostUrls();
      await reconcileUserCommentCounts();
      await storage.set({ [STRICT_DEDUP_MIGRATION_KEY]: true });
    }
    if (!values[TASK_ASSOCIATION_MIGRATION_KEY]) {
      await migrateTaskAssociations();
      await storage.set({ [TASK_ASSOCIATION_MIGRATION_KEY]: true });
    }
    if (!values[NUMBERING_MIGRATION_KEY]) {
      await ensurePersistentNumbers();
      await storage.set({ [NUMBERING_MIGRATION_KEY]: true });
    }
  }

  async function removeComments(predicate) {
    const records = await getAll("comments");
    const targets = records.filter(predicate);
    if (!targets.length) return 0;

    const postCounts = new Map();
    const userCounts = new Map();
    for (const row of targets) {
      postCounts.set(row.postId, (postCounts.get(row.postId) || 0) + 1);
      if (row.profile) userCounts.set(row.profile, (userCounts.get(row.profile) || 0) + 1);
    }

    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(["posts", "comments", "users"], "readwrite");
      const posts = transaction.objectStore("posts");
      const comments = transaction.objectStore("comments");
      const users = transaction.objectStore("users");
      for (const row of targets) comments.delete(row.id);
      for (const [postId, count] of postCounts) {
        const request = posts.get(postId);
        request.onsuccess = () => {
          if (request.result) posts.put({ ...request.result, commentCount: Math.max(0, Number(request.result.commentCount || 0) - count) });
        };
      }
      for (const [profile, count] of userCounts) {
        const request = users.get(profile);
        request.onsuccess = () => {
          if (!request.result) return;
          const commentCount = Math.max(0, Number(request.result.commentCount || 0) - count);
          if (commentCount === 0) users.delete(profile);
          else users.put({ ...request.result, commentCount });
        };
      }
      transaction.oncomplete = () => resolve(targets.length);
      transaction.onerror = () => reject(transaction.error || new Error("删除数据库记录失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("删除数据库记录已中断。"));
    });
  }

  async function removeProfiles(profiles) {
    const profileSet = new Set((profiles || []).filter(Boolean));
    if (!profileSet.size) return 0;
    return removeComments(row => profileSet.has(row.profile));
  }

  async function removeConfirmedMale() {
    return removeComments(row => row.gender === "男");
  }

  async function postRecords(url) {
    const postId = postIdentity(url);
    const [posts, comments] = await Promise.all([getAll("posts"), getAll("comments")]);
    return {
      postId,
      post: posts.find(post => post.id === postId) || null,
      comments,
      targets: comments.filter(row => row.postId === postId)
    };
  }

  async function deletePostData(url) {
    const { postId, post, comments, targets } = await postRecords(url);
    const profiles = new Set(targets.map(row => row.profile).filter(Boolean));
    const remaining = comments.filter(row => row.postId !== postId);
    const remainingByProfile = new Map();
    for (const row of remaining) {
      if (!row.profile) continue;
      remainingByProfile.set(row.profile, (remainingByProfile.get(row.profile) || 0) + 1);
    }
    const db = await openDatabase();
    await new Promise((resolve, reject) => {
      const transaction = db.transaction(["posts", "comments", "users"], "readwrite");
      const postsStore = transaction.objectStore("posts");
      const commentsStore = transaction.objectStore("comments");
      const usersStore = transaction.objectStore("users");
      for (const row of targets) commentsStore.delete(row.id);
      if (post) postsStore.delete(postId);
      for (const profile of profiles) {
        const count = remainingByProfile.get(profile) || 0;
        const request = usersStore.get(profile);
        request.onsuccess = () => {
          if (!request.result) return;
          if (count === 0) usersStore.delete(profile);
          else usersStore.put({ ...request.result, commentCount: count, lastSeenAt: Date.now() });
        };
      }
      transaction.oncomplete = resolve;
      transaction.onerror = () => reject(transaction.error || new Error("删除帖子数据失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("删除帖子数据已中断。"));
    });
    return { postId, deletedComments: targets.length, deletedPost: Boolean(post), affectedUsers: profiles.size };
  }

  async function deleteTaskData(url, taskId) {
    const postId = postIdentity(url);
    const normalizedTaskId = String(taskId || "").trim();
    if (!postId || !normalizedTaskId) throw new Error("缺少帖子或任务编号。");
    const [posts, comments] = await Promise.all([getAll("posts"), getAll("comments")]);
    const post = posts.find(item => item.id === postId) || null;
    const targets = comments.filter(row => row.postId === postId && Array.isArray(row.filterTaskIds) && row.filterTaskIds.includes(normalizedTaskId));
    const nextComments = comments.map(row => {
      if (row.postId !== postId || !Array.isArray(row.filterTaskIds) || !row.filterTaskIds.includes(normalizedTaskId)) return row;
      return { ...row, filterTaskIds: row.filterTaskIds.filter(id => id !== normalizedTaskId) };
    });
    const deleted = targets.filter(row => nextComments.some(next => next.id === row.id && next.filterTaskIds.length === 0));
    const remainingComments = nextComments.filter(row => row.id && !deleted.some(item => item.id === row.id));
    const affectedProfiles = new Set(targets.map(row => row.profile).filter(Boolean));
    const remainingByProfile = new Map();
    for (const row of remainingComments) {
      if (!row.profile) continue;
      remainingByProfile.set(row.profile, (remainingByProfile.get(row.profile) || 0) + 1);
    }
    const nextPost = post ? {
      ...post,
      commentCount: remainingComments.filter(row => row.postId === postId).length,
      filterTasks: (Array.isArray(post.filterTasks) ? post.filterTasks : []).filter(task => String(task.id || "") !== normalizedTaskId)
    } : null;

    const db = await openDatabase();
    await new Promise((resolve, reject) => {
      const transaction = db.transaction(["posts", "comments", "users"], "readwrite");
      const postsStore = transaction.objectStore("posts");
      const commentsStore = transaction.objectStore("comments");
      const usersStore = transaction.objectStore("users");
      for (const row of targets) {
        const next = nextComments.find(item => item.id === row.id);
        if (!next || !next.filterTaskIds.length) commentsStore.delete(row.id);
        else commentsStore.put({ ...next, updatedAt: Date.now() });
      }
      if (nextPost) postsStore.put(nextPost);
      for (const profile of affectedProfiles) {
        const count = remainingByProfile.get(profile) || 0;
        const request = usersStore.get(profile);
        request.onsuccess = () => {
          if (!request.result) return;
          if (count === 0) usersStore.delete(profile);
          else usersStore.put({ ...request.result, commentCount: count, lastSeenAt: Date.now() });
        };
      }
      transaction.oncomplete = resolve;
      transaction.onerror = () => reject(transaction.error || new Error("删除任务数据失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("删除任务数据已中断。"));
    });
    return {
      postId,
      taskId: normalizedTaskId,
      deletedComments: deleted.length,
      unlinkedComments: targets.length - deleted.length,
      deletedTask: Boolean(post?.filterTasks?.some(task => String(task.id || "") === normalizedTaskId))
    };
  }

  function replacePostData(url, rows, meta = {}) {
    return queuePostWrite(() => replacePostDataInternal(url, rows, meta));
  }

  async function replacePostDataInternal(url, rows, meta = {}) {
    const info = globalThis.CommentFilterPostUtils?.getPostInfo?.(url, null) || {};
    const postUrl = info.url || url;
    const postId = postIdentity(postUrl);
    const records = toRecords(rows).filter(row => row.postId === postId);
    const [posts, comments, users] = await Promise.all([getAll("posts"), getAll("comments"), getAll("users")]);
    const existingPost = posts.find(post => post.id === postId) || null;
    const oldComments = comments.filter(row => row.postId === postId);
    const otherComments = comments.filter(row => row.postId !== postId);
    const affectedProfiles = new Set([
      ...oldComments.map(row => row.profile),
      ...records.map(row => row.profile)
    ].filter(Boolean));
    const remainingByProfile = new Map();
    for (const row of otherComments) {
      if (!row.profile) continue;
      remainingByProfile.set(row.profile, (remainingByProfile.get(row.profile) || 0) + 1);
    }
    for (const record of records) {
      if (!record.profile) continue;
      remainingByProfile.set(record.profile, (remainingByProfile.get(record.profile) || 0) + 1);
    }
    const incomingUsers = new Map();
    const oldByCommentId = new Map(oldComments.map(row => [row.id, row]));
    const knownUsers = new Map(users.map(user => [user.profile, user]));
    for (const record of records) {
      if (record.profile && !incomingUsers.has(record.profile)) incomingUsers.set(record.profile, record);
    }

    const mergedRecords = records.map(record => {
      const existing = oldByCommentId.get(record.id);
      const knownUser = knownUsers.get(record.profile);
      const merged = mergeComment(existing, record);
      if (knownUser) {
        merged.gender = knownValue(record.gender, knownUser.gender, merged.gender || "未知");
        merged.profileAge = knownValue(record.profileAge, knownUser.profileAge, merged.profileAge || "");
        merged.profileLocation = knownValue(record.profileLocation, knownUser.profileLocation, merged.profileLocation || "");
      }
      return merged;
    });

    const now = Date.now();
    const db = await openDatabase();
    await new Promise((resolve, reject) => {
      const transaction = db.transaction(["posts", "comments", "users"], "readwrite");
      const postsStore = transaction.objectStore("posts");
      const commentsStore = transaction.objectStore("comments");
      const usersStore = transaction.objectStore("users");
      for (const row of oldComments) commentsStore.delete(row.id);
      for (const record of mergedRecords) {
        commentsStore.put({
          ...mergeComment(null, record),
          collectedAt: record.collectedAt || now,
          updatedAt: now
        });
      }
      let nextPost = {
        ...existingPost,
        id: postId,
        postNumber: positiveInteger(existingPost?.postNumber) || nextNumber(posts, "postNumber"),
        url: postUrl,
        sourceUrl: meta.sourceUrl || existingPost?.sourceUrl || info.sourceUrl || "",
        title: meta.title || existingPost?.title || "",
        platform: platformFromUrl(postUrl),
        commentCount: records.length,
        platformCommentCount: Math.max(0, Number(meta.task?.pagination?.platformCommentCount || existingPost?.platformCommentCount || 0)),
        pagination: meta.task?.pagination || existingPost?.pagination || null,
        firstSeenAt: existingPost?.firstSeenAt || now,
        lastScannedAt: now,
        filterTasks: Array.isArray(existingPost?.filterTasks) ? existingPost.filterTasks : []
      };
      nextPost = withFilterTask(nextPost, meta.task ? { ...meta.task, postId, postNumber: nextPost.postNumber } : null);
      postsStore.put(nextPost);
      for (const profile of affectedProfiles) {
        const count = remainingByProfile.get(profile) || 0;
        const request = usersStore.get(profile);
        request.onsuccess = () => {
          if (count === 0) {
            usersStore.delete(profile);
            return;
          }
            const incoming = incomingUsers.get(profile);
          const merged = incoming
            ? mergeUser(request.result, incoming, 0, now)
            : { ...request.result, lastSeenAt: now };
          usersStore.put({ ...merged, commentCount: count });
        };
      }
      transaction.oncomplete = resolve;
      transaction.onerror = () => reject(transaction.error || new Error("更新帖子数据失败。"));
      transaction.onabort = () => reject(transaction.error || new Error("更新帖子数据已中断。"));
    });
    return { postId, removedComments: oldComments.length, addedComments: mergedRecords.length };
  }

  globalThis.CommentFilterDatabase = {
    ensureLegacyMigration,
    upsertRows,
    recordPostScan,
    updateUserProfile,
    updateUserGender,
    getDashboardData,
    getAnalysisScopes,
    saveAnalysisScope,
    setAnalysisUserDecision,
    deleteAnalysisScope,
    upsertDiscoveredPosts,
    removeProfiles,
    removeConfirmedMale,
    deletePostData,
    deleteTaskData,
    replacePostData,
    postIdentity
  };
})();
