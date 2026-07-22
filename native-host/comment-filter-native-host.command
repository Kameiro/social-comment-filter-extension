#!/bin/zsh
set -e
script_dir="$(cd "$(dirname "$0")" && pwd)"
node_bin="/usr/local/bin/node"
if [[ ! -x "$node_bin" ]]; then
  node_bin="$(command -v node)"
fi
exec "$node_bin" "$script_dir/comment-filter-native-host.mjs"
