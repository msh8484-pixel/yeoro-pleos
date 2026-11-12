package ai.yeoro.ui.album

import ai.yeoro.cloud.TripOutside
import ai.yeoro.cloud.parseTripOutside
import ai.yeoro.data.LocationSampleEntity
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.data.YeoroDao
import ai.yeoro.geo.haversineKm
import ai.yeoro.ui.common.PhotoImage
import ai.yeoro.ui.live.LiveIcons
import ai.yeoro.ui.theme.AlbumColors
import ai.yeoro.ui.theme.Pretendard
import ai.yeoro.ux.rememberMotionProgress
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 그때 vs 지금 (시안 장면 10). 같은 목적지(500m 안)를 두 번째 찾았을 때 지난 방문과 오늘을 나란히 놓는다.
 * 비교는 차가 기록한 사실(도착 시각·와이퍼·좌석)과 받아 둔 기상청 값만 쓴다. 없는 값은 지어내지 않는다.
 */

/**
 * 한 번의 방문을 비교에 필요한 값만으로.
 * arriveMs: 도착 장 시작. rained: 이 여행에 와이퍼가 한 번이라도 켜짐(RAIN_START, 일러스트용).
 * tempC·sky·precip: 받아 둔 도착 날씨(기상청), 없으면 null. observed: 그 날씨가 실황(관측)이면 true, 예보면 false, 날씨가 없으면 null.
 * rainOnWay: 도착 전에 와이퍼가 켜짐(오는 길의 비). rainAtArrival: 도착할 때도 와이퍼가 켜져 있음(마지막 RAIN_START 뒤 RAIN_STOP 없이 도착).
 * photoFile: 표지 사진 파일.
 */
data class VisitSummary(
    val tripId: Long, val arriveMs: Long, val rained: Boolean, val tempC: Double?, val sky: String?, val seats: Int?, val photoFile: String?,
    val precip: String? = null, val observed: Boolean? = null, val rainOnWay: Boolean = false, val rainAtArrival: Boolean = false,
)

/**
 * 화면 한 칸. 날짜 "2025.11.01", 사실 "흐림 · 14℃ · 오후 2시 41분 도착"(= weather · arriveText).
 * weather: 사실의 날씨 부분("흐림 · 14℃", "오는 길에 비"), weatherSource: 그 출처("기상청 실황"·"기상청 예보"·"와이퍼"). 날씨를 모르면 둘 다 null.
 * arriveText: "오후 2시 41분 도착". hour·rained: 사진이 없을 때 그릴 일러스트의 시간대·비.
 */
data class VisitSide(
    val tripId: Long, val dateText: String, val fact: String, val photoFile: String?,
    val weather: String?, val weatherSource: String?, val arriveMs: Long, val arriveText: String, val hour: Int, val rained: Boolean,
)

enum class ComparisonKind { TIME, SKY, RAIN_ON_WAY, TEMP, SEATS }

/** 비교 칩 한 개: 문구와 출처(칩 꼬리) */
data class Comparison(val text: String, val source: String, val kind: ComparisonKind)

data class ThenNow(val then: VisitSide, val now: VisitSide, val heading: String, val comparisons: List<Comparison>)

/** 앨범·도착 카드가 같은 목적지로 보는 반경 (visitCount와 같다) */
const val SAME_PLACE_RADIUS_M = 500

/** 앨범이 있는(ALBUM_READY) 여행 중 목적지가 current 목적지 radiusM 안이고 current 출발 전에 끝난 것 가운데 가장 늦게 끝난 것 */
fun previousVisit(trips: List<TripEntity>, current: TripEntity, radiusM: Int = SAME_PLACE_RADIUS_M): TripEntity? {
    val la = current.destLat ?: return null
    val lo = current.destLon ?: return null
    return trips.filter { t ->
        t.id != current.id && t.state == "ALBUM_READY" && t.endMs != null && t.endMs < current.startMs &&
            t.destLat != null && t.destLon != null && haversineKm(la to lo, t.destLat to t.destLon) * 1000 <= radiusM
    }.maxByOrNull { it.endMs!! }
}

