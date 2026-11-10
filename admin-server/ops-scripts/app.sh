#!/usr/bin/env bash
# 程序管理脚本，所有程序共用这一份。管理后台登记程序时把它拷到 <程序目录>/bin/app.sh，每次发布前再覆盖成最新版本。
# 程序目录就是脚本所在 bin 的上一层，不需要参数或配置。在任何目录执行都一样，手动执行和管理后台执行的是同一件事。
#
# 程序目录的格式：
#   conf/                  配置文件，放在 classpath 最前面；conf/jvm.options 为 JVM 参数（每行一个，# 开头和空行忽略，可以没有）
#   lib/                   jar，其中恰好一个 jar 的 MANIFEST 带 Main-Class；lib/extra/ 也在 classpath 上
#   excel/                 可选
#   logs/                  程序自己写的日志；console.log 为最近一次启动的控制台输出（JVM 起不来时看这里）
#   app.pid                运行中的进程号
#   backup/<时间>/          发布、回滚前的 lib、conf、excel
#   backup-conf/<时间>/     在线修改配置前的旧文件
#
# 启动命令：cd <程序目录> && java <jvm.options> -Dhost.app.dir=<程序目录> -cp conf:lib/*:lib/extra/* <Main-Class>
# -Dhost.app.dir 用来确认 app.pid 里的进程确实是这个程序；监控（process-exporter）也按它区分进程。
#
# 用法：bin/app.sh <命令> [参数]
#   start | stop | restart | status
#   log [server.log|error.log|console.log] [行数]
#   deploy <zip> [--overwrite-conf] [--keep 份数]
#   backups
#   rollback <备份名>
#   conf-list | conf-get <conf 下的文件> | conf-put <conf 下的文件>（内容从标准输入读）
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$APP_DIR"
PID_FILE="$APP_DIR/app.pid"
MARKER="-Dhost.app.dir=$APP_DIR"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
START_CHECK_SECONDS="${START_CHECK_SECONDS:-3}"
STOP_TIMEOUT="${STOP_TIMEOUT:-30}"
KILL_TIMEOUT=10
BACKUP_KEEP_DEFAULT=3
CONF_MAX_SIZE=1048576
LOG_FILES="server.log error.log console.log"
# 发布、回滚时替换和备份的目录
PACKAGE_DIRS="lib conf excel"

fail() {
  echo "$*" >&2
  exit 1
}

# ---------------------------------------------------------------- 进程

# app.pid 里的进程在运行、且命令行带着本目录的标记时输出 pid
running_pid() {
  local pid args
  [ -f "$PID_FILE" ] || return 1
  pid="$(tr -dc '0-9' < "$PID_FILE")"
  [ -n "$pid" ] || return 1
  args="$(ps -ww -o args= -p "$pid" 2>/dev/null)" || return 1
  case "$args" in *"$MARKER "*) echo "$pid" ;; *) return 1 ;; esac
}

# 最多等 $1 秒，进程退出返回 0
wait_exit() {
  local i
  for ((i = 0; i < $1 * 5; i++)); do
    running_pid >/dev/null || return 0
    sleep 0.2
  done
  return 1
}

# 在 $1 目录里找 Main-Class：lib 下（不含 lib/extra）恰好一个 jar 的 MANIFEST 带 Main-Class
main_class() {
  local dir="$1" jar class found="" count=0
  command -v unzip >/dev/null 2>&1 || fail "找不到 unzip，读取 Main-Class 需要它"
  for jar in "$dir"/lib/*.jar; do
    [ -f "$jar" ] || continue
    class="$({ unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null || true; } | tr -d '\r' | sed -n 's/^Main-Class: *//p')"
    if [ -n "$class" ]; then
      found="$found $class"
      count=$((count + 1))
    fi
  done
  [ "$count" -eq 1 ] || fail "lib 下带 Main-Class 的 jar 应该恰好一个，实际 $count 个：${found:- 无}"
  echo "${found# }"
}

