package ai.yeoro.ui.home

import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.geo.haversineKm
import ai.yeoro.ui.album.albumTitle
import ai.yeoro.ui.album.seaLike
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

/*
 * 홈(우리 가족 여행)의 묶음·통계·지도 투영. 화면과 무관한 순수 함수라 ShelfTest가 그대로 검증한다.
 */

const val SAME_PLACE_M = 500

/** 같은 목적지(서로 500m 안)에 다녀온 여행 묶음. trips는 시간순, name은 가장 최근 여행의 목적지 이름. */
data class PlaceGroup(val name: String, val lat: Double, val lon: Double, val trips: List<TripEntity>) {
    val count: Int get() = trips.size
}

/**
 * 목적지 좌표가 있는 여행을 시간순으로 훑으며, 기존 묶음의 기준점(첫 여행 목적지)에서 radiusM 안이면 그 묶음에, 아니면 새 묶음에 넣는다.
 * 결과는 방문 많은 순, 같으면 최근 방문 순.
 */
fun placeGroups(trips: List<TripEntity>, radiusM: Int = SAME_PLACE_M): List<PlaceGroup> {
    val anchors = mutableListOf<Pair<Pair<Double, Double>, MutableList<TripEntity>>>()
    trips.filter { it.destLat != null && it.destLon != null }.sortedBy { it.startMs }.forEach { t ->
        val p = t.destLat!! to t.destLon!!
        val hit = anchors.firstOrNull { haversineKm(it.first, p) * 1000 <= radiusM }
        if (hit != null) hit.second += t else anchors += p to mutableListOf(t)
    }
    return anchors.map { (p, ts) -> PlaceGroup(ts.lastOrNull { it.destination != null }?.destination ?: "이름 없는 곳", p.first, p.second, ts) }
        .sortedWith(compareByDescending<PlaceGroup> { it.count }.thenByDescending { it.trips.last().startMs })
}

/** 정기 방문: 같은 목적지에 두 번 이상 */
fun regularGroups(groups: List<PlaceGroup>): List<PlaceGroup> = groups.filter { it.count >= 2 }

private val DotDate = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/** "2025.10.25 · 2026.10.24" */
fun groupDates(g: PlaceGroup, zone: ZoneId): String = g.trips.joinToString(" · ") { Instant.ofEpochMilli(it.startMs).atZone(zone).format(DotDate) }

data class HomeStats(val kmThisYear: Int, val places: Int, val trips: Int)

/** 올해 달린 km(올해 출발한 여행의 거리 합), 간 곳(같은 목적지 묶음 수), 여행 수. trips는 책장의 여행. */
fun homeStats(trips: List<TripEntity>, kmByTrip: Map<Long, Double>, year: Int, zone: ZoneId): HomeStats {
    val km = trips.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).year == year }.sumOf { kmByTrip[it.id] ?: 0.0 }
    return HomeStats(km.roundToInt(), placeGroups(trips).size, trips.size)
}

/** 책장에 꽂히는 여행: 끝났고 앨범이 준비된 것, 최근 순 */
fun shelfTrips(trips: List<TripEntity>): List<TripEntity> =
    trips.filter { it.state == "ALBUM_READY" && it.endMs != null }.sortedByDescending { it.startMs }

/** 맨 위 카드로 물어볼 확인 안 한 여행(가장 최근 하나) */
fun latestUnconfirmed(trips: List<TripEntity>): TripEntity? =
    trips.filter { it.state == "UNCONFIRMED" && it.endMs != null }.maxByOrNull { it.startMs }

/** 책 한 권. hour: 표지 일러스트 시간대(여행이 끝난 시각). */
data class Book(
    val tripId: Long, val title: String, val date: String, val place: String,
    val hour: Int, val rained: Boolean, val sea: Boolean, val isNew: Boolean,
)

private val BookDate = DateTimeFormatter.ofPattern("yyyy.MM.dd E", Locale.KOREAN)

fun book(trip: TripEntity, moments: List<MomentEntity>, km: Double, opened: Set<Long>, zone: ZoneId): Book = Book(
    tripId = trip.id,
    title = trip.title ?: albumTitle(trip.destination, moments, km, zone),
    date = Instant.ofEpochMilli(trip.startMs).atZone(zone).format(BookDate),
    place = trip.destination ?: "목적지 기록 없음",
    hour = Instant.ofEpochMilli(trip.endMs ?: trip.startMs).atZone(zone).hour,
    rained = moments.any { it.type == "RAIN_START" },
    sea = seaLike(trip.destination),
    isNew = trip.id !in opened,
)

private val UnconfirmedDay = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
private val Hm = DateTimeFormatter.ofPattern("HH:mm")

/** "10월 18일 토요일 10:12~11:40 · 41km" (거리를 모르면 뒤를 뺀다) */
fun unconfirmedLine(trip: TripEntity, km: Double?, zone: ZoneId): String {
    val s = Instant.ofEpochMilli(trip.startMs).atZone(zone)
    val e = Instant.ofEpochMilli(trip.endMs ?: trip.startMs).atZone(zone)
    return buildString {
        append(s.format(UnconfirmedDay)).append(' ').append(s.format(Hm)).append('~').append(e.format(Hm))
        km?.let { append(" · ").append(it.roundToInt()).append("km") }
    }
}

/* ── 가족 지도: 지도 타일 없이 한반도 남쪽 경위도 상자에 점을 찍는다 ── */

private const val LAT_S = 33.0
private const val LAT_N = 38.7
private const val LON_W = 124.5
private const val LON_E = 131.0
private val LonScale = cos(Math.toRadians((LAT_S + LAT_N) / 2))

/** 등장방형 투영. 가로 = 경도×cos(가운데 위도), 북쪽이 위, 비율 유지·가운데 정렬. 상자 밖 좌표는 가장자리로 붙인다. */
fun projectKorea(lat: Double, lon: Double, w: Float, h: Float): Pair<Float, Float> {
    val uw = (LON_E - LON_W) * LonScale; val uh = LAT_N - LAT_S
    val s = minOf(w / uw, h / uh)
    val ox = (w - uw * s) / 2; val oy = (h - uh * s) / 2
    val la = lat.coerceIn(LAT_S, LAT_N); val lo = lon.coerceIn(LON_W, LON_E)
    return (ox + (lo - LON_W) * LonScale * s).toFloat() to (oy + (LAT_N - la) * s).toFloat()
}

/** 지도 경위선(1도 간격) 값 */
val KoreaLats = (34..38).map { it.toDouble() }
val KoreaLons = (125..130).map { it.toDouble() }

/** 방문 횟수에 따른 점 반지름(dp): 1회 22, 한 번 더 갈 때마다 6씩, 최대 40 (안에 숫자가 들어간다) */
fun dotRadiusDp(count: Int): Int = (22 + 6 * (count - 1)).coerceIn(22, 40)
