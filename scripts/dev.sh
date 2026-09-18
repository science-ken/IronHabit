#!/usr/bin/env bash
# IronHabit 本机开发/验证助手（**统一入口，禁止再手打命令**）
#
# 目的：这台机器的开发/测试环境有几处固定坑，每次重新踩很费时间，这里一次性固化：
#   1) Git-Bash 的 PATH 被破坏（无 git/dirname/head）→ 必须前置 PortableGit/cmd
#   2) 必须用 POSIX 启动器 bin/gradle，gradle.bat 会 EXIT=127
#   3) 每次新 bash 会话 adb daemon 会重启 → connect 与后续操作必须同一条命令
#   4) MuMu 只起窗口不起 VM → 必须走 MuMuManager launch_player
#   5) **MuMu 实例重启后 adb 端口会变**（16384→16385→16448…）→ 本脚本从 MuMuManager 动态取
#   6) Windows 版 adb 不认 /d/... 路径 → 先 cd 再操作
#   7) adb 拉库只看主库会漏 WAL → 必须三件套一起拉且命名严格匹配
#   8) `input swipe` 时长 <500ms 不滚动 → 统一默认 1500ms
#
# 设备：默认用用户新建的测试设备 MuMu 实例 2「软件测试」（单 display / 1080x1920 原生竖屏，
#       点击与截图都用原生坐标，无需 -d）。要换实例：`VM=0 bash scripts/dev.sh ...`
#
# 用法：
#   bash scripts/dev.sh info                # 打印设备/端口/分辨率/是否就绪
#   bash scripts/dev.sh boot                # 启动 MuMu 实例并等到 Android 就绪
#   bash scripts/dev.sh build|test|check    # 编译 / 单测 / 两者
#   bash scripts/dev.sh install             # 装 debug 包并启动 App
#   bash scripts/dev.sh app                 # 仅启动 App
#   bash scripts/dev.sh crash                # 看崩溃缓冲（空=无崩溃）
#   bash scripts/dev.sh ui                  # 导出界面文字+坐标（用于精确定位）
#   bash scripts/dev.sh tap X Y             # 点按（原生坐标）
#   bash scripts/dev.sh swipe X1 Y1 X2 Y2 [ms]
#   bash scripts/dev.sh shot [文件名]        # 截图到 shots/
#   bash scripts/dev.sh sql                 # 拉数据库（含 WAL）到 shots/db/wal
#   bash scripts/dev.sh q "SELECT ..."      # 在拉下来的库上执行 SQL
#   bash scripts/dev.sh verify              # = install + crash + shot（冒烟）

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/usr/bin:/bin:/c/Users/science/.workbuddy/binaries/PortableGit/versions/1.2.0/cmd:/c/Users/science/android-tools/jdk17/bin:$PATH"
export JAVA_HOME="C:\\Users\\science\\android-tools\\jdk17"
export ANDROID_HOME="C:\\Users\\science\\AppData\\Local\\Android\\Sdk"
GRADLE="/c/Users/science/android-tools/gradle-8.9/bin/gradle"
ADB="/c/Users/science/AppData/Local/Android/Sdk/platform-tools/adb.exe"
MUMU="/d/mumu/MuMuPlayer/nx_main/MuMuManager.exe"
PY="/c/Users/science/.workbuddy/binaries/python/versions/3.13.12/python.exe"
PKG="com.ironhabit.app"
DBNAME="ironhabit.db"          # 真实库名（不是包名！）
VM="${VM:-2}"
# 当前活动树（纯 ASCII，可含空格：下面每处用法都带引号，Python 侧走 r'' 字面量）
SHOTS="D:/fitness-app-v204 1/shots"   # 用 D:/ 形式：Git Bash 与 Windows Python 都能读
DBDIR="$SHOTS/db/wal"

# MuMu 实例 → adb 端口（每次重启可能变，必须动态取）
adb_port() {
  "$MUMU" info -v "$VM" 2>/dev/null | grep -o '"adb_port": [0-9]*' | grep -o '[0-9]*'
}
device() { echo "127.0.0.1:$(adb_port)"; }

