#!/bin/bash
# 디버그 빌드 전용 브로드캐스트 (RecorderService가 동적 등록한 ai.yeoro.DEBUG 수신기)
# 사용: dbg.sh --es weekend true|false|clear | --es route 강릉|inject|short|clear | --ez reset_policy true | --es home here|clear|lat,lon | --ez arrive true | --ez seed_trip true | --es seed_trip true|round | --ez seed_prev true | --ez memory_reset true | --ez seed_home true | --ez clear_trips true | --es consent true|false | --es family 운전석,조수석,뒷왼,뒷가운데,뒷오른 | --ez sync_now true | --es nearby open|reserve|time:18:30|request|back|close (근처 혜택·예약 컨셉) | 시연: --es demo_clock HH:MM | --es demo_route a-b | --es demo_loc lat,lon|clear | --ei skip_min N | --ez close_trip true | --es outside_time real|demo (DemoDrive.kt)
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
out=$("$ADB" shell am broadcast --user 10 -a ai.yeoro.DEBUG -p ai.yeoro "$@" 2>&1)
echo "$out" | grep -q "Broadcast completed" || { echo "WARN: dbg 브로드캐스트 실패: $out" >&2; exit 1; }