/** "오후 2시 41분" (정각이면 "오후 2시") */
fun koClock(ms: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val h12 = (t.hour % 12).let { if (it == 0) 12 else it }
    return "${if (t.hour < 12) "오전" else "오후"} ${h12}시" + if (t.minute != 0) " ${t.minute}분" else ""
}

private const val KMA_OBSERVED = "기상청 실황"
private const val KMA_FORECAST = "기상청 예보"
private const val WIPER = "와이퍼"

/** 받아 둔 기상청 날씨가 있는지 (있으면 캡션은 그 값을 쓴다) */
private fun hasKma(v: VisitSummary) = v.precip != null || v.sky != null || v.tempC != null

/**
 * 사실의 날씨 부분과 출처.
 * 기상청 값이 있으면 "흐림 · 14℃"(강수가 있으면 하늘 대신 강수), 출처는 실황/예보.
 * 없으면 와이퍼로: 도착할 때도 비 "비", 오는 길에만 비 "오는 길에 비", 도착 뒤에만 비 "비 온 날". 아무것도 없으면 null.
 */
fun visitWeather(v: VisitSummary): Pair<String, String>? = when {
    hasKma(v) -> listOfNotNull(v.precip ?: v.sky, v.tempC?.let { "${it.roundToInt()}℃" }).joinToString(" · ") to
        (if (v.observed == false) KMA_FORECAST else KMA_OBSERVED)
    v.rainAtArrival -> "비" to WIPER
    v.rainOnWay -> "오는 길에 비" to WIPER
    v.rained -> "비 온 날" to WIPER
    else -> null
}

/** "오후 2시 41분 도착" */
fun arriveText(v: VisitSummary, zone: ZoneId) = "${koClock(v.arriveMs, zone)} 도착"

/** "흐림 · 14℃ · 오후 2시 41분 도착", "오는 길에 비 · 오후 2시 41분 도착", "오후 2시 41분 도착". 항상 "… 도착"으로 끝난다 */
fun visitFact(v: VisitSummary, zone: ZoneId): String = listOfNotNull(visitWeather(v)?.first, arriveText(v, zone)).joinToString(" · ")

private val DateDots = DateTimeFormatter.ofPattern("yyyy.MM.dd")
fun visitSide(v: VisitSummary, zone: ZoneId): VisitSide {
    val w = visitWeather(v)
    val at = Instant.ofEpochMilli(v.arriveMs).atZone(zone)
    return VisitSide(v.tripId, at.format(DateDots), visitFact(v, zone), v.photoFile, w?.first, w?.second, v.arriveMs, arriveText(v, zone), at.hour, v.rained)
}

/** 두 방문의 날짜 차가 330~400일이면 "일 년" 차이 */
private fun aboutAYear(thenMs: Long, nowMs: Long, zone: ZoneId): Boolean =
    ChronoUnit.DAYS.between(Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate(), Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()) in 330..400

fun thenNowHeading(thenMs: Long, nowMs: Long, zone: ZoneId): String = if (aboutAYear(thenMs, nowMs, zone)) "같은 곳, 일 년의 차이" else "지난번과 오늘"

/** "1시간 21분", "40분", "2시간" */
private fun spanText(min: Int): String = listOfNotNull((min / 60).takeIf { it > 0 }?.let { "${it}시간" }, (min % 60).takeIf { it > 0 }?.let { "${it}분" }).joinToString(" ")

