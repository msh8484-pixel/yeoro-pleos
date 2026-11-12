#!/bin/bash
# 사용: expect.sh "<패턴>" <초>  — 로그에 패턴이 나오면 0, 시간 안에 안 나오면 1
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
for _ in $(seq 1 "$2"); do "$ADB" logcat -d -s YeoroEngine | grep -q "$1" && { echo "OK  $1"; exit 0; }; sleep 1; done
echo "FAIL $1"; exit 1
