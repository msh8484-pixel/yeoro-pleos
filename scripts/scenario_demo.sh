#!/bin/bash
# 여로 통합 시연 (발표·영상 촬영용, 디버그 빌드 + Pleos Connect 에뮬레이터)
#   동의 켜기 → 상황 A 출발 제안(좌석·트렁크·강릉 목적지) → [여행 기록 시작] → 주행(정적 기록 화면) → 휴게소 정차
#   → 비(와이퍼 켜고 끄기) → 창문 → 경포해변 P단 도착 인증 카드 → 귀가로 여행 닫기 → 앨범 공개(이번 여행) → 표지 → 장 → 책장
# 2b: 시작 전 1년 전 이번 주 회상 카드(지난 방문 seed_prev) → 도착 카드 "두 번째 방문" + 그때 줄 → 가족 사진 4장 업로드(demo_upload.sh)
#   → 지금 화면 "가족이 사진 4장을 보냈어요" → 앨범(실제 공공데이터 칩·사진 표지·콜라주) → 그때 vs 지금 → 다시 가기 QR → 정기 방문 [다시 가기]
# 시간 압축: 에뮬레이터 위치가 고정이라 샘플 경로(서울 마포 → 강릉 경포해변, 30개 지점)를 위치 신호로 재생하고
#   엔진 시계를 앞당긴다(dbg demo_route/skip_min/close_trip). DB에 직접 쓰지 않고 실제 기록 경로를 그대로 탄다.
# 장면마다 docs/screens/2a/demo-NN-<장면>.png 를 남기고 기대 로그를 확인한다(없으면 그 자리에서 실패).
# 시계는 다가오는 토요일 08:52에서 시작해 경로 시각표대로 흐른다(13:20 경포해변 도착). 날짜는 다가오는 토요일로 옮겨 주말 신호가 달력에서 자연스럽게 나온다. 실제 시각과 다르다는 점은 README에 적어 둔다.
# 환경 변수: DEMO_PAUSE=초 (장면 사이 멈춤, 기본 3, 발표 때는 8~10) / DEMO_SHOTS=0 (스크린샷 끄기) / DEMO_PANEL=1 (판단 엔진 패널 보이기, 기본 숨김)
#           DEMO_UNTIL=arrival (도착 카드에서 멈춤: 기어 이탈·재시작 확인용)
#           DEMO_OUTSIDE=real|demo (기본 real: 그날의 바깥의 날씨를 실제 지금으로 묻는다. 시연 날짜는 기상청 창 밖일 수 있다. 일몰·축제·특일은 시연 날짜)
#           DEMO_SHOT_DIR=경로 (스크린샷 폴더, 기본 docs/screens/2b)
#           DEMO_PREV=1|0 (기본 1: 지난 방문을 넣고 시작: 1년 전 이번 주 경포해변 → 회상 카드, 도착 카드 "두 번째 방문" + 그때 줄, 앨범에 그때 vs 지금 쪽.
#                     0이면 2a 흐름: 회상·그때 vs 지금·정기 방문 장면 없음)
#           DEMO_OFFLINE=1 (오프라인 회귀: 에뮬레이터의 인터넷을 막고(사설 DNS) 클라우드 장면(업로드·sync)을 건너뛴다. 앨범은 예시 칩. 끝나면 되돌린다)
set -u
cd "$(dirname "$0")"; ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PAUSE="${DEMO_PAUSE:-3}"; SHOTS="${DEMO_SHOTS:-1}"
OUT="${DEMO_SHOT_DIR:-$(cd .. && pwd)/docs/screens/2b}"; mkdir -p "$OUT"
HOME_LL=37.5563,126.9220   # 집: 서울 마포 (샘플 경로의 출발점)
PREV="${DEMO_PREV:-1}"; OFFLINE="${DEMO_OFFLINE:-0}"

