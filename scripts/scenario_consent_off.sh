#!/bin/bash
# 동의 꺼짐: 상황 A와 같은 신호(주말, 4명 착석, 트렁크 2회, P단 목적지 강릉)를 주어도 30초 동안 AskBeforeDeparture가 없어야 한다
# 그리고 서버 동기화를 요청해도(sync_now) 네트워크 호출이 없어야 한다
# 끝나면 동의를 다시 켠다 (다른 시나리오는 prep.sh가 어차피 true를 보낸다)
cd "$(dirname "$0")"; ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
YEORO_CONSENT=false ./prep.sh true || exit 1
for s in driver passenger rear_left rear_right; do ./vc.sh seat $s on >/dev/null; done
./vc.sh seat rear_center off >/dev/null   # 에뮬레이터는 뒷좌석 가운데를 늘 앉음으로 보낸다: 가족 4명
for _ in 1 2; do ./vc.sh trunk open >/dev/null; sleep 2; ./vc.sh trunk close >/dev/null; sleep 2; done
./dbg.sh --es route inject || exit 1
if ./expect.sh "AskBeforeDeparture" 30 >/dev/null; then rc=1; echo "FAIL 동의 꺼짐인데 AskBeforeDeparture가 나왔음"
# 동의한 엔진은 틱마다 점수를 낸다(10초 간격 로그). 꺼진 엔진(OFF)은 아무 액션도 내지 않는다
elif "$ADB" logcat -d -s YeoroEngine | grep -q "score="; then rc=1; echo "FAIL 동의 꺼짐인데 엔진이 점수를 냈음"
elif "$ADB" shell dumpsys notification --noredact 2>/dev/null | grep -E "NotificationRecord.*pkg=ai\.yeoro .*id=11 " -q; then rc=1; echo "FAIL 동의 꺼짐인데 질문 알림이 있음"
else rc=0; echo "OK  동의 꺼짐: 30초 동안 AskBeforeDeparture 없음"; fi
# 서버 호출도 없어야 한다: 동기화를 요청해도 HttpCloudApi·CloudSync 로그("cloud …")가 하나도 없다
./dbg.sh --ez sync_now true || rc=1
./expect.sh "debug sync_now" 10 >/dev/null || { rc=1; echo "FAIL sync_now 브로드캐스트를 받지 못함"; }
sleep 3
if "$ADB" logcat -d -s YeoroEngine | grep -q "cloud "; then rc=1; echo "FAIL 동의 꺼짐인데 서버 호출(cloud 로그)이 있음"
else echo "OK  동의 꺼짐: sync_now에도 서버 호출 없음"; fi
./dbg.sh --es consent true
exit $rc
