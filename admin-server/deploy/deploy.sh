#!/usr/bin/env bash
# 本机执行（mvn 需使用 JDK 21）：编译打包，上传到服务器并重启服务。服务器上的 application.properties 不会被覆盖。
# 用法：deploy/deploy.sh <SSH 主机>
set -euo pipefail

HOST="${1:?用法：$0 <SSH 主机>}"
cd "$(dirname "$0")/.."

rm -rf target/lib
mvn -q -o clean package dependency:copy-dependencies -DskipTests -DincludeScope=runtime -DoutputDirectory=target/lib
cp target/adminBackground-*.jar target/lib/

rsync -az --delete target/lib/ "$HOST":/opt/admin-background/lib/
rsync -az conf/config.xml conf/protocol.xml conf/logback.xml "$HOST":/opt/admin-background/conf/
rsync -az deploy/ "$HOST":/opt/admin-background/deploy/
ssh "$HOST" 'systemctl restart admin-background && sleep 8 && systemctl --no-pager --lines=5 status admin-background'
