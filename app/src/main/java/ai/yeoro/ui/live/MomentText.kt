package ai.yeoro.ui.live

import ai.yeoro.engine.Seat

enum class MomentIcon { RAIN, SUN, WINDOW, STOP, USER }

/** 지금 화면 한 줄: 문구, 출처(어떤 신호로 알았는지), 아이콘 */
data class MomentLine(val text: String, val source: String, val icon: MomentIcon)

/** DB의 순간(type 이름, detail)을 한국어 한 줄로. 모르는 종류는 보이지 않는다(null). */
fun momentLine(type: String, detail: String): MomentLine? = when (type) {
    "RAIN_START" -> MomentLine("비가 오기 시작했어요", "와이퍼", MomentIcon.RAIN)
    "RAIN_STOP" -> MomentLine("비가 그쳤어요", "와이퍼", MomentIcon.SUN)
    "WINDOWS_OPEN" -> MomentLine("창문 ${detail}개가 열렸어요", "창문", MomentIcon.WINDOW)
    "STOP" -> MomentLine("${detail}분 쉬어 갔어요", "도어·P단", MomentIcon.STOP)
    // 출발 때 좌석("→4|…")은 함께 출발한 인원, 정차 뒤 늘어난 좌석("3→4")은 동승
    "COMPANION_JOINED" -> ai.yeoro.data.parseJoined(detail).let { j ->
        if (j.from == null && j.to != null) MomentLine("${j.to}명이 함께 출발했어요", "좌석", MomentIcon.USER)
        else MomentLine("함께하는 사람이 늘었어요", "좌석", MomentIcon.USER)
    }
    else -> null
}

/** 좌석 이름 (가족 이름이 없을 때 쓴다) */
fun seatName(s: Seat): String = when (s) {
    Seat.DRIVER -> "운전석"; Seat.PASSENGER -> "조수석"
    Seat.REAR_LEFT -> "뒷좌석 왼쪽"; Seat.REAR_CENTER -> "뒷좌석 가운데"; Seat.REAR_RIGHT -> "뒷좌석 오른쪽"
}

/** 설정의 가족 이름, 비어 있으면 좌석 이름 */
fun personName(s: Seat, names: Map<Seat, String>): String = names[s]?.trim()?.takeIf { it.isNotEmpty() } ?: seatName(s)
