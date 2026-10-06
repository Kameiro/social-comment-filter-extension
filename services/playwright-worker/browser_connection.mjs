const DEFAULT_CDP_PORT = 9222;

function clean(value) {
  return String(value || "").trim();
}

function positivePort(value) {
  const port = Number(value);
  return Number.isInteger(port) && port > 0 && port < 65536 ? port : 0;
}

export function browserConnectionConfig(env = process.env) {
  const mode = clean(env.COMMENT_FILTER_BROWSER_MODE || "auto").toLowerCase();
  return {
    mode: ["auto", "cdp", "isolated"].includes(mode) ? mode : "auto",
    explicitUrl: clean(env.COMMENT_FILTER_CDP_URL),
    ports: clean(env.COMMENT_FILTER_CDP_PORTS || env.COMMENT_FILTER_CDP_PORT || DEFAULT_CDP_PORT)
      .split(/[\s,]+/)
      .map(positivePort)
      .filter(Boolean)
  };
}

export function cdpEndpoints(env = process.env) {
  const config = browserConnectionConfig(env);
  if (config.mode === "isolated") return [];
  const endpoints = [];
  if (config.explicitUrl) endpoints.push(config.explicitUrl);
  for (const port of config.ports) endpoints.push(`http://127.0.0.1:${port}`);
  return [...new Set(endpoints)];
}
