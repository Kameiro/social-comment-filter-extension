import assert from "node:assert/strict";
import { browserConnectionConfig, cdpEndpoints } from "../services/playwright-worker/browser_connection.mjs";

assert.deepEqual(browserConnectionConfig({}), { mode: "auto", explicitUrl: "", ports: [9222] });
assert.deepEqual(cdpEndpoints({}), ["http://127.0.0.1:9222"]);
assert.deepEqual(browserConnectionConfig({ COMMENT_FILTER_BROWSER_MODE: "isolated", COMMENT_FILTER_CDP_PORT: "9333" }), {
  mode: "isolated", explicitUrl: "", ports: [9333]
});
assert.deepEqual(cdpEndpoints({ COMMENT_FILTER_BROWSER_MODE: "isolated", COMMENT_FILTER_CDP_PORT: "9333" }), []);
assert.deepEqual(cdpEndpoints({ COMMENT_FILTER_BROWSER_MODE: "cdp", COMMENT_FILTER_CDP_URL: "ws://127.0.0.1:9444/devtools/browser/a", COMMENT_FILTER_CDP_PORTS: "9222, 9223" }), [
  "ws://127.0.0.1:9444/devtools/browser/a", "http://127.0.0.1:9222", "http://127.0.0.1:9223"
]);
console.log("Browser connection config tests passed: auto, CDP, isolated and custom endpoints.");
