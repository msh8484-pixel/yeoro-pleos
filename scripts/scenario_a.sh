#!/bin/bash
# 상황 A: 주말, 가족 4명 착석, 트렁크 열고 닫기 2회, P단에서 목적지 강릉 → 30초 안에 AskBeforeDeparture
# 목적지: ROUTE=inject(기본, 엔진 쪽에 직접 주입) | ROUTE=real(NaviHelper.requestRoute, 에뮬레이터 내비는 오프라인이라 목적지가 나오지 않음)
set -e; cd "$(dirname "$0")"; ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
./prep.sh true
for s in driver passenger rear_left rear_right; do ./vc.sh seat $s on >/dev/null; done
./vc.sh seat rear_center off >/dev/null   # 에뮬레이터는 뒷좌석 가운데를 늘 앉음으로 보낸다: 가족 4명
for _ in 1 2; do ./vc.sh trunk open >/dev/null; sleep 2; ./vc.sh trunk close >/dev/null; sleep 2; done
if [ "${ROUTE:-inject}" = real ]; then ./dbg.sh --es route 강릉; else ./dbg.sh --es route inject; fi
./expect.sh "AskBeforeDeparture" 30