ensure_device() {
  local p; p="$(adb_port)"
  if [ -z "$p" ]; then echo "❌ 取不到实例 $VM 的 adb 端口（实例存在吗？）"; return 1; fi
  local D="127.0.0.1:$p"
  "$ADB" start-server >/dev/null 2>&1
  "$ADB" connect "$D" >/dev/null 2>&1
  if ! "$ADB" devices 2>/dev/null | grep -qE "^$D[[:space:]]+device"; then
    echo "设备未就绪（实例 $VM）。先跑：bash scripts/dev.sh boot"
    return 1
  fi
  return 0
}

boot() {
  started="$("$MUMU" info -v "$VM" 2>/dev/null | grep -o '"is_android_started": [a-z]*')"
  if [ "$started" = '"is_android_started": true' ]; then echo "实例 $VM 已在运行"; else
    echo "启动实例 $VM …"
    "$MUMU" api -v "$VM" launch_player >/dev/null 2>&1
  fi
  for i in $(seq 1 15); do
    sleep 8
    started="$("$MUMU" info -v "$VM" 2>/dev/null | grep -o '"is_android_started": [a-z]*')"
    if [ "$started" = '"is_android_started": true' ]; then
      echo "Android 已就绪（第 $i 次轮询），adb 端口 $(adb_port)"
      ensure_device
      return $?
    fi
    echo "  等待中…（$i）"
  done
  echo "❌ 启动超时"; return 1
}

# 把当前界面层级拉到 $SHOTS/ui.xml（ui / taptext 共用）
pull_ui() {
  "$ADB" -s "$(device)" shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
  "$ADB" -s "$(device)" exec-out cat /data/local/tmp/ui.xml > "$SHOTS/ui.xml" 2>/dev/null
  [ -s "$SHOTS/ui.xml" ] || { echo "❌ 界面层级拉取失败（App 未在前台？）"; return 1; }
}

case "${1:-help}" in
  info)
    echo "实例        : $VM"
    "$MUMU" info -v "$VM" 2>/dev/null | grep -E '"name"|"android_version"|is_android_started|adb_port'
    if ensure_device; then
      D="$(device)"
      echo "adb         : $D"
      printf "机型        : "; "$ADB" -s "$D" shell getprop ro.product.model
      printf "分辨率      : "; "$ADB" -s "$D" shell wm size | tail -1
      printf "显示数      : "; "$ADB" -s "$D" shell "dumpsys SurfaceFlinger --display-id" 2>/dev/null | wc -l
      printf "已装本 App  : "; "$ADB" -s "$D" shell "pm list packages | grep -c $PKG"
    fi ;;

  boot) boot ;;

  build) cd "$ROOT" && "$GRADLE" :app:assembleDebug --console=plain ;;
  # `test` 强制真实执行（--rerun-tasks）：否则 Gradle 可能 UP-TO-DATE / 命中 build cache 而不真跑，
  # 让"测试通过"变成假象（2026-09-16 QA 复验时踩到：首次命中了 FROM-CACHE）。
  # 只想快速看编译是否过 → 用 check。
  test)  cd "$ROOT" && "$GRADLE" :app:testDebugUnitTest --rerun-tasks --console=plain ;;
  check) cd "$ROOT" && "$GRADLE" :app:assembleDebug :app:testDebugUnitTest --console=plain ;;

  install)
    ensure_device || exit 1; D="$(device)"
    cd "$ROOT/app/build/outputs/apk/debug" || exit 1
    "$ADB" -s "$D" install -r app-debug.apk || exit 1
    "$ADB" -s "$D" logcat -c >/dev/null 2>&1
    "$ADB" -s "$D" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    sleep 8
    echo "已安装并启动（用 crash 子命令看是否崩溃）" ;;

  app)
    ensure_device || exit 1; D="$(device)"
    "$ADB" -s "$D" logcat -c >/dev/null 2>&1
    "$ADB" -s "$D" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; echo "已启动" ;;

  crash)
    ensure_device || exit 1; D="$(device)"
    out="$("$ADB" -s "$D" shell "logcat -d -b crash | tail -30")"
    if [ -z "$out" ]; then echo "✅ 崩溃缓冲为空"; else echo "$out"; fi ;;

  ui)
    ensure_device || exit 1
    pull_ui
    "$PY" -c "