/** 비교에 쓰는 도착 날씨: (비·눈이 왔나, 낱말, 출처). 캡션과 같은 값을 쓴다. 기상청 예보는 비교하지 않고(null), 기상청 값이 없으면 와이퍼(도착할 때 켜져 있었을 때만) */
private fun arrivalSky(v: VisitSummary): Triple<Boolean, String, String>? = when {
    hasKma(v) -> if (v.observed == true) (v.precip ?: v.sky)?.let { Triple(v.precip != null, it, KMA_OBSERVED) } else null
    v.rainAtArrival -> Triple(true, "비", WIPER)
    else -> null
}

/**
 * 최대 3개, 이 순서:
 * 1. 도착 시각(하루 중) 차 10분 이상 "작년보다 1시간 21분 일찍 도착" (330~400일이면 "작년", 아니면 "지난번"). 운행 기록.
 * 2. 도착할 때의 날씨(캡션에 보이는 그 순간): 낱말이 다르면 "그때는 비, 이번엔 맑음", 같고 비·눈이면 "그때도 이번에도 비", 같고 맑으면 없음.
 *    두 쪽 모두 기상청 실황이거나 도착할 때 와이퍼가 켜져 있었을 때만. 예보는 비교하지 않는다.
 * 3. 오는 길에 그친 비(와이퍼, 도착할 때는 꺼짐): "두 번 모두 오는 길에 비가 그쳤어요" / "이번엔 …" / "그때는 …".
 * 4. 기온: 두 쪽 모두 기상청 실황이고 2℃ 이상 차이 "4℃ 더 따뜻했어요"/"쌀쌀했어요". 예보가 한쪽이라도 있으면 비교하지 않는다.
 * 5. 인원 차이 "이번엔 한 명 더 함께"/"이번엔 한 명 적게". 좌석.
 */
fun comparisons(then: VisitSummary, now: VisitSummary, zone: ZoneId): List<Comparison> {
    val out = mutableListOf<Comparison>()
    fun minuteOfDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }
    val d = minuteOfDay(now.arriveMs) - minuteOfDay(then.arriveMs)
    if (abs(d) >= 10) {
        val who = if (aboutAYear(then.arriveMs, now.arriveMs, zone)) "작년" else "지난번"
        out += Comparison("${who}보다 ${spanText(abs(d))} ${if (d < 0) "일찍" else "늦게"} 도착", "운행 기록", ComparisonKind.TIME)
    }
    val ts = arrivalSky(then); val ns = arrivalSky(now)
    if (ts != null && ns != null) {
        val src = setOf(ts.third, ns.third).sortedBy { it != KMA_OBSERVED }.joinToString("·")
        when {
            ts.second != ns.second -> out += Comparison("그때는 ${ts.second}, 이번엔 ${ns.second}", src, ComparisonKind.SKY)
            ts.first -> out += Comparison("그때도 이번에도 ${ns.second}", src, ComparisonKind.SKY)
        }
    }
    // 도착할 때 비가 보이는 쪽(와이퍼가 켜져 있었거나 기상청 강수)에는 "그쳤다"고 말하지 않는다
    fun stopped(v: VisitSummary) = v.rainOnWay && !v.rainAtArrival && !(hasKma(v) && v.precip != null)
    val thenStop = stopped(then)
    val nowStop = stopped(now)
    when {
        thenStop && nowStop -> "두 번 모두 오는 길에 비가 그쳤어요"
        nowStop -> "이번엔 오는 길에 비가 그쳤어요"
        thenStop -> "그때는 오는 길에 비가 그쳤어요"
        else -> null
    }?.let { out += Comparison(it, WIPER, ComparisonKind.RAIN_ON_WAY) }
    if (then.observed == true && now.observed == true && then.tempC != null && now.tempC != null) {
        val t = now.tempC - then.tempC
        if (abs(t) >= 2.0) out += Comparison("${abs(t).roundToInt()}℃ 더 ${if (t > 0) "따뜻했어요" else "쌀쌀했어요"}", KMA_OBSERVED, ComparisonKind.TEMP)
    }
    if (then.seats != null && now.seats != null && then.seats != now.seats) {
        val n = now.seats - then.seats
        out += Comparison("이번엔 ${koCount(abs(n))} 명 ${if (n > 0) "더 함께" else "적게"}", "좌석", ComparisonKind.SEATS)
    }
    return out.take(3)
}

