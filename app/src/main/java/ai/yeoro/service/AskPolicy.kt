package ai.yeoro.service

import ai.yeoro.PendingAsk

const val ASK_TTL_MS = 10 * 60_000L

/** 질문은 지금 화면이 보일 때만 앱 카드로 전달된다. 그 밖(다른 탭·다른 앱)에서는 알림을 한 번 띄운다. */
fun shouldNotifyAsk(liveVisible: Boolean, ask: PendingAsk?, posted: PendingAsk?): Boolean = !liveVisible && ask != null && ask !== posted

/**
 * 출발 질문을 소리로 물을지: 지금 화면이 보이고(마이크가 열려 있음을 카드에서 볼 수 있을 때만), 아직 소리로 묻지 않은 출발 질문일 때.
 * 알림으로만 전달된 질문은 소리로 묻지 않는다. 같은 질문은 두 번 묻지 않는다(voiced = 이미 소리로 물은 질문).
 */
fun shouldVoiceAsk(liveVisible: Boolean, ask: PendingAsk?, voiced: PendingAsk?): Boolean =
    liveVisible && ask != null && ask.kind == "departure" && ask !== voiced

/** 질문이 올라온 뒤 남은 유효 시간 */
fun askRemainingMs(askedMs: Long, nowMs: Long): Long = (ASK_TTL_MS - (nowMs - askedMs)).coerceAtLeast(0L)

/** "강릉 · 238km" 형태의 목적지·거리 문구 (알림·앱 카드 공용). 둘 다 없으면 빈 문자열 */
fun askWhere(p: PendingAsk): String = listOfNotNull(p.destination, p.distanceKm?.let { "${Math.round(it)}km" }).joinToString(" · ")
