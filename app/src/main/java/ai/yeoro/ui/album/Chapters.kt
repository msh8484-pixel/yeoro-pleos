package ai.yeoro.ui.album

import ai.yeoro.cloud.OutsideInfo
import ai.yeoro.cloud.outsideSentences
import ai.yeoro.data.parseJoined
import ai.yeoro.data.LocationSampleEntity
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.engine.Seat
import ai.yeoro.ui.live.personName
import ai.yeoro.geo.haversineKm
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/*
 * 앨범의 장(章) 구성과 규칙 기반 기록문. 화면과 무관한 순수 함수라 ChaptersTest가 그대로 검증한다.
 * 기록문은 차가 기록한 사실만 잇는다(동화체 금지). 2a에는 장소 이름(역지오코딩)이 없어 정차는 "N번째 쉼"이다.
 */

enum class ChapterKind { DEPARTURE, STOP, ARRIVAL, RETURN }

/**
 * 앨범의 한 장. startMs: 출발·정차 시작·도착·집으로 떠난 시각. stayMin: 머문 시간(분, 출발·돌아오는 길은 null).
 * homeMs: 돌아오는 길의 귀가 시각(마지막 샘플). moments: 이 장에 담긴 순간(시간순).
 */
data class Chapter(
    val kind: ChapterKind, val name: String, val startMs: Long, val stayMin: Int?,
    val lat: Double?, val lon: Double?, val moments: List<MomentEntity>, val homeMs: Long? = null,
) {
    val endMs: Long get() = homeMs ?: (startMs + (stayMin ?: 0) * 60_000L)
}

private const val HOME_RADIUS_KM = 0.3

/**
 * 출발(첫 샘플) → 도착 전 STOP 순간마다 한 장 → 도착(tripLegs의 도착 샘플, 샘플이 없으면 여행 끝) → 왕복이면 돌아오는 길.
 * 편도: 도착 이후에 시작한 정차는 도착 장에 합친다(그 정차 시간이 도착 장의 머문 시간, 없으면 여행 끝까지).
 * 왕복(집 → 목적지 → 집): 도착~떠남 사이 정차는 도착 장에 합치고, 머문 시간은 떠남 − 도착.
 *   떠남 이후의 모든 순간(정차 포함)은 "돌아오는 길" 한 장(시작 = 떠남, 끝 = 마지막 샘플)에 들어간다.
 *   이 장의 좌표는 돌아오는 길의 거리상 가운데 지점이다(지도 핀이 집 앞 출발 핀과 겹치지 않고 흐린 귀갓길 선 위에 놓인다).
 * 그 밖의 정차가 아닌 순간은 시간상 가장 가까운 장(장 구간 [시작, 시작+머문 시간]까지의 거리, 같으면 앞 장)에 들어간다.
 * legs: 이미 계산한 tripLegsOf 값(앨범 화면이 경로 나누기와 같은 값을 넘긴다). 주지 않으면 여기서 계산한다.
 */
