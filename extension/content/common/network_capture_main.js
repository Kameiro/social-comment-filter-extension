(() => {
  if (globalThis.__commentFilterNetworkCaptureV1) return;
  globalThis.__commentFilterNetworkCaptureV1 = true;

  const queue = [];
  const searchQueue = [];
  const maxBodyLength = 1200000;
  let requestSequence = 0;

  function isCommentRequest(url) {
    const value = String(url || "");
    return /douyin\.com\/aweme\/v(?:1|2)\/web\/comment\/list/i.test(value) ||
      /xiaohongshu\.com\/api\/sns\/web\/v\d+\/comment/i.test(value) ||
      /kuaishou\.com\/rest\/v\/photo\/comment\/(?:list|sublist)/i.test(value) ||
      // The note detail response contains the platform totals that belong to
      // the post snapshot. It is captured through the same page session as
      // comments, but is never replayed as a comment page.
      /xiaohongshu\.com\/api\/sns\/web\/v\d+\/feed(?:\?|$)/i.test(value);
  }

  function isSearchRequest(url) {
    const value = String(url || "");
    return /douyin\.com\/aweme\/v(?:1|2)\/web\/(?:general\/search|search)/i.test(value);
  }

  function dispatch(item, kind = "comment") {
    if (!item?.body || item.body.length > maxBodyLength) return;
    const packet = JSON.stringify({
      ...item,
      requestId: item.requestId || `comment-${++requestSequence}`,
      requestType: item.requestType || "unknown",
      capturedAt: Date.now()
    });
    const targetQueue = kind === "search" ? searchQueue : queue;
    targetQueue.push(packet);
    while (targetQueue.length > 80) targetQueue.shift();
    window.dispatchEvent(new CustomEvent(
      kind === "search" ? "comment-filter-search-response" : "comment-filter-network-response",
      { detail: packet }
    ));
  }

  async function captureFetch(response, url) {
    const kind = isCommentRequest(url) ? "comment" : isSearchRequest(url) ? "search" : "";
    if (!kind) return;
    try {
      const body = await response.clone().text();
      dispatch({ url: String(url), status: response.status, body, requestType: "fetch" }, kind);
    } catch (error) {
      // Some platform responses cannot be cloned; DOM extraction remains available.
    }
  }

  const nativeFetch = globalThis.fetch;
  if (typeof nativeFetch === "function") {
    globalThis.fetch = function (...args) {
      const promise = nativeFetch.apply(this, args);
      Promise.resolve(promise).then(response => captureFetch(response, response?.url || args[0])).catch(() => {});
      return promise;
    };
  }

  const nativeOpen = XMLHttpRequest.prototype.open;
  const nativeSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url, ...rest) {
    this.__commentFilterUrl = String(url || "");
    return nativeOpen.call(this, method, url, ...rest);
  };
  XMLHttpRequest.prototype.send = function (...args) {
    this.addEventListener("load", () => {
      const url = this.responseURL || this.__commentFilterUrl || "";
      const kind = isCommentRequest(url) ? "comment" : isSearchRequest(url) ? "search" : "";
      if (!kind) return;
      try {
        const body = this.responseType === "json"
          ? JSON.stringify(this.response)
          : this.responseText;
        dispatch({ url, status: this.status, body: String(body || ""), requestType: "xhr" }, kind);
      } catch (error) {
        // DOM extraction remains available for unsupported response types.
      }
    }, { once: true });
    return nativeSend.apply(this, args);
  };

  window.addEventListener("comment-filter-network-read", () => {
    for (const packet of queue) {
      window.dispatchEvent(new CustomEvent("comment-filter-network-response", { detail: packet }));
    }
  });

  window.addEventListener("comment-filter-search-read", () => {
    for (const packet of searchQueue) {
      window.dispatchEvent(new CustomEvent("comment-filter-search-response", { detail: packet }));
    }
  });

  // Reuse the page's own fetch/XHR environment for a captured comment request.
  // The extension never creates a signature or stores one: the current page
  // session remains responsible for cookies, tokens, and any dynamic signing.
  window.addEventListener("comment-filter-network-fetch", event => {
    let request;
    try {
      request = JSON.parse(String(event.detail || ""));
    } catch (error) {
      return;
    }
    if (!request?.requestId || !isCommentRequest(request.url)) return;

    Promise.resolve()
      .then(() => fetch(request.url, {
        credentials: "include",
        headers: { accept: "application/json, text/plain, */*" }
      }))
      .then(async response => {
        const body = await response.clone().text();
        const packet = {
          url: response.url || request.url,
          status: response.status,
          body,
          requestType: "extension-fetch",
          requestId: request.requestId,
          capturedAt: Date.now()
        };
        dispatch(packet);
        window.dispatchEvent(new CustomEvent("comment-filter-network-fetch-result", {
          detail: JSON.stringify(packet)
        }));
      })
      .catch(error => {
        window.dispatchEvent(new CustomEvent("comment-filter-network-fetch-result", {
          detail: JSON.stringify({
            requestId: request.requestId,
            error: error?.message || "评论接口请求失败"
          })
        }));
      });
  });
})();