/** 여행 한 번의 비교 값. 장·사진 맞추기는 앨범과 같은 규칙(buildChapters, assignPhotos, coverPhoto) */
fun visitSummary(
    trip: TripEntity, samples: List<LocationSampleEntity>, moments: List<MomentEntity>, outside: TripOutside?,
    photos: List<AlbumPhoto>, home: Pair<Double, Double>?,
): VisitSummary {
    val chapters = buildChapters(trip, samples.sortedBy { it.timeMs }, moments, home)
    val cover = coverPhoto(chapters, assignPhotos(chapters, photos))
    return summaryOf(trip.id, chapters, moments, outside, cover, trip.endMs ?: trip.startMs)
}

/** 도착 장 시작(없으면 fallbackMs), 와이퍼 순간(오는 길·도착할 때), 받아 둔 도착 날씨(실황/예보), 좌석, 표지로 비교 값을 만든다 */
internal fun summaryOf(tripId: Long, chapters: List<Chapter>, moments: List<MomentEntity>, outside: TripOutside?, cover: AlbumPhoto?, fallbackMs: Long): VisitSummary {
    val w = outside?.arrival?.weather?.takeIf { it.tempC != null || it.sky != null || it.precip != null }
    val arrive = chapters.lastOrNull { it.kind == ChapterKind.ARRIVAL }?.startMs ?: fallbackMs
    val rainEvents = moments.filter { (it.type == "RAIN_START" || it.type == "RAIN_STOP") && it.timeMs <= arrive }.sortedBy { it.timeMs }
    return VisitSummary(
        tripId = tripId,
        arriveMs = arrive,
        rained = moments.any { it.type == "RAIN_START" },
        tempC = w?.tempC, sky = w?.sky, precip = w?.precip, observed = w?.observed,
        seats = seatCount(moments),
        photoFile = cover?.let { it.fullFile ?: it.thumbFile },
        rainOnWay = rainEvents.any { it.type == "RAIN_START" },
        rainAtArrival = rainEvents.lastOrNull()?.type == "RAIN_START",
    )
}

/** 저장된 여행 하나의 비교 값 (DB에서 읽는다) */
suspend fun loadVisitSummary(dao: YeoroDao, trip: TripEntity, home: Pair<Double, Double>?): VisitSummary =
    visitSummary(trip, dao.samplesNow(trip.id), dao.momentsNow(trip.id), dao.outsideNow(trip.id)?.let { parseTripOutside(it.json) },
        dao.photosNow(trip.id).map(::albumPhoto), home)

/** current(열린 여행이어도 된다)의 지난 방문 쪽. 없으면 null */
suspend fun loadThenSide(dao: YeoroDao, current: TripEntity, home: Pair<Double, Double>?, zone: ZoneId): Pair<VisitSummary, VisitSide>? {
    val prev = previousVisit(dao.endedTripsWithDest(), current) ?: return null
    val s = loadVisitSummary(dao, prev, home)
    return s to visitSide(s, zone)
}

/* ───────────────────────── 앨범 한 쪽 (장면 10) ───────────────────────── */

private val KoHeading = LineBreak.Heading.copy(wordBreak = LineBreak.WordBreak.Phrase)
private val CaptionInk = Color(0xFF090B14)

private fun comparisonIcon(c: Comparison): ImageVector = when (c.kind) {
    ComparisonKind.TIME -> LiveIcons.Clock
    ComparisonKind.SKY, ComparisonKind.RAIN_ON_WAY -> LiveIcons.Rain
    ComparisonKind.TEMP -> LiveIcons.Sun
    ComparisonKind.SEATS -> LiveIcons.Seat
}