fun buildChapters(
    trip: TripEntity, samples: List<LocationSampleEntity>, moments: List<MomentEntity>,
    home: Pair<Double, Double>? = null,
    legs: TripLegs? = tripLegsOf(trip, samples.sortedBy { it.timeMs }, moments, home),
): List<Chapter> {
    val sorted = moments.sortedBy { it.timeMs }
    val ordered = samples.sortedBy { it.timeMs }
    val first = ordered.firstOrNull()
    val arrived = legs?.let { ordered[it.arriveIdx] }
    val departMs = first?.timeMs ?: trip.startMs
    val arriveMs = arrived?.timeMs ?: trip.endMs ?: sorted.lastOrNull()?.timeMs ?: trip.startMs
    val leaveIdx = legs?.leaveIdx
    val leaveMs = leaveIdx?.let { ordered[it].timeMs }
    val returning = { m: MomentEntity -> leaveMs != null && m.timeMs > leaveMs }

    val stops = sorted.filter { it.type == "STOP" && !returning(it) }
    val (before, after) = stops.partition { it.timeMs < arriveMs }
    val merged = after.firstOrNull()

    val nearHome = home != null && first != null && haversineKm(home, first.lat to first.lon) <= HOME_RADIUS_KM
    val skeleton = buildList {
        add(Chapter(ChapterKind.DEPARTURE, if (nearHome) "집 앞 출발" else "출발", departMs, null, first?.lat, first?.lon, emptyList()))
        before.forEachIndexed { i, m ->
            val at = m.lat?.let { la -> m.lon?.let { lo -> la to lo } } ?: nearestSample(ordered, m.timeMs)
            add(Chapter(ChapterKind.STOP, "${koOrdinal(i + 1)} 쉼", m.timeMs, m.detail.toIntOrNull(), at?.first, at?.second, emptyList()))
        }
        val endStay = if (leaveMs != null) ((leaveMs - arriveMs) / 60_000L).toInt()
            else merged?.detail?.toIntOrNull() ?: trip.endMs?.let { ((it - arriveMs) / 60_000L).toInt() }?.takeIf { it >= 1 }
        add(Chapter(ChapterKind.ARRIVAL, trip.destination ?: "도착", arriveMs, endStay,
            trip.destLat ?: arrived?.lat, trip.destLon ?: arrived?.lon, emptyList()))
        if (leaveIdx != null && leaveMs != null) {
            val mid = routeMidpoint(ordered.subList(leaveIdx, ordered.size).map { it.lat to it.lon })
            add(Chapter(ChapterKind.RETURN, "돌아오는 길", leaveMs, null, mid?.first, mid?.second, emptyList(), homeMs = ordered.last().timeMs))
        }
    }

    val outbound = if (leaveMs != null) skeleton.lastIndex - 1 else skeleton.lastIndex   // 돌아오는 길을 뺀 마지막 장(도착)
    val buckets = List(skeleton.size) { mutableListOf<MomentEntity>() }
    for (m in sorted) {
        val idx = when {
            returning(m) -> skeleton.lastIndex
            m.type == "STOP" && m in before -> 1 + before.indexOf(m)
            m.type == "STOP" -> outbound
            else -> (0..outbound).minBy { i -> distanceTo(skeleton[i], m.timeMs) }   // minBy는 같으면 앞의 것
        }
        buckets[idx] += m
    }
    return skeleton.mapIndexed { i, c -> c.copy(moments = buckets[i]) }
}

private fun distanceTo(c: Chapter, t: Long): Long = when {
    t < c.startMs -> c.startMs - t
    t > c.endMs -> t - c.endMs
    else -> 0
}

private fun nearestSample(samples: List<LocationSampleEntity>, t: Long): Pair<Double, Double>? =
    samples.minByOrNull { abs(it.timeMs - t) }?.let { it.lat to it.lon }

/** "08시 52분" */
fun hourMinute(ms: Long, zone: ZoneId): String {
    val z = Instant.ofEpochMilli(ms).atZone(zone)
    return "%02d시 %02d분".format(z.hour, z.minute)
}

/** "08:52" (장 머리의 시각) */
fun clock(ms: Long, zone: ZoneId): String {
    val z = Instant.ofEpochMilli(ms).atZone(zone)
    return "%02d:%02d".format(z.hour, z.minute)
}

/**
 * 순간 하나를 사실 문장으로. 모르는 종류는 null.
 * names: 설정의 좌석별 가족 이름. 좌석이 남은 좌석 순간은 끝에 "(아빠·엄마·…)"를 붙인다(이름이 없으면 좌석 이름).
 */
fun momentSentence(m: MomentEntity, zone: ZoneId, names: Map<Seat, String> = emptyMap()): String? {
    val at = hourMinute(m.timeMs, zone)
    return when (m.type) {
        "COMPANION_JOINED" -> {
            val (from, to, seats) = parseJoined(m.detail)
            val who = if (seats.isEmpty()) "" else seats.joinToString("·", "(", ")") { personName(it, names) }
            when {
                to == null -> "$at, 함께 탄 사람이 늘었습니다$who."
                to >= 4 -> "$at, ${koCount(to)} 자리에 가족이 앉았습니다$who."
                from != null -> "$at, 탄 사람이 ${from}명에서 ${to}명으로 늘었습니다$who."
                else -> "$at, ${to}명이 함께 탔습니다$who."
            }
        }
        "RAIN_START" -> "${at}부터 와이퍼가 움직였습니다."
        "RAIN_STOP" -> "$at, 비가 그쳤습니다."
        "WINDOWS_OPEN" -> m.detail.toIntOrNull()?.let { "$at, 창문 ${koCount(it)} 개가 내려갔습니다." } ?: "$at, 창문이 내려갔습니다."
        "STOP" -> m.detail.toIntOrNull()?.let { "${at}부터 ${stayText(it)} 동안 쉬었습니다." } ?: "$at, 잠시 쉬었습니다."
        else -> null
    }
}

