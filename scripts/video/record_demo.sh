#!/bin/bash
# 에뮬레이터 화면을 녹화하면서 scenario_demo.sh를 돌린다. 장면 시작 시각을 scenes.tsv에 남긴다.
set -u
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH" JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
OUT="$(cd "$(dirname "$0")" && pwd)/rec"; rm -rf "$OUT"; mkdir -p "$OUT"
adb forward tcp:8088 tcp:8088 >/dev/null 2>&1
adb shell rm -f /sdcard/rec_*.mp4
( i=0; while [ ! -f "$OUT/stop" ]; do python3 -c "import time;print($i, time.time())" >> "$OUT/segs"; adb shell screenrecord --size 1920x1080 --bit-rate 16000000 --time-limit 170 /sdcard/rec_$i.mp4; i=$((i+1)); done ) &
REC=$!
sleep 2
T0=$(python3 -c 'import time;print(time.time())')
echo "$T0" > "$OUT/t0"
cd "$(dirname "$0")/../../scripts" 2>/dev/null || cd "${YEORO_DIR:?YEORO_DIR=저장소 경로}/scripts"
DEMO_PAUSE=${DEMO_PAUSE:-4} DEMO_SHOTS=0 ./scenario_demo.sh 2>&1 | while IFS= read -r line; do
  now=$(python3 -c 'import time;print(time.time())'); printf '%s\t%s\n' "$now" "$line" >> "$OUT/log.tsv"; echo "$line"; done
RC=${PIPESTATUS[0]}
sleep 3
touch "$OUT/stop"; adb shell pkill -INT screenrecord; wait $REC 2>/dev/null
sleep 2
for f in $(adb shell ls /sdcard/ | grep rec_ | tr -d '\r'); do adb pull -q /sdcard/$f "$OUT/$f"; done
ls -la "$OUT"; echo "scenario exit=$RC"