cmd_start() {
  local pid main line
  if pid="$(running_pid)"; then
    echo "已在运行，pid=$pid"
    return 0
  fi
  ls lib/*.jar >/dev/null 2>&1 || fail "还没有发布：$APP_DIR/lib 下没有 jar"
  command -v "$JAVA" >/dev/null 2>&1 || fail "找不到 java（$JAVA），先初始化主机安装 JDK，或者设置 JAVA_HOME"
  main="$(main_class "$APP_DIR")"
  local cmd=("$JAVA")
  if [ -f conf/jvm.options ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      line="$(printf '%s' "$line" | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
      case "$line" in '' | '#'*) continue ;; esac
      cmd+=("$line")
    done < conf/jvm.options
  fi
  cmd+=("$MARKER" -cp "conf:lib/*:lib/extra/*" "$main")
  mkdir -p logs
  echo "启动：${cmd[*]}"
  # setsid 放进新的会话，SSH 断开、终端退出都不影响程序；没有 setsid（macOS）时用 nohup
  if command -v setsid >/dev/null 2>&1; then
    setsid "${cmd[@]}" < /dev/null > logs/console.log 2>&1 &
  else
    nohup "${cmd[@]}" < /dev/null > logs/console.log 2>&1 &
  fi
  echo $! > "$PID_FILE"
  sleep "$START_CHECK_SECONDS"
  if pid="$(running_pid)"; then
    echo "已启动，pid=$pid"
    return 0
  fi
  rm -f "$PID_FILE"
  echo "程序启动后 $START_CHECK_SECONDS 秒内退出，logs/console.log 最后 30 行：" >&2
  tail -n 30 logs/console.log >&2 || true
  exit 1
}

cmd_stop() {
  local pid
  if ! pid="$(running_pid)"; then
    rm -f "$PID_FILE"
    echo "没有在运行"
    return 0
  fi
  echo "停止，pid=$pid"
  kill -TERM "$pid" 2>/dev/null || true
  if ! wait_exit "$STOP_TIMEOUT"; then
    echo "$STOP_TIMEOUT 秒内没有退出，强制结束"
    kill -KILL "$pid" 2>/dev/null || true
    wait_exit "$KILL_TIMEOUT" || fail "无法结束进程 $pid"
  fi
  rm -f "$PID_FILE"
  echo "已停止"
}

# 每行 key=value：deployed、running；运行中时还有 pid、elapsed（秒）、rss（KB）、cpu_ms（累计 CPU 时间）
cmd_status() {
  local pid ticks
  if ls lib/*.jar >/dev/null 2>&1; then echo "deployed=1"; else echo "deployed=0"; fi
  if ! pid="$(running_pid)"; then
    echo "running=0"
    return 0
  fi
  echo "running=1"
  echo "pid=$pid"
  echo "elapsed=$(ps -o etimes= -p "$pid" 2>/dev/null | tr -d ' ')"
  echo "rss=$(ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ')"
  if [ -r "/proc/$pid/stat" ]; then
    ticks="$(getconf CLK_TCK 2>/dev/null || echo 100)"
    # 去掉「pid (进程名) 」后第 12、13 列为 utime、stime
    sed 's/^.*) //' "/proc/$pid/stat" | awk -v t="$ticks" '{printf "cpu_ms=%d\n", ($12 + $13) * 1000 / t}'
  fi
}

was_running() {
  running_pid >/dev/null
}

# ---------------------------------------------------------------- 日志

cmd_log() {
  local file="${1:-server.log}" lines="${2:-200}"
  case " $LOG_FILES " in *" $file "*) ;; *) fail "只能查看 $LOG_FILES" ;; esac
  case "$lines" in '' | *[!0-9]*) fail "行数不合法：$lines" ;; esac
  [ -f "logs/$file" ] || return 0
  tail -c 1048576 "logs/$file" | tail -n "$lines"
}

# ---------------------------------------------------------------- 发布、回滚

# 新建 backup/<时间>（同一秒已有时等到下一秒），输出路径
new_backup_dir() {
  local name
  mkdir -p backup
  name="$(date +%Y%m%d-%H%M%S)"
  while [ -e "backup/$name" ]; do
    sleep 1
    name="$(date +%Y%m%d-%H%M%S)"
  done
  mkdir "backup/$name"
  echo "$APP_DIR/backup/$name"
}

prune_backups() {
  local keep="$1" old
  [ -d backup ] || return 0
  ls -1 backup | grep -E '^[0-9]{8}-[0-9]{6}$' | sort -r | tail -n +$((keep + 1)) | while read -r old; do
    rm -rf "backup/$old"
    echo "删除旧备份 $old"
  done || true
}

# 把 $1（备份目录）里 $2 列出的目录放回程序目录，用于替换失败时恢复
restore_from() {
  local from="$1" name
  for name in $2; do
    if [ -e "$from/$name" ]; then
      rm -rf "${APP_DIR:?}/$name"
      mv "$from/$name" "$APP_DIR/$name"
    fi
  done
}

cmd_deploy() {
  local zip="" overwrite_conf=0 keep="$BACKUP_KEEP_DEFAULT"
  while [ $# -gt 0 ]; do
    case "$1" in
      --overwrite-conf) overwrite_conf=1 ;;
      --keep) keep="${2:-}"; shift ;;
      *) zip="$1" ;;
    esac
    shift
  done
  [ -n "$zip" ] || fail "用法：app.sh deploy <zip> [--overwrite-conf] [--keep 份数]"
  [ -f "$zip" ] || fail "找不到程序包：$zip"
  case "$keep" in '' | *[!0-9]* | 0) fail "--keep 应为正整数" ;; esac
  command -v unzip >/dev/null 2>&1 || fail "找不到 unzip"

  DEPLOY_TMP="$(mktemp -d "${TMPDIR:-/tmp}/app-deploy.XXXXXX")"
  trap 'rm -rf "$DEPLOY_TMP"' EXIT
  local tmp="$DEPLOY_TMP"

  echo "== 解压程序包"
  unzip -q "$zip" -d "$tmp/pkg"
  # 包里直接是 lib，或者外面包了一层目录
  local root="" sub
  if [ -d "$tmp/pkg/lib" ]; then
    root="$tmp/pkg"
  else
    sub="$(find "$tmp/pkg" -mindepth 1 -maxdepth 1 -type d)"
    if [ -n "$sub" ] && [ "$(printf '%s\n' "$sub" | wc -l | tr -d ' ')" = 1 ] && [ -d "$sub/lib" ]; then root="$sub"; fi
  fi
  [ -n "$root" ] || fail "程序包里没有 lib 目录，应为 lib、conf、excel 打成的 zip"
  # 停止旧版本之前先确认新包能启动
  local main
  main="$(main_class "$root")"
  echo "Main-Class：$main"

  local replace="" name
  for name in $PACKAGE_DIRS; do
    [ -e "$root/$name" ] || continue
    if [ "$name" = conf ] && [ -d conf ] && [ "$overwrite_conf" != 1 ]; then continue; fi
    replace="$replace $name"
  done
  echo "替换：$replace"

  local running=0
  if was_running; then
    running=1
    echo "== 停止"
    cmd_stop
  fi

  echo "== 备份并替换"
  local backup moved=""
  backup="$(new_backup_dir)"
  for name in $PACKAGE_DIRS; do
    [ -e "$name" ] || continue
    case " $replace " in
      *" $name "*) ;;
      *) cp -a "$name" "$backup/$name" ;;  # 不替换的也留一份，回滚时整体换回
    esac
  done
  local failed=0
  for name in $replace; do
    if [ -e "$name" ]; then
      mv "$name" "$backup/$name" || { failed=1; break; }
    fi
    moved="$moved $name"
    mv "$root/$name" "$APP_DIR/$name" || { failed=1; break; }
  done
  if [ "$failed" = 1 ]; then
    echo "替换失败，恢复原来的版本" >&2
    restore_from "$backup" "$moved"
    rm -rf "$backup"
    if [ "$running" = 1 ]; then cmd_start; fi
    exit 1
  fi
  if rmdir "$backup" 2>/dev/null; then
    echo "第一次发布，没有旧版本需要备份"
  else
    echo "旧版本备份到 $(basename "$backup")：$(ls -1 "$backup" | tr '\n' ' ')"
    prune_backups "$keep"
  fi

  if [ "$running" = 1 ]; then
    echo "== 启动"
    cmd_start
  else
    echo "发布前没有在运行，不启动"
  fi
}

cmd_backups() {
  [ -d backup ] || return 0
  ls -1 backup | grep -E '^[0-9]{8}-[0-9]{6}$' | sort -r || true
}

cmd_rollback() {
  local name="${1:-}" from
  echo "$name" | grep -qE '^[0-9]{8}-[0-9]{6}$' || fail "备份名不合法：$name"
  from="$APP_DIR/backup/$name"
  [ -d "$from" ] || fail "备份不存在：$name"
  ls "$from"/lib/*.jar >/dev/null 2>&1 || fail "备份 $name 里没有 lib/*.jar"

  local running=0
  if was_running; then
    running=1
    echo "== 停止"
    cmd_stop
  fi

  echo "== 换回 $name"
  local now dir
  now="$(new_backup_dir)"
  for dir in $PACKAGE_DIRS; do
    [ -e "$from/$dir" ] || continue
    if [ -e "$dir" ]; then mv "$dir" "$now/$dir"; fi
    mv "$from/$dir" "$APP_DIR/$dir"
  done
  rm -rf "$from"
  echo "已换回 $name，换下来的版本备份为 $(basename "$now")"

  if [ "$running" = 1 ]; then
    echo "== 启动"
    cmd_start
  else
    echo "回滚前没有在运行，不启动"
  fi
}

# ---------------------------------------------------------------- 配置文件

# conf 下的相对路径：字母、数字、_ . - /，不能有 . 和 .. 段
check_conf_file() {
  local file="$1"
  echo "$file" | grep -qE '^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$' || fail "文件名不合法：$file"
  case "/$file/" in */./* | */../*) fail "文件名不合法：$file" ;; esac
}

cmd_conf_list() {
  [ -d conf ] || return 0
  (cd conf && find . -type f | sed 's#^\./##' | sort)
}

cmd_conf_get() {
  local file="${1:-}"
  check_conf_file "$file"
  [ -f "conf/$file" ] || fail "文件不存在：conf/$file"
  [ "$(wc -c < "conf/$file" | tr -d ' ')" -le "$CONF_MAX_SIZE" ] || fail "文件超过 1MB，不能在线查看：conf/$file"
  cat "conf/$file"
}

# 旧文件先备份到 backup-conf/<时间>/，新内容写到临时文件再替换
cmd_conf_put() {
  local file="${1:-}" tmp backup
  check_conf_file "$file"
  mkdir -p "conf/$(dirname "$file")"
  tmp="conf/$file.uploading"
  head -c $((CONF_MAX_SIZE + 1)) > "$tmp"
  if [ "$(wc -c < "$tmp" | tr -d ' ')" -gt "$CONF_MAX_SIZE" ]; then
    rm -f "$tmp"
    fail "文件不能超过 1MB"
  fi
  if [ -f "conf/$file" ]; then
    backup="backup-conf/$(date +%Y%m%d-%H%M%S)"
    case "$file" in */*) backup="$backup/$(dirname "$file")" ;; esac
    mkdir -p "$backup"
    cp -p "conf/$file" "$backup/"
    echo "旧文件备份到 $backup/$(basename "$file")"
  fi
  mv -f "$tmp" "conf/$file"
  echo "已保存 conf/$file"
}

# ---------------------------------------------------------------- 入口

command="${1:-}"
[ $# -gt 0 ] && shift
case "$command" in
  start) cmd_start ;;
  stop) cmd_stop ;;
  restart) cmd_stop; cmd_start ;;
  status) cmd_status ;;
  log) cmd_log "$@" ;;
  deploy) cmd_deploy "$@" ;;
  backups) cmd_backups ;;
  rollback) cmd_rollback "$@" ;;
  conf-list) cmd_conf_list ;;
  conf-get) cmd_conf_get "$@" ;;
  conf-put) cmd_conf_put "$@" ;;
  *) fail "用法：bin/app.sh start|stop|restart|status|log|deploy|backups|rollback|conf-list|conf-get|conf-put" ;;
esac