/**
 * 장의 기록문: 장 자체의 사건(출발·도착·집으로 출발·귀가)과 순간 문장을 시간순으로 잇는다. names: 좌석별 가족 이름(momentSentence).
 * 출발과 같은 분에 순간이 있으면 출발 시각 문장은 생략하고, 출발 장 끝에 "목적지는 …."을 붙인다.
 * outside: 도착 무렵 그날의 바깥(공공데이터). 도착 장 끝에만 문장(outsideSentences)을 잇는다.
 */
fun chapterDiary(c: Chapter, destination: String?, zone: ZoneId, names: Map<Seat, String> = emptyMap(), outside: OutsideInfo? = null): String {
    data class Line(val t: Long, val order: Int, val text: String)
    val lines = c.moments.mapNotNull { m -> momentSentence(m, zone, names)?.let { Line(m.timeMs, 1, it) } }.toMutableList()
    val minute = { ms: Long -> ms / 60_000L }
    when (c.kind) {
        ChapterKind.DEPARTURE ->
            if (c.moments.none { minute(it.timeMs) == minute(c.startMs) }) lines += Line(c.startMs, 0, "${hourMinute(c.startMs, zone)}에 출발했습니다.")
        ChapterKind.ARRIVAL -> lines += Line(c.startMs, 2,
            if (destination != null) "${hourMinute(c.startMs, zone)}, ${destination}에 도착했습니다." else "${hourMinute(c.startMs, zone)}에 도착했습니다.")
        ChapterKind.RETURN -> {
            lines += Line(c.startMs, 0, "${hourMinute(c.startMs, zone)}, 집으로 출발했습니다.")
            lines += Line(c.endMs, 2, "${hourMinute(c.endMs, zone)}, 집에 도착했습니다.")
        }
        ChapterKind.STOP -> {}
    }
    val out = lines.sortedWith(compareBy({ it.t }, { it.order })).map { it.text }.toMutableList()
    if (c.kind == ChapterKind.DEPARTURE && destination != null) out += "목적지는 $destination."
    if (c.kind == ChapterKind.ARRIVAL && outside != null) out += outsideSentences(outside)
    return out.joinToString(" ")
}

/**
 * 제목 규칙: 비 시작과 그침이 모두 있으면 "비가 그친 {때}, {목적지}(으)로", 창문 순간이 있으면 "창문을 연 날, {목적지}",
 * 둘 다 아니면 "{목적지}까지 {거리}km". {때}는 마지막 비 그침 시각(아침·오후·저녁·새벽)으로 사실에 맞춘다.
 */
fun albumTitle(destination: String?, moments: List<MomentEntity>, distanceKm: Double, zone: ZoneId): String {
    val rainStop = moments.filter { it.type == "RAIN_STOP" }.maxByOrNull { it.timeMs }
    val rained = rainStop != null && moments.any { it.type == "RAIN_START" }
    val km = distanceKm.roundToInt()
    return when {
        rained -> {
            val part = partOfDay(rainStop!!.timeMs, zone)
            if (destination != null) "비가 그친 $part, ${withRo(destination)}" else "비가 그친 ${part}의 여행"
        }
        moments.any { it.type == "WINDOWS_OPEN" } -> if (destination != null) "창문을 연 날, $destination" else "창문을 연 날"
        destination != null -> "${destination}까지 ${km}km"
        else -> "${km}km를 달린 날"
    }
}

private fun partOfDay(ms: Long, zone: ZoneId): String = when (Instant.ofEpochMilli(ms).atZone(zone).hour) {
    in 5..11 -> "아침"; in 12..17 -> "오후"; in 18..23 -> "저녁"; else -> "새벽"
}

