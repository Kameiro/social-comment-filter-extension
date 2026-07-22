#!/bin/zsh
set -e
cd "$(dirname "$0")/playwright-worker"
exec npm start
