#!/bin/bash
set -euo pipefail

DURATION=30
OUTPUT=""
DISPLAY_ID=1
AUDIO=0
CLICKS=0
COUNTDOWN=3
SELECT_WINDOW=0
OPEN_AFTER=0

usage() {
  cat <<'EOF'
MineThunder 录屏脚本（macOS）

用法:
  ./tools/record-screen.sh [选项]

选项:
  -d <秒>     录制时长，默认 30
  -o <文件>   输出文件（默认 ~/Movies/MineThunder/recording-时间戳.mov）
  -D <编号>   显示器编号，默认 1（主屏）
  -a          录制系统音频/麦克风（会请求权限）
  -k          在视频中显示鼠标点击
  -c <秒>     开始前倒计时，默认 3
  -w          交互选择窗口（录制单个窗口）
  -p          录制完成后用默认播放器打开
  -h          显示帮助

示例:
  ./tools/record-screen.sh -d 20
  ./tools/record-screen.sh -d 15 -a -p
  ./tools/record-screen.sh -w -d 30 -o ~/Desktop/wt.mov

注意:
  首次使用需在「系统设置 → 隐私与安全性 → 屏幕录制」中
  授权给终端 / iTerm / opencode，否则可能录出黑屏。
EOF
}

while getopts ":d:o:D:akc:wph" opt; do
  case "$opt" in
    d) DURATION="$OPTARG" ;;
    o) OUTPUT="$OPTARG" ;;
    D) DISPLAY_ID="$OPTARG" ;;
    a) AUDIO=1 ;;
    k) CLICKS=1 ;;
    c) COUNTDOWN="$OPTARG" ;;
    w) SELECT_WINDOW=1 ;;
    p) OPEN_AFTER=1 ;;
    h) usage; exit 0 ;;
    :) echo "缺少参数: -$OPTARG" >&2; usage; exit 1 ;;
    \?) echo "未知选项: -$OPTARG" >&2; usage; exit 1 ;;
  esac
done

if ! [[ "$DURATION" =~ ^[0-9]+$ ]] || [ "$DURATION" -le 0 ]; then
  echo "录制时长必须是正整数秒" >&2
  exit 1
fi

if ! [[ "$COUNTDOWN" =~ ^[0-9]+$ ]]; then
  echo "倒计时必须是整数秒" >&2
  exit 1
fi

if ! command -v screencapture >/dev/null 2>&1; then
  echo "找不到 screencapture（仅支持 macOS）" >&2
  exit 1
fi

if [ -z "$OUTPUT" ]; then
  OUTPUT="$HOME/Movies/MineThunder/recording-$(date +%Y%m%d-%H%M%S).mov"
fi

mkdir -p "$(dirname "$OUTPUT")"

echo "输出文件: $OUTPUT"
for ((i=COUNTDOWN; i>0; i--)); do
  printf "\r%2d 秒后开始录制..." "$i"
  sleep 1
done
printf "\r开始录制 %s 秒...\n" "$DURATION"

ARGS=(-v "-V${DURATION}" "-D${DISPLAY_ID}")
[ "$AUDIO" -eq 1 ] && ARGS+=(-g)
[ "$CLICKS" -eq 1 ] && ARGS+=(-k)
[ "$SELECT_WINDOW" -eq 1 ] && ARGS+=(-w)

set +e
screencapture "${ARGS[@]}" "$OUTPUT"
STATUS=$?
set -e

if [ "$STATUS" -ne 0 ]; then
  echo "录制失败（退出码 $STATUS）。请检查屏幕录制权限。" >&2
  exit "$STATUS"
fi

if [ ! -s "$OUTPUT" ]; then
  echo "录制文件为空，可能没有屏幕录制权限。" >&2
  exit 1
fi

echo "已保存: $OUTPUT"
[ "$OPEN_AFTER" -eq 1 ] && open "$OUTPUT"
