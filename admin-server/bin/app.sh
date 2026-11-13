#!/usr/bin/env bash
# 程序管理脚本。每个项目的 bin/app.sh 都是这一份，和 conf 一样随 mvn package 打进程序包。
# 程序目录就是脚本所在 bin 的上一层，不需要参数或配置。在任何目录执行都一样，手动执行和管理后台执行的是同一件事。
#
# 程序目录的格式：
#   bin/app.sh             本脚本
#   conf/                  配置文件，放在 classpath 最前面；conf/jvm.options 为 JVM 参数（每行一个，# 开头和空行忽略，可以没有）
#   lib/                   jar，其中恰好一个 jar 的 MANIFEST 带 Main-Class；lib/extra/ 也在 classpath 上
#   excel/                 可选
#   logs/                  程序自己写的日志；console.log 为最近一次启动的控制台输出（JVM 起不来时看这里）
#   app.pid                运行中的进程号
#   backup/<时间>/          发布、回滚前的 bin、lib、conf、excel
#   backup-conf/<时间>/     在线修改配置前的旧文件
#
# 启动命令：cd <程序目录> && java <jvm.options> -Dhost.app.dir=<程序目录> -cp conf:lib/*:lib/extra/* <Main-Class>
# -Dhost.app.dir 用来确认 app.pid 里的进程确实是这个程序。
#
# 用法：bin/app.sh <命令> [参数]
#   start | stop | restart | status
#   log [server.log|error.log|console.log] [行数]
#   deploy <程序目录> [--overwrite-conf] [--keep 份数]   在解压出来的新程序包里执行，把这个包发布到程序目录
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
PACKAGE_DIRS="bin lib conf excel"

fail() {
  echo "$*" >&2
  exit 1
}

# ---------------------------------------------------------------- 进程

# <目录>/app.pid 里的进程在运行、且命令行带着这个目录的标记时输出 pid；目录默认为本程序
running_pid() {
  local dir="${1:-$APP_DIR}" pid args
  [ -f "$dir/app.pid" ] || return 1
  pid="$(tr -dc '0-9' < "$dir/app.pid")"
  [ -n "$pid" ] || return 1
  args="$(ps -ww -o args= -p "$pid" 2>/dev/null)" || return 1
  case "$args" in *"-Dhost.app.dir=$dir "*) echo "$pid" ;; *) return 1 ;; esac
}

# 最多等 $2 秒，<目录>的进程退出返回 0
wait_exit() {
  local i
  for ((i = 0; i < $2 * 5; i++)); do
    running_pid "$1" >/dev/null || return 0
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
  command -v "$JAVA" >/dev/null 2>&1 || fail "找不到 java（$JAVA）：主机上还没有安装 java，请先安装，或者设置 JAVA_HOME"
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

# 停止<目录>的程序，目录默认为本程序
cmd_stop() {
  local dir="${1:-$APP_DIR}" pid
  if ! pid="$(running_pid "$dir")"; then
    rm -f "$dir/app.pid"
    echo "没有在运行"
    return 0
  fi
  echo "停止，pid=$pid"
  kill -TERM "$pid" 2>/dev/null || true
  if ! wait_exit "$dir" "$STOP_TIMEOUT"; then
    echo "$STOP_TIMEOUT 秒内没有退出，强制结束"
    kill -KILL "$pid" 2>/dev/null || true
    wait_exit "$dir" "$KILL_TIMEOUT" || fail "无法结束进程 $pid"
  fi
  rm -f "$dir/app.pid"
  echo "已停止"
}

# 每行 key=value：deployed、running；运行中时还有 pid、elapsed（秒）
cmd_status() {
  local pid
  if ls lib/*.jar >/dev/null 2>&1; then echo "deployed=1"; else echo "deployed=0"; fi
  if ! pid="$(running_pid)"; then
    echo "running=0"
    return 0
  fi
  echo "running=1"
  echo "pid=$pid"
  echo "elapsed=$(ps -o etimes= -p "$pid" 2>/dev/null | tr -d ' ')"
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

# 在当前目录新建 backup/<时间>（同一秒已有时等到下一秒），输出路径
new_backup_dir() {
  local name
  mkdir -p backup
  name="$(date +%Y%m%d-%H%M%S)"
  while [ -e "backup/$name" ]; do
    sleep 1
    name="$(date +%Y%m%d-%H%M%S)"
  done
  mkdir "backup/$name"
  echo "$PWD/backup/$name"
}

prune_backups() {
  local keep="$1" old
  [ -d backup ] || return 0
  ls -1 backup | grep -E '^[0-9]{8}-[0-9]{6}$' | sort -r | tail -n +$((keep + 1)) | while read -r old; do
    rm -rf "backup/$old"
    echo "删除旧备份 $old"
  done || true
}

# 把 $1（备份目录）里 $2 列出的目录放回当前目录，用于替换失败时恢复
restore_from() {
  local from="$1" name
  for name in $2; do
    if [ -e "$from/$name" ]; then
      rm -rf "./$name"
      mv "$from/$name" "./$name"
    fi
  done
}

# 在解压出来的新程序包里执行（APP_DIR 是这个包），把包里的 bin、lib、conf、excel 发布到 <程序目录>
cmd_deploy() {
  local target="" overwrite_conf=0 keep="$BACKUP_KEEP_DEFAULT"
  while [ $# -gt 0 ]; do
    case "$1" in
      --overwrite-conf) overwrite_conf=1 ;;
      --keep) keep="${2:-}"; shift ;;
      *) target="$1" ;;
    esac
    shift
  done
  [ -n "$target" ] || fail "用法：在解压出来的程序包里执行 bin/app.sh deploy <程序目录> [--overwrite-conf] [--keep 份数]"
  case "$target" in /*) ;; *) fail "程序目录必须是绝对路径：$target" ;; esac
  case "$keep" in '' | *[!0-9]* | 0) fail "--keep 应为正整数" ;; esac
  mkdir -p "$target"
  target="$(cd "$target" && pwd -P)"
  [ "$target" != "$APP_DIR" ] || fail "deploy 要在解压出来的新程序包里执行，不能在程序目录里执行"

  # 停止旧版本之前先确认新包能启动
  local main
  main="$(main_class "$APP_DIR")"
  echo "Main-Class：$main"

  cd "$target"
  local replace="" name
  for name in $PACKAGE_DIRS; do
    [ -e "$APP_DIR/$name" ] || continue
    if [ "$name" = conf ] && [ -d conf ] && [ "$overwrite_conf" != 1 ]; then continue; fi
    replace="$replace $name"
  done
  echo "替换：$replace"

  local running=0
  if running_pid "$target" >/dev/null; then
    running=1
    echo "== 停止"
    cmd_stop "$target"
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
    mv "$APP_DIR/$name" "./$name" || { failed=1; break; }
  done
  chmod +x bin/*.sh 2>/dev/null || true
  if [ "$failed" = 1 ]; then
    echo "替换失败，恢复原来的版本" >&2
    restore_from "$backup" "$moved"
    rm -rf "$backup"
    if [ "$running" = 1 ]; then bash "$target/bin/app.sh" start; fi
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
    bash "$target/bin/app.sh" start
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
  if running_pid >/dev/null; then
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
    bash "$APP_DIR/bin/app.sh" start  # 用换回来的 bin/app.sh
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
