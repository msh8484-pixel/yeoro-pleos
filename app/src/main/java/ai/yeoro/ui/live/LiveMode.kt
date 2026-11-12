package ai.yeoro.ui.live

import ai.yeoro.engine.TripState

/** 지금 화면에 보일 장면. */
enum class LiveMode { OFF, ASK, ARRIVAL, RECORDING, START, ALBUM_READY, WAITING, DAILY }

private val Recording = setOf(TripState.TRIP, TripState.STAYING, TripState.CANDIDATE)

/**
 * 지금 화면 장면 고르기. 기록 동의가 켜져 있지 않으면(끔·[나중에]·아직 안 정함) 다른 모든 것보다 먼저 "기록이 꺼져 있어요".
 * 꺼진 엔진은 아무 점수도 내지 않아 마지막 점수(state)가 낡은 값으로 남기 때문에, 동의를 엔진 상태보다 먼저 본다.
 * 그다음 우선순위: 질문 카드 > 도착 인증 카드(P단) > 기록 중 > 기록 시작 장면 > 앨범 준비 > 신호 기다림 > 일상.
 */
fun liveMode(consent: Boolean?, state: TripState?, hasAsk: Boolean, hasArrival: Boolean, leftP: Boolean, started: Boolean): LiveMode = when {
    consent != true -> LiveMode.OFF
    hasAsk -> LiveMode.ASK
    hasArrival && !leftP -> LiveMode.ARRIVAL
    state in Recording -> LiveMode.RECORDING
    started -> LiveMode.START
    state == TripState.OFF -> LiveMode.OFF
    state == TripState.ALBUM_READY -> LiveMode.ALBUM_READY
    state == null -> LiveMode.WAITING
    else -> LiveMode.DAILY
}
