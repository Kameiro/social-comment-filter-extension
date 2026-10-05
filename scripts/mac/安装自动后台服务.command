#!/bin/zsh
set -e

project_root="$(cd "$(dirname "$0")/../.." && pwd)"
host_path="$project_root/services/native-host/comment-filter-native-host.command"
template_path="$project_root/services/native-host/com.commentfilter.helper.json.template"
launch_agent_label="com.commentfilter.playwright-worker"
launch_agent_dir="$HOME/Library/LaunchAgents"
launch_agent_path="$launch_agent_dir/$launch_agent_label.plist"
host_dirs=(
  "$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
  "$HOME/Library/Application Support/Citro Labs/ego lite/NativeMessagingHosts"
  "$HOME/Library/Application Support/Chromium/NativeMessagingHosts"
  "$HOME/Library/Application Support/Microsoft Edge/NativeMessagingHosts"
)

node_bin="/usr/local/bin/node"
if [[ ! -x "$node_bin" ]]; then
  node_bin="$(command -v node || true)"
fi
if [[ -z "$node_bin" ]] || [[ ! -x "$node_bin" ]]; then
  osascript -e 'display alert "未找到 Node.js" message "请先安装 Node.js，再重新双击此安装文件。"'
  exit 1
fi

if [[ ! -d "$project_root/services/playwright-worker/node_modules/playwright" ]]; then
  osascript -e 'display alert "缺少 Playwright 依赖" message "请先双击“启动评论筛选后台.command”完成依赖安装，或联系我处理。"'
  exit 1
fi

playwright_browser_path="$(cd "$project_root/services/playwright-worker" && node --input-type=module -e 'import { chromium } from "playwright"; process.stdout.write(chromium.executablePath())')"
if [[ ! -x "$playwright_browser_path" ]]; then
  if ! (cd "$project_root/services/playwright-worker" && npx playwright install chromium); then
    osascript -e 'display alert "缺少 Chromium 运行时" message "Playwright 浏览器安装失败，请检查网络后重试。"'
    exit 1
  fi
fi

if [[ ! -x "$host_path" ]]; then
  osascript -e 'display alert "本地后台主机文件不存在" message "请确认插件目录完整，并重新运行安装脚本。"'
  exit 1
fi
chmod +x "$host_path"
escaped_host_path="${host_path//&/\\&}"
for host_dir in "${host_dirs[@]}"; do
  manifest_path="$host_dir/com.commentfilter.helper.json"
  mkdir -p "$host_dir"
  sed "s|__NATIVE_HOST_PATH__|$escaped_host_path|g" "$template_path" > "$manifest_path"
  if [[ ! -f "$manifest_path" ]] || ! grep -Fq 'services/native-host/comment-filter-native-host.command' "$manifest_path"; then
    osascript -e 'display alert "自动后台服务注册失败" message "Native Messaging Host 清单没有写入当前插件目录。"'
    exit 1
  fi
done

# Keep the worker independent from the browser that happens to be installed.
# Native Messaging remains registered above as a fallback for browsers that use
# the extension before the user session has loaded this LaunchAgent.
mkdir -p "$launch_agent_dir"
cat > "$launch_agent_path" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$launch_agent_label</string>
  <key>ProgramArguments</key>
  <array>
    <string>$node_bin</string>
    <string>$project_root/services/playwright-worker/worker.mjs</string>
  </array>
  <key>WorkingDirectory</key>
  <string>$project_root/services/playwright-worker</string>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ThrottleInterval</key>
  <integer>5</integer>
  <key>StandardOutPath</key>
  <string>$project_root/services/playwright-worker/launchd-worker.log</string>
  <key>StandardErrorPath</key>
  <string>$project_root/services/playwright-worker/launchd-worker.log</string>
</dict>
</plist>
EOF

if ! plutil -lint "$launch_agent_path" >/dev/null; then
  osascript -e 'display alert "常驻后台服务配置无效" message "launchd 配置文件校验失败，请把错误截图发给我。"'
  exit 1
fi

# Replace only this project's worker so launchd becomes the single owner.
launchctl bootout "gui/$(id -u)/$launch_agent_label" 2>/dev/null || true
pkill -f "$project_root/services/playwright-worker/worker.mjs" 2>/dev/null || true
for attempt in {1..20}; do
  if ! lsof -nP -iTCP:38765 -sTCP:LISTEN >/dev/null 2>&1; then
    break
  fi
  pkill -f "$project_root/services/playwright-worker/worker.mjs" 2>/dev/null || true
  sleep 0.5
done
if lsof -nP -iTCP:38765 -sTCP:LISTEN >/dev/null 2>&1; then
  osascript -e 'display alert "后台端口被占用" message "无法接管 38765 端口，请先停止旧的评论筛选后台服务后重试。"'
  exit 1
fi
launchctl bootstrap "gui/$(id -u)" "$launch_agent_path"
launchctl enable "gui/$(id -u)/$launch_agent_label" 2>/dev/null || true
launchctl kickstart -k "gui/$(id -u)/$launch_agent_label"

worker_ready=""
for attempt in {1..20}; do
  if curl -fsS --max-time 1 http://127.0.0.1:38765/health >/dev/null 2>&1; then
    worker_ready="yes"
    break
  fi
  sleep 0.5
done

if [[ "$worker_ready" != "yes" ]]; then
  osascript -e 'display alert "常驻后台服务未启动" message "已安装 launchd，但 38765 端口尚未就绪。请检查 launchd-worker.log。"'
  exit 1
fi

osascript -e 'display notification "安装完成。后台服务已加入 launchd，会自动运行，不依赖浏览器类型。" with title "评论筛选助手"'
