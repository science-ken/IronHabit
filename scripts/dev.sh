#!/usr/bin/env bash
# IronHabit 本机开发/验证助手
#
# 目的：这台机器的开发环境有几处固定坑，每次重新踩很费时间，这里一次性固化：
#   1) Git-Bash 的 PATH 被破坏（无 git/dirname/head）→ 必须前置 PortableGit/cmd
#   2) 必须用 POSIX 启动器 bin/gradle，gradle.bat 会 EXIT=127
#   3) gradle 操作必须绕过沙箱（否则 dex 阶段写 build/intermediates 被拒）
#   4) 每次新 bash 会话 adb daemon 会重启 → connect 与后续操作必须在同一条命令里
#   5) Windows 版 adb 不认 /d/... 路径，push 前要先 cd
#
# 用法：
#   bash scripts/dev.sh build            # 编译 debug 包
#   bash scripts/dev.sh test             # 单元测试
#   bash scripts/dev.sh check            # 编译 + 测试（一步到位）
#   bash scripts/dev.sh install          # 连模拟器 → 安装 → 启动（自动修好后重建）
#   bash scripts/dev.sh shot <文件名>     # 截一张图到 shots/
#   bash scripts/dev.sh ui               # 导出当前界面层级（用于精确定位坐标）
#   bash scripts/dev.sh sql              # 拉取数据库（含 -wal，否则看不到新写入）

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/usr/bin:/bin:/c/Users/science/.workbuddy/binaries/PortableGit/versions/1.2.0/cmd:/c/Users/science/android-tools/jdk17/bin:$PATH"
export JAVA_HOME="C:\\Users\\science\\android-tools\\jdk17"
export ANDROID_HOME="C:\\Users\\science\\AppData\\Local\\Android\\Sdk"
GRADLE="/c/Users/science/android-tools/gradle-8.9/bin/gradle"
ADB="/c/Users/science/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEVICE="127.0.0.1:16384"
SHOTS="/d/Workbuddy data/2026-09-14-09-31-06/shots"

# 模拟器没起来时自动拉起（MuMu 只起窗口不起 VM 是常见坑，必须走 manager launch）
ensure_device() {
  "$ADB" connect "$DEVICE" >/dev/null 2>&1
  if ! "$ADB" devices 2>/dev/null | grep -q "^$DEVICE" ; then
    echo "模拟器未连接，正在启动 MuMu 虚拟机…"
    "/d/mumu/MuMuPlayer/nx_main/MuMuManager.exe" control --vmindex 0 launch >/dev/null 2>&1
    for _ in 1 2 3 4 5 6 7 8; do
      sleep 20
      "$ADB" connect "$DEVICE" >/dev/null 2>&1
      if "$ADB" devices 2>/dev/null | grep -qE "^$DEVICE[[:space:]]+device"; then
        echo "已连接 $DEVICE"
        return 0
      fi
    done
    echo "❌ 模拟器启动失败，请手动打开 MuMu 后重试"
    return 1
  fi
}

case "${1:-help}" in
  build)
    cd "$ROOT" && "$GRADLE" :app:assembleDebug --console=plain ;;
  test)
    cd "$ROOT" && "$GRADLE" :app:testDebugUnitTest --console=plain ;;
  check)
    cd "$ROOT" && "$GRADLE" :app:assembleDebug :app:testDebugUnitTest --console=plain ;;
  install)
    ensure_device || exit 1
    cd "$ROOT/app/build/outputs/apk/debug" || exit 1
    "$ADB" -s "$DEVICE" install -r app-debug.apk || exit 1
    "$ADB" -s "$DEVICE" shell am start -n com.ironhabit.app/.MainActivity >/dev/null 2>&1
    sleep 10
    echo "已安装并启动" ;;
  shot)
    ensure_device || exit 1
    "$ADB" -s "$DEVICE" exec-out screencap -p > "$SHOTS/${2:-shot}.png"
    echo "已保存 $SHOTS/${2:-shot}.png" ;;
  ui)
    ensure_device || exit 1
    "$ADB" -s "$DEVICE" exec-out uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    "$ADB" -s "$DEVICE" shell cat /data/local/tmp/ui.xml 2>/dev/null | grep -oE 'text="[^"]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
      | sed 's/ resource-id.*bounds="\[/  bounds=[/; s/\]"//' ;;
  sql)
    ensure_device || exit 1
    mkdir -p "$SHOTS/db/wal"
    cd "$SHOTS/db/wal" || exit 1
    rm -f ironhabit.db ironhabit.db-wal ironhabit.db-shm
    for f in ironhabit.db ironhabit.db-wal ironhabit.db-shm; do
      "$ADB" -s "$DEVICE" exec-out run-as com.ironhabit.app cat "databases/$f" > "$f" 2>/dev/null
    done
    echo "已拉取（含 WAL）到 $SHOTS/db/wal —— 只拉主库会漏掉最新写入" ;;
  *)
    sed -n '2,20p' "${BASH_SOURCE[0]}" ;;
esac