import re
s=open(r'$SHOTS/ui.xml',encoding='utf-8',errors='replace').read()
rows=[]
for m in re.finditer(r'text=\"([^\"]+)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', s):
    t=m.group(1); x1,y1,x2,y2=map(int,m.groups()[1:])
    rows.append(((y1+y2)//2,(x1+x2)//2,f'{t}\t{(x1+x2)//2}\t{(y1+y2)//2}'))
rows.sort()
for _,_,line in rows: print(line)
" ;;

  # 按文本点击：bash scripts/dev.sh taptext 早餐   （子串匹配，取最靠上的那个）
  taptext)
    ensure_device || exit 1; D="$(device)"
    pull_ui
    xy=$("$PY" -c "
import re
s=open(r'$SHOTS/ui.xml',encoding='utf-8',errors='replace').read()
pat='''$2'''
hits=[]
for m in re.finditer(r'text=\"([^\"]+)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', s):
    if pat in m.group(1):
        x1,y1,x2,y2=map(int,m.groups()[1:]); hits.append(((y1+y2)//2,(x1+x2)//2))
if hits:
    hits.sort(); print(hits[0][1], hits[0][0])
")
    if [ -z "$xy" ]; then echo "❌ 未找到文本: $2"; exit 1; fi
    "$ADB" -s "$D" shell input tap $xy
    echo "taptext '$2' → $xy" ;;

  tap)
    ensure_device || exit 1; D="$(device)"
    "$ADB" -s "$D" shell input tap "$2" "$3"; echo "tap $2 $3" ;;

  # 返回：bash scripts/dev.sh back   （二级页没有底部导航，必须先返回）
  back)
    ensure_device || exit 1; D="$(device)"
    "$ADB" -s "$D" shell input keyevent 4; echo "back" ;;

  swipe)
    ensure_device || exit 1; D="$(device)"
    "$ADB" -s "$D" shell input swipe "$2" "$3" "$4" "$5" "${6:-1500}"
    echo "swipe $2 $3 → $4 $5 (${6:-1500}ms)" ;;

  shot)
    ensure_device || exit 1; D="$(device)"; mkdir -p "$SHOTS"
    "$ADB" -s "$D" exec-out screencap -p > "$SHOTS/${2:-shot}.png"
    echo "已保存 $SHOTS/${2:-shot}.png" ;;

  sql)
    ensure_device || exit 1; D="$(device)"; mkdir -p "$DBDIR"; cd "$DBDIR" || exit 1
    rm -f "$DBNAME" "$DBNAME-wal" "$DBNAME-shm"
    for f in "$DBNAME" "$DBNAME-wal" "$DBNAME-shm"; do
      "$ADB" -s "$D" exec-out run-as "$PKG" cat "databases/$f" > "$f" 2>/dev/null
    done
    if [ ! -s "$DBNAME" ]; then echo "❌ 拉库失败（文件为空）：App 是否装过并启动过？"; exit 1; fi
    echo "已拉取（含 WAL）到 $DBDIR —— 只拉主库会漏掉最新写入" ;;

  q)
    cd "$DBDIR" || exit 1
    [ -s "$DBNAME" ] || { echo "❌ 先跑：bash scripts/dev.sh sql"; exit 1; }
    "$PY" -c "
import sqlite3,sys
c=sqlite3.connect('$DBNAME')
try:
    for r in c.execute('''$2''').fetchall(): print(r)
except sqlite3.Error as e:
    print('SQL 错误:', e); sys.exit(1)
" ;;

  verify)
    "$0" install || exit 1
    "$0" crash
    "$0" shot "verify-$(date +%H%M)" ;;

  *) sed -n '2,30p' "${BASH_SOURCE[0]}" ;;
esac
