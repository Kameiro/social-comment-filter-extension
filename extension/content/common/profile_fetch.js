(() => {
  if (globalThis.__commentFilterProfileFetchV1) return;
  globalThis.__commentFilterProfileFetchV1 = true;

  async function fetchDouyinPublicProfile(url) {
    const target = new URL(url);
    const secUid = target.pathname.match(/^\/user\/(MS4[\w-]+)\/?$/)?.[1];
    if (target.origin !== "https://www.douyin.com" || !secUid) throw new Error("抖音主页链接格式不正确。");
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch(`${target.origin}/user/${secUid}`, {
        credentials: "include", signal: controller.signal
      });
      if (!response.ok) throw new Error(`抖音资料请求失败：HTTP ${response.status}`);
      if (new URL(response.url).pathname.replace(/\/$/, "") !== `/user/${secUid}`) throw new Error("抖音主页跳转到了登录或验证页面。");
      const html = await response.text();
      const info = globalThis.CommentFilterProfileMetadata.extractDouyinProfilePublicInfo({ html, expectedSecUid: secUid });
      if (!info) throw new Error("主页没有返回对应用户的公开结构化资料。");
      const rendered = globalThis.CommentFilterProfileMetadata.extractDouyinRenderedProfileInfo({ html });
      return {
        ...info,
        gender: rendered?.gender && rendered.gender !== "未知" ? rendered.gender : info.gender,
        profileAge: info.profileAge || rendered?.profileAge || "",
        profileLocation: info.profileLocation || rendered?.profileLocation || ""
      };
    } finally {
      clearTimeout(timer);
    }
  }

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === "DY_READ_RENDERED_PROFILE_INFO_V1") {
      try {
        const info = globalThis.CommentFilterProfileMetadata?.extractDouyinRenderedProfileInfo?.() || {
          gender: "未知", profileAge: "", profileLocation: "", profileReadSource: "douyin-rendered"
        };
        sendResponse({ ok: true, info });
      } catch (error) {
        sendResponse({ ok: false, error: error.message || "读取当前主页资料失败。" });
      }
      return false;
    }
    if (message?.type !== "DY_FETCH_PROFILE_PUBLIC_INFO_V1") return false;
    fetchDouyinPublicProfile(message.url)
      .then(info => sendResponse({ ok: true, info }))
      .catch(error => sendResponse({ ok: false, error: error.message || "快速读取主页失败。" }));
    return true;
  });
})();
