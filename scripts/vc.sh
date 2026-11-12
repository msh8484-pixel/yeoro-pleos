#!/bin/bash
# Pleos Connect Virtual Console을 터미널에서 조작한다 (시연 영상 자동화용).
# 사용법:
#   scripts/vc.sh gear Drive|Park|Reverse|Neutral
#   scripts/vc.sh speed 60            # km/h
#   scripts/vc.sh door fl|fr|rl|rr open|close
#   scripts/vc.sh seat driver|passenger|rear_left|rear_right on|off
#   scripts/vc.sh seat rear_center on|off|auto   # 뒷좌석 가운데: VC에 없는 좌석이라 앱(디버그 빌드)의 좌석 재정의로 보낸다
#   scripts/vc.sh trunk open|close
#   scripts/vc.sh wiper Off|Auto|Low|High
#   scripts/vc.sh window fl|fr|rl|rr 0-100   # 열림 %
#   scripts/vc.sh windows 0-100             # 네 창문을 한 번에 (한 틱 안에 함께 바뀌게)
#   scripts/vc.sh state               # 현재 상태 JSON
#   scripts/vc.sh catalog             # 조작 가능한 전체 항목
# Virtual Console은 브라우저 세션 heartbeat가 있어야 차량 버스(DDS)에 연결된다.
# 브라우저로 http://127.0.0.1:8088 을 열어두거나, 이 스크립트가 잠깐 heartbeat를 보낸다.
set -e
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
B=http://127.0.0.1:8088
# 에뮬레이터 quirk 보정: Virtual Console의 occupied=true가 SDK에는 false로 도달한다(HAL 이벤트가 이미 반전).
# 앱은 SDK 의미(true=착석)를 그대로 쓰고, 여기서만 쓰는 값을 뒤집어 `seat fl on`이 앱에서 착석으로 보이게 한다.
# 실차에서는 EMULATOR_SEAT_INVERTED=0 으로 끈다(실차 동작은 미확인).
EMULATOR_SEAT_INVERTED="${EMULATOR_SEAT_INVERTED:-1}"

"$ADB" forward tcp:8088 tcp:8088 >/dev/null

heartbeat() {
  for _ in 1 2 3; do curl -s -X POST "$B/browser/session?client=vc-cli" -o /dev/null; sleep 1; done
}
put() { curl -s -X PUT "$B$1" -o /dev/null -w "%{http_code}\n"; }

case "$1" in
  gear)    heartbeat; put "/vehicle/gear?gear=$2" ;;
  speed)   heartbeat; put "/vehicle/controls?speed=$2&accelerator=0&brake=0" ;;
  door)    heartbeat; v=false; [ "$3" = open ] && v=true; put "/vehicle/body?door_$2=$v" ;;
  # 뒷좌석 가운데는 VC 좌석 영역에 없다(driver·passenger·rear_left·rear_right만). 에뮬레이터에서는 늘 앉은 것으로 SDK에 오므로
  # 디버그 빌드의 좌석 재정의(dbg --es seat rear_center:off)로 앱이 보는 값을 정한다. 이 값은 앱 의미(on=착석) 그대로라
  # EMULATOR_SEAT_INVERTED 반전을 하지 않는다. auto = 재정의 해제(차량 값). 실차·릴리스에는 영향이 없다.
  seat)    if [ "$2" = rear_center ]; then
             case "$3" in on|off|auto) ;; *) echo "seat rear_center on|off|auto" >&2; exit 1 ;; esac
             "$(dirname "$0")/dbg.sh" --es seat "rear_center:$3" >/dev/null && echo 200; exit $?
           fi
           heartbeat; v=false; [ "$3" = on ] && v=true
           if [ "$EMULATOR_SEAT_INVERTED" = 1 ]; then [ "$v" = true ] && v=false || v=true; fi
           put "/vehicle/seat?kind=occupied&area=$2&value=$v" ;;
  trunk)   heartbeat; v=false; [ "$2" = open ] && v=true; put "/vehicle/body?trunk=$v" ;;
  wiper)   heartbeat; put "/vehicle/body?front_wiper=$2" ;;
  window)  heartbeat; put "/vehicle/body?window_$2=$3" ;;
  windows) heartbeat; put "/vehicle/body?window_fl=$2&window_fr=$2&window_rl=$2&window_rr=$2" ;;
  state)   curl -s "$B/vehicle/state" ;;
  catalog) curl -s "$B/vehicle/catalog" ;;
  *) sed -n '2,15p' "$0" ;;
esac
