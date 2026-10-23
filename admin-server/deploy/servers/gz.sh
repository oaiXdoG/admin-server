#!/usr/bin/env bash
# gz 服务器的发布入口：admin-server 与 admin-web 的 nginx 在 gz 同机。
# SSH 别名 gz 由本机 ~/.ssh/config 提供（root 登录）。
# 用法：deploy/servers/gz.sh    发布后台
set -euo pipefail

SSH_HOST=gz
ROOT="$(dirname "$0")/../.."

exec "$ROOT/deploy/deploy.sh" "$SSH_HOST"
