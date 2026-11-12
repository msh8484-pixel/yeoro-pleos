#!/bin/bash
# 상황 B: 3명, 트렁크 1회, 목적지 없이 출발 → 후보(CANDIDATE) 진입까지만 확인 (1시간 정차는 JVM 테스트로 검증)
cd "$(dirname "$0")"; ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
./prep.sh true
for s in driver passenger rear_left; do ./vc.sh seat $s on >/dev/null; done; ./vc.sh seat rear_right off >/dev/null
./vc.sh trunk open >/dev/null; sleep 2; ./vc.sh trunk close >/dev/null; sleep 2
./vc.sh gear Drive >/dev/null; ./vc.sh speed 70 >/dev/null; sleep 5
"$ADB" logcat -d -s YeoroEngine | tail -5
# Drive 이후에 찍힌 로그에서 상태+기어가 함께 보여야 한다 (prep에서 logcat을 비웠고 좌석·트렁크는 Park에서 일어난다)
if ./expect.sh "score=.*state=CANDIDATE gear=D\b" 15 >/dev/null; then echo "OK  CANDIDATE 진입 (Drive)"; else echo "FAIL CANDIDATE+Drive 로그 없음"; exit 1; fi
