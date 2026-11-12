package ai.yeoro.service

import ai.yeoro.data.TripRepository
import ai.yeoro.engine.Gear

const val ARRIVAL_RADIUS_M = 300.0
const val ARRIVAL_PARK_MS = 2 * 60_000L
const val ARRIVAL_SHOW_MS = 30_000L

/**
 * 도착 판정. 열린 여행이 있고, 아직 이 여행에서 띄운 적이 없고, 기어 P일 때만(화면 카드는 P단에서만)
 * - 내비가 이 여행 중에 도착을 알렸거나 (naviArrivedTripId == tripId)
 * - 목적지 300m 안에서 P단으로 2분 이상 서 있으면 도착이다.
 */
fun arrivalDue(tripId: Long?, firedTripId: Long?, naviArrivedTripId: Long?, gear: Gear, parkedSinceMs: Long?, distanceM: Double?, nowMs: Long): Boolean {
    if (tripId == null || tripId == firedTripId || gear != Gear.P) return false
    if (naviArrivedTripId == tripId) return true
    return distanceM != null && distanceM <= ARRIVAL_RADIUS_M && parkedSinceMs != null && nowMs - parkedSinceMs >= ARRIVAL_PARK_MS
}

const val PREF_ARRIVAL_FIRED = "arrival_fired_trip"
const val PREF_NAVI_ARRIVED = "navi_arrived_trip"
const val NO_TRIP = -1L

/** 재시작 뒤 복원: 저장된 여행 번호가 지금 열린 여행과 같을 때만 유효하다 (이전 여행의 값은 버린다). */
fun restoredTripFlag(saved: Long, openTripId: Long?): Long? = if (openTripId != null && saved == openTripId) saved else null

/** 도착 카드 닫기: 30초가 지났거나 기어가 P를 벗어나면(R·N·D). 모르는 기어(UNKNOWN)는 벗어난 것으로 보지 않는다. */
fun arrivalCloseDue(shownMs: Long, nowMs: Long, gear: Gear): Boolean =
    nowMs - shownMs >= ARRIVAL_SHOW_MS || (gear != Gear.P && gear != Gear.UNKNOWN)

/**
 * 이번 방문이 몇 번째인지. 도착 시점의 지금 여행은 아직 열려 있어(endMs 없음) visitCount가 세지 않고,
 * visitCount의 +1이 바로 이번 방문이다. 목적지 좌표를 모르면 첫 방문으로 본다.
 */
suspend fun arrivalVisit(repo: TripRepository, lat: Double?, lon: Double?, nowMs: Long): Int =
    if (lat == null || lon == null) 1 else repo.visitCount(lat, lon, beforeMs = nowMs)

private val Ordinals = listOf("첫", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열")
fun visitOrdinal(n: Int): String = if (n in 1..10) "${Ordinals[n - 1]} 번째" else "${n}번째"

/** "강릉 경포해변, 두 번째 방문이에요" (카드·알림 공용) */
fun arrivalTitle(place: String?, visit: Int): String = "${place?.takeIf { it.isNotBlank() } ?: "이곳"}, ${visitOrdinal(visit)} 방문이에요"

/** 단골 단계 막대 3칸: 1회 스탬프 · 2회 그때 vs 지금 · 3회 단골 */
fun regularStage(visit: Int): Int = visit.coerceIn(1, 3)
/**
 * 단계 막대 아래 범례. 두 번째 방문부터 지난 방문을 비교할 앨범이 없으면(확인 안 한 여행만 있었거나 지웠을 때) 방문 수는 그대로 두되
 * "그때 vs 지금"을 약속하지 않는다: 2회 칸을 "다시 찾음"으로.
 */
fun stageLegend(visit: Int, hasThen: Boolean): String =
    if (visit >= 2 && !hasThen) "1회 스탬프 · 2회 다시 찾음 · 3회 단골" else "1회 스탬프 · 2회 그때 vs 지금 · 3회 단골"
fun regularStageText(visit: Int): String = when (regularStage(visit)) { 1 -> "단골까지 두 번 남았어요"; 2 -> "단골까지 한 번 남았어요"; else -> "단골이에요" }

/**
 * 사진 올리기 주소의 바탕. 우리 Supabase 프로젝트의 upload 함수(휴대폰 업로드 페이지로 보낸다). 주소는 CloudConfig(BuildConfig).
 * 여행 번호 대신 여행마다 만든 짐작할 수 없는 공유 토큰(ShareTokens)을 붙인다.
 */
val UPLOAD_BASE_URL: String = ai.yeoro.cloud.CloudConfig.fn("upload") + "?t="
fun arrivalUrl(shareToken: String): String = UPLOAD_BASE_URL + shareToken

/** 스탬프 안에 넣을 짧은 지명: 목적지 이름의 첫 낱말 */
fun stampPlace(destination: String?): String = destination?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: "이곳"