/**
 * 그때 vs 지금 · {목적지}: 두 사진 칸과 비교 칩(실선).
 * 두 칸은 250ms 간격으로 떠오른다(모션 허용 시). visible: 이 쪽이 pager에서 자리 잡은 쪽일 때만 true.
 * pager가 옆 쪽을 미리 그려 두므로, 보이기 전에는 0에 멈춰 있다가 넘겨 와서 자리 잡으면 그때 떠오른다.
 */
@Composable internal fun ThenNowPage(a: AlbumData, tn: ThenNow, visible: Boolean = true, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val narrow = maxWidth < 1100.dp
        val side = if (narrow) 40.dp else 90.dp
        val figH = (maxHeight * 0.52f).coerceIn(300.dp, 600.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = side, end = side, top = 56.dp, bottom = 16.dp)) {
            Text(listOfNotNull("그때 vs 지금", a.destination).joinToString(" · "), fontSize = 24.sp, letterSpacing = 0.12.em, color = AlbumColors.Amber)
            Text(tn.heading, Modifier.padding(top = 12.dp), style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 54.sp,
                lineHeight = 68.sp, color = AlbumColors.Text, lineBreak = KoHeading))
            Row(Modifier.padding(top = 28.dp).fillMaxWidth().height(figH), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                Figure(a, 0, tn.then, tn.then.hour, tn.then.rained, visible, Modifier.weight(1f).fillMaxHeight())
                Figure(a, 1, tn.now, a.arriveHour, a.rained, visible, Modifier.weight(1f).fillMaxHeight())
            }
            if (tn.comparisons.isNotEmpty()) Box(Modifier.padding(top = 26.dp)) {
                ChipFlow(tn.comparisons.map { ChipData(it.text, it.source, comparisonIcon(it), dashed = false) })
            }
        }
    }
}

@Composable private fun Figure(a: AlbumData, i: Int, s: VisitSide, hour: Int, rained: Boolean, visible: Boolean, modifier: Modifier) {
    val p by rememberMotionProgress("thennow-${a.tripId}-$i", tween(800, easing = EaseOutCubic), delayMs = 150 + i * 250, active = visible)
    val dy = with(LocalDensity.current) { 30.dp.toPx() }
    val shape = RoundedCornerShape(22.dp)
    val illustration = Modifier.fillMaxSize().scene(hour, rained, seed = 31 + i * 7, sea = seaLike(a.destination))
    Box(modifier.graphicsLayer { alpha = p; translationY = (1f - p) * dy }.clip(shape)) {
        if (s.photoFile != null) PhotoImage(s.photoFile, Modifier.fillMaxSize(), maxPx = 1280) { Box(illustration) } else Box(illustration)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, CaptionInk.copy(alpha = 0.92f))))
            .padding(start = 30.dp, end = 30.dp, top = 48.dp, bottom = 26.dp)) {
            Text(s.dateText, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 54.sp, color = Color.White)
            Text(captionText(s), fontSize = 30.sp, lineHeight = 42.sp, color = Color(0xFFD6D9E6),
                style = TextStyle(lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)))
        }
    }
}

/** "맑음 · 12℃ ⁽기상청 실황⁾ · 오후 1시 22분 도착": 날씨 부분 바로 뒤에 출처를 작게 */
private fun captionText(s: VisitSide): AnnotatedString = buildAnnotatedString {
    if (s.weather != null) {
        append(s.weather)
        s.weatherSource?.let { src -> append("\u00A0"); withStyle(SpanStyle(fontSize = 22.sp, color = Color(0xFFA3A9BE))) { append(src.replace(" ", "\u00A0")) } }
        append(" · ")
    }
    append(s.arriveText.replace(TimeWords, "$1\u00A0$2"))
}

/** "2시 41분"이 줄 끝에서 갈라지지 않게 */
private val TimeWords = Regex("(\\d+시) (\\d+분)")
