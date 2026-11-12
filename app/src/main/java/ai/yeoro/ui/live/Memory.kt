package ai.yeoro.ui.live

import ai.yeoro.data.TripEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/*
 * 1년 전 이번 주 (시안 장면 1, 순수). 지금 화면 일상(P단)에 하루 한 번 회상 카드를 띄운다.
 * 금요일 저녁 귀가 규칙(시안의 "금요일 저녁, 집 도착 P단")은 하지 않는다(로드맵).
 */

/** 오늘 − 1년 기준 ± 며칠 */
const val MEMORY_WINDOW_DAYS = 3L
/** 처음 보인 날을 적는 prefs 열쇠 (값은 "yyyy-MM-dd") */
const val MEMORY_SHOWN_KEY = "memory_shown_date"

/**
 * 회상할 여행: 확인된 여행(ALBUM_READY) 중 출발 날짜가 (오늘 − 1년) ± 3일 안인 것. 날짜 차가 작은 것, 같으면 늦게 끝난 것.
 * 윤년 2월 29일의 1년 전은 2월 28일(LocalDate.minusYears).
 */
fun memoryTrip(trips: List<TripEntity>, nowMs: Long, zone: ZoneId): TripEntity? {
    val target = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().minusYears(1)
    fun gap(t: TripEntity) = abs(ChronoUnit.DAYS.between(target, Instant.ofEpochMilli(t.startMs).atZone(zone).toLocalDate()))
    return trips.asSequence()
        .filter { it.state == "ALBUM_READY" && gap(it) <= MEMORY_WINDOW_DAYS }
        .minWithOrNull(compareBy<TripEntity> { gap(it) }.thenByDescending { it.endMs ?: it.startMs })
}

/** 오늘 아직 띄우지 않았으면 true. 날짜는 "yyyy-MM-dd" */
fun memoryDue(lastShownDate: String?, today: String): Boolean = lastShownDate != today

/**
 * 후보 카드를 내보낼지: 오늘 아직 안 띄웠거나(memoryDue), 지금 화면에 떠 있는 바로 그 카드일 때만.
 * 한 번 떴다가 안 보이게 되면(onScreen을 비움) 그날은 다시 나오지 않는다.
 */
fun memoryVisible(lastShownDate: String?, today: String, tripId: Long, onScreen: Pair<String, Long>?): Boolean =
    memoryDue(lastShownDate, today) || onScreen == (today to tripId)

/** "yyyy-MM-dd" (memoryDue·prefs용) */
fun memoryDay(nowMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()

/** 제목 "1년 전 이번 주, 우리는 {목적지}에 있었어요", 부제 "2025년 11월 1일 · 사진 1장"(사진이 없으면 날짜만) */
fun memoryText(trip: TripEntity, photoCount: Int, zone: ZoneId): Promo.Memory {
    val d: LocalDate = Instant.ofEpochMilli(trip.startMs).atZone(zone).toLocalDate()
    val dest = trip.destination?.trim()?.takeIf { it.isNotEmpty() }
    val title = dest?.let { "1년 전 이번 주, 우리는 ${it}에 있었어요" } ?: "1년 전 이번 주의 여행"
    val date = "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일"
    return Promo.Memory(trip.id, title, if (photoCount > 0) "$date · 사진 ${photoCount}장" else date)
}
