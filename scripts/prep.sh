#!/bin/bash
# 시나리오 공통 준비: 앱 기동, 로그 비우기, 엔진·정책 초기화, 디버그 재정의 해제
# 기록 동의는 기본 true (YEORO_CONSENT=false 로 끌 수 있다: scenario_consent_off.sh)
# YEORO_HOME=37.5563,126.9220 처럼 주면 내비 위치 대신 그 좌표를 집으로 쓴다 (에뮬레이터 내비가 오프라인일 때)
cd "$(dirname "$0")"; ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
"$ADB" shell am start --user 10 -n ai.yeoro/.ui.MainActivity >/dev/null 2>&1
sleep 3
./vc.sh gear Park >/dev/null
./dbg.sh --es consent "${YEORO_CONSENT:-true}" --ez reset_policy true --es route clear --es weekend "$1" --es home "${YEORO_HOME:-here}"
sleep 3
"$ADB" logcat -c
