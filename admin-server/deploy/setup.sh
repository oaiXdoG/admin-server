#!/usr/bin/env bash
# 在服务器上执行一次（root）：创建运行账号和目录、安装 pg_dump（数据备份用）和 systemd 服务。
# 前提：/opt/admin-background/conf/application.properties 已按 README 准备好。
set -euo pipefail

id admin-background >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin admin-background
mkdir -p /opt/admin-background/lib /opt/admin-background/conf /data/admin-background/backup
# 备份目录（backup.dir）需要运行账号可写
chown admin-background:admin-background /data/admin-background/backup
chmod 750 /data/admin-background/backup
# Debian 13 只有 17 版客户端，可以备份 16 版数据库
command -v pg_dump >/dev/null || apt-get install -y postgresql-client-17
chown -R root:admin-background /opt/admin-background/conf
chmod 750 /opt/admin-background/conf

install -m 644 /opt/admin-background/deploy/admin-background.service /etc/systemd/system/admin-background.service
systemctl daemon-reload
systemctl enable admin-background

