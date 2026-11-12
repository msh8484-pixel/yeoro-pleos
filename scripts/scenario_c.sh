#!/bin/bash
# 상황 C: 혼자, 평일, 트렁크 없음, 짧은 목적지(회사) 주행 → AskBeforeDeparture가 나오면 안 된다
cd "$(dirname "$0")"; ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
./prep.sh false
./vc.sh seat driver on >/dev/null; for s in passenger rear_left rear_right; do ./vc.sh seat $s off >/dev/null; done
# 짧은 목적지(회사, ~15km)를 주입하고 Park에서 8초 유지: 출발 전 제안이 나올 기회를 준다
./dbg.sh --es route short; sleep 8
./vc.sh gear Drive >/dev/null; ./vc.sh speed 50 >/dev/null; sleep 30
./expect.sh "score=" 15 || { echo "FAIL: 엔진 로그가 없음 (서비스 미기동?)"; exit 1; }
if ./expect.sh "AskBeforeDeparture" 3 >/dev/null; then echo "FAIL: 출퇴근에 제안이 뜸"; exit 1; else echo "OK  조용함 (AskBeforeDeparture 없음)"; fi