/** 점들을 이은 선의 거리상 가운데 지점(두 점 사이는 직선 보간). 점이 없으면 null */
fun routeMidpoint(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
    if (points.size <= 1) return points.firstOrNull()
    val legs = points.zipWithNext { a, b -> haversineKm(a, b) }
    var left = legs.sum() / 2
    for ((i, d) in legs.withIndex()) {
        if (left <= d && d > 0) {
            val f = left / d; val a = points[i]; val b = points[i + 1]
            return (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
        }
        left -= d
    }
    return points.last()
}

/** 위치 샘플을 시간순으로 이은 거리(km) */
fun routeKm(samples: List<LocationSampleEntity>): Double =
    samples.sortedBy { it.timeMs }.zipWithNext { a, b -> haversineKm(a.lat to a.lon, b.lat to b.lon) }.sum()

/** "4:28" */
fun durationText(ms: Long): String { val m = (ms / 60_000L).toInt(); return "${m / 60}:%02d".format(m % 60) }

/** "38분", "1시간", "1시간 12분" */
fun stayText(min: Int): String = when {
    min < 60 -> "${min}분"
    min % 60 == 0 -> "${min / 60}시간"
    else -> "${min / 60}시간 ${min % 60}분"
}

private val KoCounts = listOf("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열")
/** 단위 앞 한국어 수(한·두·세·네…). 11부터는 숫자. */
fun koCount(n: Int): String = KoCounts.getOrNull(n - 1) ?: n.toString()
/** 첫 번째, 두 번째 … 열 번째, 그다음은 "11번째" */
fun koOrdinal(n: Int): String = when (n) { 1 -> "첫 번째"; in 2..10 -> "${koCount(n)} 번째"; else -> "${n}번째" }

/** 받침에 따라 "으로"/"로" (ㄹ 받침은 "로"). 한글이 아니면 "로". */
fun withRo(word: String): String {
    val c = word.lastOrNull() ?: return word
    if (c !in '가'..'힣') return word + "로"
    val jong = (c - '가') % 28
    return word + if (jong == 0 || jong == 8) "로" else "으로"
}

/** 탑승 인원: 좌석 순간("2→4")의 최댓값. 기록이 없으면 null. */
fun seatCount(moments: List<MomentEntity>): Int? =
    moments.filter { it.type == "COMPANION_JOINED" }.mapNotNull { parseJoined(it.detail).to }.maxOrNull()

/**
 * (위도, 경도)를 w×h 화면에 등장방형으로 투영한다. 가로 = 경도×cos(평균 위도), 북쪽이 위, 사방 여백 margin, 비율 유지, 가운데 정렬.
 * 결과는 [x0, y0, x1, y1, …].
 */
fun projectRoute(points: List<Pair<Double, Double>>, w: Float, h: Float, margin: Float = 0.08f): FloatArray {
    if (points.isEmpty()) return FloatArray(0)
    val k = cos(Math.toRadians(points.map { it.first }.average()))
    val xs = points.map { it.second * k }; val ys = points.map { it.first }
    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
    val dx = (maxX - minX).coerceAtLeast(1e-9); val dy = (maxY - minY).coerceAtLeast(1e-9)
    val s = minOf(w * (1 - 2 * margin) / dx, h * (1 - 2 * margin) / dy)
    val ox = (w - dx * s) / 2; val oy = (h - dy * s) / 2
    val single = maxX - minX < 1e-9 && maxY - minY < 1e-9
    return FloatArray(points.size * 2).also { out ->
        points.indices.forEach { i ->
            out[2 * i] = if (single) w / 2 else (ox + (xs[i] - minX) * s).toFloat()
            out[2 * i + 1] = if (single) h / 2 else (oy + (maxY - ys[i]) * s).toFloat()
        }
    }
}

/** "차가 기억한 순간" 칩: 짧은 글, 출처(어떤 신호로 알았는지), 아이콘. 출처·아이콘은 지금 화면(momentLine)과 같다. */
data class MomentChip(val text: String, val source: String, val icon: ai.yeoro.ui.live.MomentIcon)

fun momentChip(m: MomentEntity, zone: ZoneId): MomentChip? {
    val line = ai.yeoro.ui.live.momentLine(m.type, m.detail) ?: return null
    val text = when (m.type) {
        "STOP" -> m.detail.toIntOrNull()?.let { "정차 ${stayText(it)}" } ?: "정차"
        "RAIN_START" -> "${clock(m.timeMs, zone)} 비 시작"
        "RAIN_STOP" -> "${clock(m.timeMs, zone)} 비 그침"
        "WINDOWS_OPEN" -> "창문 ${m.detail}개 열림"
        "COMPANION_JOINED" -> parseJoined(m.detail).to?.let { "${it}석 착석" } ?: "동승 늘어남"
        else -> line.text
    }
    return MomentChip(text, line.source, line.icon)
}
