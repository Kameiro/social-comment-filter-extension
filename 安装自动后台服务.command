#!/bin/zsh
set -e

extension_dir="$(cd "$(dirname "$0")" && pwd)"
host_dir="$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
host_path="$extension_dir/native-host/comment-filter-native-host.command"
template_path="$extension_dir/native-host/com.commentfilter.helper.json.template"
manifest_path="$host_dir/com.commentfilter.helper.json"

if [[ ! -x "/usr/local/bin/node" ]] && ! command -v node >/dev/null 2>&1; then
  osascript -e 'display alert "未找到 Node.js" message "请先安装 Node.js，再重新双击此安装文件。"'
  exit 1
fi

if [[ ! -d "$extension_dir/playwright-worker/node_modules/playwright" ]]; then
  osascript -e 'display alert "缺少 Playwright 依赖" message "请先双击“启动评论筛选后台.command”完成依赖安装，或联系我处理。"'
  exit 1
fi

mkdir -p "$host_dir"
chmod +x "$host_path"
escaped_host_path="${host_path//&/\\&}"
sed "s|__NATIVE_HOST_PATH__|$escaped_host_path|g" "$template_path" > "$manifest_path"

osascript -e 'display notification "安装完成。重新加载 Chrome 扩展后，插件会自动启动后台服务。" with title "评论筛选助手"'
