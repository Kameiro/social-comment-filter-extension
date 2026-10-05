#!/bin/zsh
set -e
cd "$(dirname "$0")/../../services/playwright-worker"
exec npm run login:douyin
