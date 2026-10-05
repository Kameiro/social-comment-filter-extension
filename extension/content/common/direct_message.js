(() => {
  if (window.__commentFilterDirectMessageV1) return;
  window.__commentFilterDirectMessageV1 = true;

  const clean = value => String(value || "").replace(/\s+/g, " ").trim();
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

  function visible(element) {
    if (!element) return false;
    const style = getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return style.display !== "none" && style.visibility !== "hidden" && Number(style.opacity || 1) > 0 && rect.width > 0 && rect.height > 0;
  }

  function labelOf(element) {
    return clean([
      element.innerText,
      element.getAttribute("aria-label"),
      element.getAttribute("title"),
      element.getAttribute("data-e2e")
    ].filter(Boolean).join(" "));
  }

  function candidates(root = document) {
    return [...root.querySelectorAll("button, [role='button'], a, [aria-label], [title]")].filter(visible);
  }

  function findMessageEntry() {
    const entries = candidates().map(element => ({ element, label: labelOf(element) }));
    const exact = entries.find(item => /^(发私信|私信|发消息|发送消息|聊天)$/.test(item.label));
    if (exact) return exact.element;
    return entries.find(item => /发私信|发消息|发送消息/.test(item.label))?.element || null;
  }

  function findComposer() {
    if (/douyin\.com$/.test(location.hostname)) {
      return [...document.querySelectorAll('[data-e2e="msg-input"] [contenteditable="true"]')].find(visible) || null;
    }
    const selectors = [
      "textarea",
      "input[type='text']",
      "[contenteditable='true']",
      "[role='textbox']"
    ];
    const inputs = selectors.flatMap(selector => [...document.querySelectorAll(selector)]).filter(visible);
    return inputs.find(input => {
      const hint = clean([input.getAttribute("placeholder"), input.getAttribute("aria-label"), input.getAttribute("data-placeholder")].filter(Boolean).join(" "));
      return !/搜索/.test(hint) && (/消息|私信|发消息|说点什么|聊天|内容/.test(hint) || input.isContentEditable);
    }) || null;
  }

  function messageScope(input) {
    return input?.closest("[role='dialog'], [class*='dialog'], [class*='chat'], [class*='message'], [class*='private']") || document;
  }

  function findSendButton(input) {
    const douyinSend = input?.closest('[data-e2e="msg-input"]')?.querySelector('.e2e-send-msg-btn');
    if (douyinSend && visible(douyinSend)) return douyinSend;
    const scope = messageScope(input);
    const items = candidates(scope).map(element => ({ element, label: labelOf(element) }));
    return items.find(item => /^(发送|发送消息|确定)$/.test(item.label))?.element ||
      items.find(item => /^发送/.test(item.label))?.element || null;
  }

  function setNativeValue(input, value) {
    if (input.isContentEditable || input.getAttribute("contenteditable") === "true") {
      if (input.getAttribute("data-slate-editor") === "true") {
        if (clean(input.textContent).replace(/\u200b/g, "")) throw new Error("私信输入框已有草稿，已停止以避免覆盖。");
        if (!document.execCommand("insertText", false, value)) throw new Error("无法写入平台私信编辑器。");
        return;
      }
      input.textContent = value;
    } else {
      const prototype = Object.getPrototypeOf(input);
      const descriptor = Object.getOwnPropertyDescriptor(prototype, "value");
      if (descriptor?.set) descriptor.set.call(input, value);
      else input.value = value;
    }
    const inputEvent = typeof InputEvent === "function"
      ? new InputEvent("input", { bubbles: true, inputType: "insertText", data: value })
      : new Event("input", { bubbles: true });
    input.dispatchEvent(inputEvent);
    input.dispatchEvent(new Event("change", { bubbles: true }));
  }

  async function waitFor(getter, timeout = 9000) {
    const startedAt = Date.now();
    while (Date.now() - startedAt < timeout) {
      const value = getter();
      if (value) return value;
      await sleep(180);
    }
    return null;
  }

  async function send(content, prepareOnly = false) {
    if (!content.trim() || content.length > 500) throw new Error("私信内容不能为空或超过 500 个字符。");
    let input = findComposer();
    if (!input) {
      const opener = await waitFor(findMessageEntry, 7000);
      if (!opener) throw new Error("没有找到该平台的私信入口，请先登录并打开用户主页。");
      opener.click();
      input = await waitFor(findComposer);
    }
    if (!input) throw new Error("私信输入框加载超时，请确认账号已登录。");
    if (prepareOnly) return { ok: true, prepared: true };
    const panel = input.closest('.componentsRightPanelwrapper') || messageScope(input);
    const bubbleSelector = '[data-e2e="msg-item-content"]';
    const countMatching = () => [...panel.querySelectorAll(bubbleSelector)].filter(node => clean(node.innerText || node.textContent) === clean(content)).length;
    const beforeCount = countMatching();
    input.focus();
    const draft = clean(input.textContent).replace(/\u200b/g, "");
    if (!input.isContentEditable || draft !== clean(content)) setNativeValue(input, content);
    await sleep(250);
    const sendButton = await waitFor(() => findSendButton(input), 5000);
    if (!sendButton) throw new Error("没有找到发送按钮。请先在平台页面手动打开私信窗口后重试。");
    if (sendButton.getAttribute("aria-disabled") === "true" || sendButton.disabled) throw new Error("平台发送按钮不可用。");
    if (typeof sendButton.click === "function") sendButton.click();
    else sendButton.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, view: window }));
    if (/douyin\.com$/.test(location.hostname)) {
      const confirmed = await waitFor(() => countMatching() > beforeCount && !clean(input.textContent).replace(/\u200b/g, ""), 10000);
      if (!confirmed) throw new Error("已点击发送，但未确认新消息出现在聊天记录中；请检查聊天记录，不要立即重复发送。");
      await sleep(1000);
      const failed = [...panel.querySelectorAll('[class*="fail"], [class*="Fail"], [role="alert"]')].some(node => visible(node) && /失败|重试|限制|频繁|无法发送/.test(labelOf(node)));
      if (failed) throw new Error("聊天窗口显示发送失败或平台限制，请手动检查。");
      return { ok: true, platform: "douyin", confirmation: "chat-record" };
    }
    await sleep(700);
    return { ok: true, platform: /xiaohongshu/i.test(location.hostname) ? "xhs" : "kuaishou" };
  }

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type !== "DIRECT_MESSAGE_SEND_V1") return false;
    send(String(message.content || "").trim(), message.prepareOnly === true)
      .then(sendResponse)
      .catch(error => sendResponse({ ok: false, error: error.message || "私信发送失败。" }));
    return true;
  });
})();
