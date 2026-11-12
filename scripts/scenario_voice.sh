#!/bin/bash
# 음성 (Pleos TTS·STT 온디바이스, 디버그 빌드 + Pleos Connect 에뮬레이터)
#   ① 도착 인사: 지난 방문(seed_prev) + 강릉 목적지 + 도착 → caas가 "강릉 경포해변, 두 번째 방문이에요"를 말하고 UpdateTtsDone
#   ② 듣고 있어요: 말로 답하기 켜고 실제 마이크(에뮬레이터는 무음) → 카드에 "듣고 있어요" 스크린샷 → 6초 뒤 멈추고 답 없음
#   ③ voice_pcm yes → accepted=true (기록 시작 장면)  ④ voice_pcm no → accepted=false
#   ⑤ voice_pcm maybe → 답 없음, 카드 그대로, 터치로 답함  ⑥ 질문 중 기어 D → 음성 멈춤
#   ⑦ 설정 둘 다 끔 → 도착·질문에 caas 호출 없음
#   ⑧ 지금 화면이 가려진 채 질문 → 소리로 묻지 않음, 지금 화면이 보이면 그때 한 번, 다시 가려지면 멈춤
# 에뮬레이터 마이크로는 소리가 들어오지 않아 답은 PCM 주입(PcmVoiceIo, scripts/make_voice_pcm.sh)으로 넣는다.
# 환경 변수: YEORO_HOME (prep.sh, 기본 here) / VOICE_SHOT_DIR (기본 docs/screens/2b)
set -u
cd "$(dirname "$0")"; ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
OUT="${VOICE_SHOT_DIR:-$(cd .. && pwd)/docs/screens/2b}"; mkdir -p "$OUT"
rc=0
scene() { printf '\n\033[1m▶ %s\033[0m\n' "$1"; }
fail()  { printf '\033[31mFAIL %s\033[0m\n' "$1"; rc=1; }
ok()    { echo "OK  $1"; }
dbg()   { ./dbg.sh "$@" || fail "dbg $*"; }
expect() { ./expect.sh "$1" "${2:-20}" >/dev/null && ok "$1" || fail "로그 '$1' 없음"; }
# 전체 logcat(caas 포함)에 패턴이 나올 때까지
expect_all() { for _ in $(seq 1 "${2:-20}"); do "$ADB" logcat -d | grep -q "$1" && { ok "$1"; return 0; }; sleep 1; done; fail "logcat '$1' 없음"; return 1; }
tap_text() {
  local b=""
  for _ in 1 2 3 4 5 6; do
    "$ADB" shell uiautomator dump /sdcard/yeoro_ui.xml >/dev/null 2>&1
    b=$("$ADB" shell cat /sdcard/yeoro_ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
    [ -n "$b" ] && break; sleep 1
  done
  [ -n "$b" ] || { fail "화면에서 '$1'을 찾지 못함"; return 1; }
  set -- $(echo "$b" | grep -oE '[0-9]+')
  "$ADB" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
on_screen() { "$ADB" shell uiautomator dump /sdcard/yeoro_ui.xml >/dev/null 2>&1; "$ADB" shell cat /sdcard/yeoro_ui.xml | grep -qF "text=\"$1\""; }
# 상황 A의 출발 신호 (주말, 가족 4명, 트렁크 2회, 목적지 강릉) → 출발 질문
ask() {
  for s in driver passenger rear_left rear_right; do ./vc.sh seat $s on >/dev/null; done
  ./vc.sh seat rear_center off >/dev/null
  for _ in 1 2; do ./vc.sh trunk open >/dev/null; sleep 1; ./vc.sh trunk close >/dev/null; sleep 1; done
  dbg --es route inject
  expect "AskBeforeDeparture" 30
}
prep() { ./prep.sh true || { fail prep; exit 1; }; tap_text "지금"; sleep 1; "$ADB" logcat -c; }

scene "0 준비: 말로 답하기 켜기 (마이크 권한은 pm grant, 설정 화면은 켤 때 묻는다)"
"$ADB" shell pm grant --user 10 ai.yeoro android.permission.RECORD_AUDIO
./vc.sh gear Park >/dev/null
prep
dbg --es voice_guide true --es voice_answer true --es voice_pcm off

scene "① 도착 인사 (P단, 두 번째 방문)"
dbg --ez seed_prev true; expect "debug seed_prev id=" 10
dbg --es route inject; sleep 2
dbg --ez arrive true
expect "voice arrival '강릉 경포해변, 두 번째 방문이에요'" 10
expect_all "speak(ai.yeoro) received final. texts=강릉 경포해변, 두 번째 방문이에요" 10
expect_all "UpdateTtsDone" 15
expect "tts done" 5

scene "② 듣고 있어요 (실제 마이크, 에뮬레이터는 무음 → 6초 뒤 답 없음)"
prep; dbg --es voice_pcm off
ask
expect "voice ask '가족 여행 가시나요?'" 5
expect "stt listening" 15
sleep 1; "$ADB" exec-out screencap -p > "$OUT/voice-listening.png" && echo "     📷 voice-listening.png"
on_screen "듣고 있어요 · ‘응’ 또는 ‘아니’" && ok "카드에 듣고 있어요" || fail "카드에 듣고 있어요가 없음"
sleep 8
"$ADB" logcat -d -s YeoroEngine | grep -q "via=voice" && fail "무음인데 음성 답이 들어감" || ok "무음: 답 없음"
on_screen "듣고 있어요 · ‘응’ 또는 ‘아니’" && fail "듣기가 끝났는데 듣고 있어요가 남음" || ok "6초 뒤 듣고 있어요 사라짐"
on_screen "여행 기록 시작" && ok "카드는 그대로" || fail "카드가 사라짐"

scene "③ voice_pcm yes → 수락"
prep; dbg --es voice_pcm yes
ask
expect "answer kind=departure accepted=true taken=true via=voice" 20
sleep 2; "$ADB" exec-out screencap -p > "$OUT/voice-start.png" && echo "     📷 voice-start.png"
on_screen "이제 출발하세요" && ok "기록 시작 장면" || fail "기록 시작 장면이 아님"

scene "④ voice_pcm no → 거절"
prep; dbg --es voice_pcm no
ask
expect "answer kind=departure accepted=false taken=true via=voice" 20

scene "⑤ voice_pcm maybe → 답 없음, 카드 그대로, 터치로 답"
prep; dbg --es voice_pcm maybe
ask
expect "stt final" 20
sleep 1
"$ADB" logcat -d -s YeoroEngine | grep -q "via=voice" && fail "모르는 답인데 음성 답이 들어감" || ok "모르는 답: 답 없음"
tap_text "여행 기록 시작" && sleep 2
on_screen "이제 출발하세요" && ok "터치로 답함" || fail "터치 답이 안 됨"

scene "⑥ 질문 중 기어 D → 음성 멈춤"
prep; dbg --es voice_pcm off
ask
expect "stt listening" 15
./vc.sh gear Drive >/dev/null
expect "voice cancel (gear D)" 10
expect "voice stop" 5
./vc.sh gear Park >/dev/null; sleep 2

scene "⑧ 지금 화면이 가려진 채 질문 → 소리로 묻지 않음, 지금 화면으로 오면 그때 한 번 묻고 가려지면 멈춤"
prep; dbg --es voice_pcm off
tap_text "설정"; sleep 1
ask
sleep 3
"$ADB" logcat -d -s YeoroEngine | grep -q "voice ask" && fail "가려진 화면인데 소리로 물음" || ok "가려짐: 소리로 묻지 않음 (알림만)"
tap_text "지금"
expect "voice ask '가족 여행 가시나요?'" 10
expect "stt listening" 15
tap_text "설정"
expect "voice cancel (hidden)" 10
tap_text "지금"; sleep 3
[ "$("$ADB" logcat -d -s YeoroEngine | grep -c "voice ask '")" = 1 ] && ok "같은 질문은 한 번만 소리로" || fail "같은 질문을 다시 소리로 물음"
on_screen "여행 기록 시작" && ok "카드는 그대로" || fail "카드가 사라짐"

scene "⑦ 설정 둘 다 끔 → caas 호출 없음"
prep; dbg --es voice_guide false --es voice_answer false --es voice_pcm yes
ask
dbg --ez arrive true; expect "debug arrive" 10
sleep 6
if "$ADB" logcat -d | grep -E "\[GAAS\]" | grep -qE "ai\.yeoro([^.a-z]|$)"; then fail "음성이 꺼졌는데 caas에 ai.yeoro 호출"; else ok "음성 꺼짐: caas에 ai.yeoro 없음"; fi
"$ADB" logcat -d -s YeoroEngine | grep -q "voice ask\|voice arrival" && fail "음성이 꺼졌는데 voice 로그" || ok "음성 꺼짐: voice 로그 없음"

scene "정리: 기본값으로 (도착 인사 켜짐, 말로 답하기 꺼짐, 실제 마이크)"
prep; dbg --es voice_guide true --es voice_answer false --es voice_pcm off --ez clear_trips true
[ $rc = 0 ] && echo "OK  scenario_voice" || echo "FAIL scenario_voice"
exit $rc