scene() { printf '\n\033[1m▶ %s\033[0m\n' "$1"; }
fail()  { printf '\033[31mFAIL %s\033[0m\n' "$1"; exit 1; }
pause() { sleep "${1:-$PAUSE}"; }
shot()  { [ "$SHOTS" = 1 ] || return 0; "$ADB" exec-out screencap -p > "$OUT/demo-$1.png" && echo "     📷 demo-$1.png"; }
vc()    { ./vc.sh "$@" >/dev/null || fail "vc $*"; }
dbg()   { ./dbg.sh "$@" || fail "dbg $*"; }
# 로그 확인: logcat을 비우지 않고 이 실행에서 처음 본 줄 이후만 본다 (prep.sh가 시작 때 한 번 비운다)
expect() { ./expect.sh "$1" "${2:-20}" || fail "로그 '$1' 없음"; }
# 화면의 글자(uiautomator 덤프의 text 그대로, 줄바꿈은 &#10;)를 찾아 그 가운데를 누른다. 못 찾으면 실패
tap_text() {
  local b=""
  for _ in 1 2 3 4 5 6; do
    "$ADB" shell uiautomator dump /sdcard/yeoro_ui.xml >/dev/null 2>&1
    b=$("$ADB" shell cat /sdcard/yeoro_ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
    [ -n "$b" ] && break; sleep 1
  done
  [ -n "$b" ] || fail "화면에서 '$1'을 찾지 못함"
  set -- $(echo "$b" | grep -oE '[0-9]+')
  "$ADB" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
# 화면에 글자(부분 일치)가 보일 때까지 기다린다. 못 찾으면 실패
see() {
  for _ in $(seq 1 "${2:-10}"); do
    "$ADB" shell uiautomator dump /sdcard/yeoro_ui.xml >/dev/null 2>&1
    "$ADB" shell cat /sdcard/yeoro_ui.xml | grep -qF "$1" && { echo "OK  화면: $1"; return 0; }; sleep 1
  done
  fail "화면에 '$1' 없음"
}
# 지금까지 기록된 시연 로그 수 (같은 로그가 여러 번 나오는 단계를 구분할 때)
count() { "$ADB" logcat -d -s YeoroEngine | grep -c "$1"; }
wait_count() { for _ in $(seq 1 "${3:-30}"); do [ "$(count "$1")" -ge "$2" ] && { echo "OK  $1 (#$2)"; return 0; }; sleep 1; done; fail "로그 '$1' #$2 없음"; }
play() { local n; n=$(count "demo_route done"); dbg --es demo_route "$1"; wait_count "demo_route done" $((n + 1)) 60; }

# 오프라인 회귀: 인터넷 이름 풀기를 막고(없는 사설 DNS) 끝나면(실패해도) 되돌린다.
# svc wifi/data disable은 쓰지 않는다: eth0가 내려가 Virtual Console(차량 버스)이 끊기고 에뮬레이터를 재부팅해야 살아난다.
net_off() { "$ADB" shell settings put global private_dns_mode hostname; "$ADB" shell settings put global private_dns_specifier offline.invalid; }
net_on()  { "$ADB" shell settings put global private_dns_mode opportunistic; "$ADB" shell settings delete global private_dns_specifier >/dev/null 2>&1; }
if [ "$OFFLINE" = 1 ]; then net_off; trap net_on EXIT; echo "     DEMO_OFFLINE=1: 인터넷 막음(사설 DNS offline.invalid)"; sleep 3; fi

# ── 0. 준비: 앱 기동, 정책·시계 초기화, 집은 서울, 지난 시연 여행 지우기 ─────────────────────
scene "00 준비"
"$ADB" shell am start --user 10 -n ai.yeoro/.ui.MainActivity >/dev/null 2>&1
vc gear Park; vc speed 0
vc windows 0; vc wiper Off
for d in fl fr rl rr; do vc door $d close; done; vc trunk close
for s in driver passenger rear_left rear_right; do vc seat $s off; done
YEORO_HOME=$HOME_LL ./prep.sh clear || fail prep   # 동의 켜기·reset_policy(시계·위치·좌석 재정의도 지움)·주말 재정의 해제(달력 그대로)·집, 로그 비움
# 뒷좌석 가운데: 에뮬레이터가 늘 "앉음"으로 보내 시연 가족이 5명이 되므로 비운다 (prep의 reset_policy 뒤에 해야 남는다)
vc seat rear_center off
# 디버그 "판단 엔진" 패널은 시연에서 숨긴다 (DEMO_PANEL=1이면 보인다)
dbg --es debug_panel "$([ "${DEMO_PANEL:-0}" = 1 ] && echo true || echo false)"
dbg --ez clear_trips true --es demo_loc $HOME_LL --es demo_clock 08:52   # 시연 시계: 토요일 08:52 출발 (샘플 경로의 시각표와 같게)
dbg --es outside_time "${DEMO_OUTSIDE:-real}"   # 그날의 바깥: 날씨를 묻는 시각 (reset_policy 뒤에 보내야 남는다)
expect "debug clear_trips" 10
if [ "$PREV" = 1 ]; then dbg --ez memory_reset true --ez seed_prev true; expect "debug seed_prev id=" 10; fi   # 시연 시계(토요일) 뒤에 넣어야 같은 요일. 회상 카드 "오늘 띄움"도 지운다
expect "consent=true\|score=" 15
dbg --es family "아빠,엄마,민지,,민호"   # 설정 화면의 가족 이름 (새로 설치해도 같은 이름)
"$ADB" shell am start --user 10 -n ai.yeoro/.ui.MainActivity >/dev/null 2>&1
tap_text "지금"   # 지난 실행이 다른 탭에서 끝났어도 지금 화면에서 시작한다 (질문은 지금 화면에서만 카드로 뜬다)
if [ "$PREV" = 1 ]; then
  scene "00b 1년 전 이번 주: 회상 카드 (일상·P단)"
  see "1년 전 이번 주"
  pause 2; shot 00-memory; pause
  tap_text "닫기"   # 출발 제안이 회상 카드 자리를 쓰도록 닫는다 (하루 한 번)
fi
pause 2; shot 01-daily; pause

# ── 1. 떠나기: 가족 4명 착석, 트렁크 두 번, 목적지 강릉 → 출발 전 제안 ────────────────────
scene "01 출발 신호: 좌석 4석 · 트렁크 2회 · 목적지 강릉 경포해변"
for s in driver passenger rear_left rear_right; do vc seat $s on; done
for _ in 1 2; do vc trunk open; sleep 1; vc trunk close; sleep 1; done
dbg --es route inject
expect "AskBeforeDeparture" 30
# 목적지 종류(TourAPI): 관광·숙박지면 제안 점수에 "목적지 관광·숙박지 +20"이 붙는다(오프라인·TourAPI 누락이면 없음)
"$ADB" shell uiautomator dump /sdcard/yeoro_ui.xml >/dev/null 2>&1
if "$ADB" shell cat /sdcard/yeoro_ui.xml | grep -q "관광·숙박지"; then echo "     점수: 목적지 관광·숙박지 +20 (TourAPI)"; else echo "     점수: 목적지 종류 신호 없음"; fi
"$ADB" logcat -d -s YeoroEngine | grep "AskBeforeDeparture" | tail -1 | grep -o 'points=-\{0,1\}[0-9]*' | awk -F= '{t+=$2} END{if (NR) print "     제안 점수 " t}'
pause 2; shot 02-ask; pause

scene "02 [여행 기록 시작]"
tap_text "여행 기록 시작"
pause 4; shot 03-start; pause

# ── 2. 달리기: D단, 정적 기록 화면 ─────────────────────────────────────────────────────
scene "03 출발 (D단) — 주행 중 화면은 정적"
vc gear Drive; vc speed 90
expect "action=StartTrip" 15
play 1-6
pause 1; shot 04-driving; pause

scene "04 휴게소: P단, 문 열고 38분 쉬기"
play 7-11
vc speed 0; vc gear Park; vc door fl open; sleep 2; vc door fl close
play 12-12          # 같은 자리에서 38분
vc gear Drive; vc speed 90
expect "type=STOP" 15
pause 1; shot 05-rest; pause

scene "05 비: 와이퍼 켜기 → 3분 넘게 → 비 시작"
play 13-16
vc wiper Low
play 17-21
expect "type=RAIN_START" 15
pause 1; shot 06-rain; pause

scene "06 비 그침: 와이퍼 끄고 10분 넘게"
play 22-24
vc wiper Off
play 25-26
expect "type=RAIN_STOP" 15

scene "07 창문 네 개 내리기"
play 27-27
vc windows 100
play 28-28
expect "type=WINDOWS_OPEN" 15
pause 1; shot 07-windows; pause

# ── 3. 머물기: 경포해변 P단 2분 → 도착 인증 카드 ─────────────────────────────────────────
scene "08 경포해변 도착: P단 → 도착 인증 카드"
play 29-29
vc speed 0; vc gear Park
vc windows 0
dbg --ei skip_min 3
expect "arrival trip=" 20
# 그날의 바깥: 도착 직후 동기화 결과(네트워크가 없거나 키가 없으면 outside=0이거나 줄이 없다. 시연은 그대로 이어 간다)
./expect.sh "cloud sync reason=arrival" 30 >/dev/null 2>&1 && "$ADB" logcat -d -s YeoroEngine | grep "cloud sync reason=arrival" | tail -1 | sed 's/^.*cloud sync/     cloud sync/'
pause 3; shot 08-arrival; pause
[ "${DEMO_UNTIL:-}" = arrival ] && { echo "DEMO_UNTIL=arrival: 도착 카드에서 멈춤 (여행은 열린 채)"; exit 0; }

# 근처 혜택 → 4인 예약 (컨셉: 제휴 사업 모델, 예시 데이터). 혜택 칸을 눌러 열고, 식당 예약을 요청한 뒤 닫는다
scene "08c 근처 혜택: 쿠폰 · 4인 예약"
tap_text "가족 관광쿠폰 1만 원"
see "근처에서 쓰기"
pause 2; shot 08c-benefits; pause
tap_text "4인 예약"
see "차가 확인한 탑승 인원"
tap_text "18:30"
pause 1
tap_text "예약 요청"
see "예약 요청됨"
pause 2; shot 08d-reserve; pause
tap_text "완료"

if [ "$OFFLINE" != 1 ]; then
  scene "08b 가족 사진 4장: 휴대폰으로 QR 업로드 → 차가 받아 제자리에"
  URL=$("$ADB" logcat -d -s YeoroEngine | grep -o 'upload_url=https://[^ ]*' | tail -1 | cut -d= -f2-)
  [ -n "$URL" ] || fail "로그에 upload_url 없음"
  expect "registered=1" 30   # 도착 동기화가 여행을 등록해야 업로드가 열린다
  ./demo_upload.sh "$URL" || fail "demo_upload"
  n=$(count "photos=4")
  dbg --ez sync_now true
  wait_count "photos=4" $((n + 1)) 60
  dbg --ei skip_min 1   # 도착 카드는 30초 뒤 닫힌다 (시연 시계)
  see "가족이 사진 4장을 보냈어요"
  pause 2; shot 08b-photos; pause
else
  echo "     DEMO_OFFLINE=1: 사진 업로드·동기화 건너뜀"
fi

# ── 4. 기억하기: 귀가로 여행 닫기 → 앨범 공개 ───────────────────────────────────────────
scene "09 귀가: 집 P단 120분 규칙으로 여행 닫기"
dbg --ez close_trip true
expect "action=TripClosed" 30
expect "close_trip done" 30
pause 1; shot 09-saved; [ "$SHOTS" = 1 ] && [ "$(basename "$OUT")" = 2a ] && cp "$OUT/demo-09-saved.png" "$OUT/live-album-ready.png"; pause

scene "10 앨범 공개 (이번 여행): 지금 화면의 [앨범 보기]"
tap_text "앨범 보기"
sleep 1.5; shot 10-reveal-mid
pause 6; shot 11-reveal; pause

scene "11 표지"
tap_text "앨범 펼치기"
pause 6; shot 12-cover; pause

scene "12 장 넘기기"
tap_text "펼쳐 보기"
pause 3; shot 13-chapter-depart; pause
tap_text "첫 번째 쉼"
pause 3; shot 14-chapter-rest; pause
tap_text "강릉 경포해변"
pause 3; shot 15-chapter-arrive; pause
if [ "$PREV" = 1 ]; then
  scene "12b 그때 vs 지금 (두 번째 방문)"
  tap_text "그때 vs 지금"
  pause 3; shot 15b-then-now; pause
fi
scene "12c 다시 가기: 지도·홈페이지 QR (휴대폰으로 넘기기)"
tap_text "다시 가기"
pause 3; shot 15c-again; pause

# ── 5. 다시 가기(책장) ─────────────────────────────────────────────────────────────
scene "13 우리 가족 여행 (책장)"
tap_text "우리 가족&#10;여행"
pause 3; shot 16-home; pause 1
if [ "$PREV" = 1 ]; then
  scene "14 정기 방문: 강릉 경포해변 2번 → [다시 가기]"
  tap_text "정기 방문"
  pause 2
  tap_text "다시 가기"
  pause 3; shot 17-home-again; pause
  tap_text "닫기"
fi

printf '\n\033[32mOK  시연 완료: %s출발 제안 → 기록 → 비·창문·쉼 → 도착 인증%s → 앨범 공개%s\033[0m\n' \
  "$([ "$PREV" = 1 ] && echo '회상 → ')" "$([ "$OFFLINE" != 1 ] && echo ' → 가족 사진 4장')" "$([ "$PREV" = 1 ] && echo ' → 그때 vs 지금 → 다시 가기')"
