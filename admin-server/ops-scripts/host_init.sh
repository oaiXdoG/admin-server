#!/usr/bin/env bash
# 初始化主机，可以重复执行。管理后台通过 SSH 把这个脚本送给 bash -s 执行：bash -s -- <node_exporter 端口> <process-exporter 端口>
# - 程序运行需要的 JDK 21、unzip（app.sh 用它读取 Main-Class、解压程序包）
# - 监控：node_exporter（机器指标）、process-exporter（按命令行里的 -Dhost.app.dir=<程序目录> 统计每个程序的 CPU、内存、线程）
#   由 Prometheus 拉取，两个端口只需要对 Prometheus 所在机器开放
set -euo pipefail

NODE_PORT="${1:-9100}"
PROCESS_PORT="${2:-9256}"
export DEBIAN_FRONTEND=noninteractive

# 内容不同才写入；写入了返回 0，没变返回 1
write_if_changed() {
  local file="$1" content="$2"
  if [ -f "$file" ] && [ "$(cat "$file")" = "$content" ]; then
    return 1
  fi
  mkdir -p "$(dirname "$file")"
  printf '%s\n' "$content" > "$file"
  echo "写入 $file"
}

# 配置变了重启，否则确保在运行；都设为开机自启
ensure_service() {
  local name="$1" changed="$2"
  systemctl enable "$name" >/dev/null 2>&1 || true
  if [ "$changed" = 1 ]; then
    systemctl restart "$name"
    echo "重启 $name"
  else
    systemctl start "$name"
  fi
}

echo "== 安装 openjdk-21-jre-headless、unzip、node_exporter、process-exporter"
apt-get update -q
apt-get install -y -q --no-install-recommends openjdk-21-jre-headless unzip prometheus-node-exporter prometheus-process-exporter

echo "== 配置监控"
node_changed=0
write_if_changed /etc/default/prometheus-node-exporter "# 由管理后台运维中心生成
ARGS=\"--web.listen-address=:$NODE_PORT\"" && node_changed=1

process_changed=0
write_if_changed /etc/prometheus/process-exporter.yml "# 由管理后台运维中心生成：按 -Dhost.app.dir=<程序目录> 把进程归到程序，groupname 为程序目录
process_names:
  - name: \"{{.Matches.dir}}\"
    cmdline:
      - '-Dhost\\.app\\.dir=(?P<dir>\\S+)'" && process_changed=1
write_if_changed /etc/default/prometheus-process-exporter "# 由管理后台运维中心生成
ARGS=\"-web.listen-address :$PROCESS_PORT -config.path /etc/prometheus/process-exporter.yml\"" && process_changed=1

ensure_service prometheus-node-exporter "$node_changed"
ensure_service prometheus-process-exporter "$process_changed"

echo "== 版本"
java -version 2>&1 | head -1
unzip -v | head -1
echo "初始化完成"
