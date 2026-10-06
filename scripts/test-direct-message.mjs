import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../extension/content/common/direct_message.js", import.meta.url), "utf8");
const visibleNode = attributes => ({
  getAttribute: name => attributes[name] || null,
  getBoundingClientRect: () => ({ width: 100, height: 30 }),
  ...attributes
});
const search = visibleNode({ placeholder: "搜索你感兴趣的内容" });
const editor = visibleNode({ "data-placeholder": "发送消息", isContentEditable: true });
const svg = visibleNode({});
editor.closest = () => ({ querySelector: () => svg });
let hasEditor = false;
const context = vm.createContext({
  window: {}, location: { hostname: "www.douyin.com" },
  getComputedStyle: () => ({ display: "block", visibility: "visible", opacity: "1" }),
  document: { querySelectorAll: selector => selector.includes('msg-input') ? hasEditor ? [editor] : [] : [search] },
  chrome: { runtime: { onMessage: { addListener() {} } } },
  setTimeout
});
vm.runInContext(source.replace("  chrome.runtime.onMessage.addListener", "  globalThis.testApi = { findComposer, findSendButton };\n  chrome.runtime.onMessage.addListener"), context);
assert.equal(context.testApi.findComposer(), null, "homepage search must not be used as a message composer");
hasEditor = true;
assert.equal(context.testApi.findComposer(), editor);
assert.equal(context.testApi.findSendButton(editor), svg, "support the platform's SVG send control");
console.log("Direct message selector tests passed: search exclusion, chat editor, SVG send control.");
