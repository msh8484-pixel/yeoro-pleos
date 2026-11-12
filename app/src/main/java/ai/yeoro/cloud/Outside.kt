package ai.yeoro.cloud

import ai.yeoro.engine.PlaceKind
import ai.yeoro.ui.album.ChapterKind
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/*
 * 그날의 바깥 (순수). outside 함수 응답(Task 3)을 읽고, 앨범 칩과 기록문 문장을 만든다.
 * trip_outside.json = {"arrival": <outside 응답 원문 | null>, "departure": <… | null>}.
 * 받지 못한 값은 가짜로 채우지 않는다: 값이 없는 칩·문장은 빠진다.
 */

/** observed: 기상청 실황(관측값), false면 단기예보 */
data class Weather(val tempC: Double?, val sky: String?, val precip: String?, val observed: Boolean)
/** "06:41", "17:36" */
data class SunTimes(val sunrise: String, val sunset: String)
/**
 * distanceM: 이름을 고른 관광지까지 거리(m). 앞으로 보조 표시에 쓰려고 읽어 둔다("{name} 근처", 200m 이하면 근처 없이 이름만).
 * 2b에서는 화면에 보이지 않는다(앨범 제목·리드는 목적지 이름 그대로).
 */
data class PlaceInfo(val kind: PlaceKind, val name: String?, val homepage: String?, val distanceM: Int? = null)
/** dayIndex: 그날이 축제 며칠째인가(1부터). distanceKm: 목적지에서 축제 장소까지 */
data class Festival(val name: String, val start: LocalDate, val end: LocalDate, val dayIndex: Int, val distanceKm: Double, val homepage: String?)
data class OutsideInfo(val atMs: Long, val weather: Weather?, val sun: SunTimes?, val place: PlaceInfo?, val festival: Festival?, val holiday: String?)
data class TripOutside(val arrival: OutsideInfo?, val departure: OutsideInfo?)

enum class OutsideChipKind { WEATHER, SUN, FESTIVAL, HOLIDAY }
data class OutsideChipData(val text: String, val source: String, val kind: OutsideChipKind)

/** outside 응답 하나. 모르는 필드·null 칸은 견디고, JSON이 깨졌거나 at이 없으면 null */
fun parseOutside(json: String): OutsideInfo? = try { parseOutside(JSONObject(json)) } catch (_: Exception) { null }

private fun parseOutside(o: JSONObject): OutsideInfo? {
    val atMs = o.str("at")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: return null
    return OutsideInfo(
        atMs = atMs,
        weather = o.optJSONObject("weather")?.let { w ->
            Weather(w.num("tempC"), w.str("sky"), w.str("precip"), observed = w.str("kind") == "observed")
        },
        sun = o.optJSONObject("sun")?.let { s -> s.str("sunset")?.let { SunTimes(s.str("sunrise").orEmpty(), it) } },
        place = o.optJSONObject("place")?.let { p ->
            PlaceInfo(p.str("kind")?.let { k -> PlaceKind.entries.firstOrNull { it.name == k } } ?: PlaceKind.OTHER,
                p.str("name"), p.str("homepage"), p.num("distanceM")?.roundToInt())
        },
        festival = o.optJSONObject("festival")?.let { f ->
            runCatching {
                Festival(f.str("name")!!, LocalDate.parse(f.str("start")), LocalDate.parse(f.str("end")),
                    f.getInt("dayIndex"), f.num("distanceKm") ?: 0.0, f.str("homepage"))
            }.getOrNull()
        },
        holiday = o.optJSONObject("holiday")?.str("name"),
    )
}

/** trip_outside.json → 도착·출발. 깨졌으면 둘 다 null */
fun parseTripOutside(json: String): TripOutside {
    val (a, d) = tripOutsideParts(json)
    return TripOutside(a?.let(::parseOutside), d?.let(::parseOutside))
}

/** trip_outside.json의 원문 두 칸 (arrival, departure) */
fun tripOutsideParts(json: String): Pair<String?, String?> = try {
    val o = JSONObject(json)
    o.optJSONObject("arrival")?.toString() to o.optJSONObject("departure")?.toString()
} catch (_: Exception) { null to null }

/** 원문 두 칸을 trip_outside.json으로. 깨진 원문은 null로 둔다 */
fun tripOutsideJson(arrival: String?, departure: String?): String = JSONObject()
    .put("arrival", arrival.toJsonOrNull())
    .put("departure", departure.toJsonOrNull())
    .toString()

/**
 * 시연 시계: 날씨만 다른 시각(실제 지금)으로 따로 물은 응답을 본 응답에 합친다. at은 본 응답 것을 쓴다.
 * 한쪽만 있으면 그쪽을 쓴다. 둘 다 없으면 null.
 */
fun mergeOutsideWeather(main: String?, weather: String?): String? {
    val m = main?.let { runCatching { JSONObject(it) }.getOrNull() }
    val w = weather?.let { runCatching { JSONObject(it) }.getOrNull() }
    if (m == null) return w?.toString()
    if (w != null) m.put("weather", w.opt("weather") ?: JSONObject.NULL)
    return m.toString()
}

/** 칩: DEPARTURE 장은 departure 날씨만, ARRIVAL 장(과 공개 화면)은 arrival의 날씨·일몰·축제·휴일, STOP·RETURN은 없다 */
fun outsideChips(o: TripOutside, kind: ChapterKind): List<OutsideChipData> = when (kind) {
    ChapterKind.DEPARTURE -> listOfNotNull(o.departure?.weather?.let(::weatherChip))
    ChapterKind.ARRIVAL -> o.arrival?.let { a ->
        listOfNotNull(
            a.weather?.let(::weatherChip),
            a.sun?.let { OutsideChipData("일몰 ${it.sunset}", "천문연", OutsideChipKind.SUN) },
            a.festival?.let { OutsideChipData("${it.name} ${koOrdinalDay(it.dayIndex)}", "TourAPI", OutsideChipKind.FESTIVAL) },
            a.holiday?.let { OutsideChipData(it, "천문연", OutsideChipKind.HOLIDAY) },
        )
    }.orEmpty()
    ChapterKind.STOP, ChapterKind.RETURN -> emptyList()
}

/** 실제 값이 하나도 없을 때만 2a의 "예시" 칩을 보인다 (하나라도 있으면 예시는 섞지 않는다) */
fun showExampleOutside(o: TripOutside?): Boolean = o == null || ChapterKind.entries.all { outsideChips(o, it).isEmpty() }

/** "18℃ 맑음", 비가 오면 "16℃ 비". 출처는 "기상청 실황"/"기상청 예보". 온도도 하늘도 비도 없으면 null */
fun weatherChip(w: Weather): OutsideChipData? {
    val text = listOfNotNull(w.tempC?.let { "${it.roundToInt()}℃" }, w.precip ?: w.sky).joinToString(" ")
    if (text.isEmpty()) return null
    return OutsideChipData(text, if (w.observed) "기상청 실황" else "기상청 예보", OutsideChipKind.WEATHER)
}

/** 도착 장 기록문 끝에 이어 붙일 문장들 (값이 없는 문장은 빠진다) */
fun outsideSentences(o: OutsideInfo): List<String> = listOfNotNull(
    o.weather?.let(::weatherSentence),
    o.sun?.sunset?.let { s -> Regex("(\\d{1,2}):(\\d{2})").matchEntire(s)?.destructured?.let { (h, m) -> "해는 ${h.toInt()}시 ${m}분에 졌습니다." } },
    o.festival?.let { f ->
        // 축제장이 몇 km 떨어져 있을 수 있다: 가족이 갔다고 쓰지 않는다
        if (f.distanceKm <= NEAR_FESTIVAL_KM) "가까운 곳에서 ${f.name}${subjectJosa(f.name)} ${koOrdinalDay(f.dayIndex).let { it + objectJosa(it) }} 맞고 있었습니다."
        else "그날 이 근처에서는 ${f.name}${subjectJosa(f.name)} 한창이었습니다."
    },
    o.holiday?.let { "이날은 ${it}${copulaPast(it)}." },
)

private const val NEAR_FESTIVAL_KM = 5.0

private fun weatherSentence(w: Weather): String? {
    val temp = w.tempC?.let { "${it.roundToInt()}℃" }
    if (!w.observed) {
        val what = listOfNotNull(w.precip ?: w.sky, temp).joinToString(", ")
        return if (what.isEmpty()) null else "기상청 예보로는 $what${if (temp != null) "였습니다" else copulaPast(what)}."
    }
    w.precip?.let { return "도착할 무렵 ${precipPhrase(it)}." }
    val sky = when (w.sky) { "맑음" -> "하늘은 맑았습니다"; "구름많음" -> "구름이 많았습니다"; "흐림" -> "하늘이 흐렸습니다"; else -> null }
    return when {
        temp != null && sky != null -> "도착할 무렵 기온은 $temp, $sky."
        temp != null -> "도착할 무렵 기온은 ${temp}였습니다."
        sky != null -> "도착할 무렵 $sky."
        else -> null
    }
}

private fun precipPhrase(p: String) = when (p) {
    "비" -> "비가 내리고 있었습니다"
    "눈" -> "눈이 내리고 있었습니다"
    "비/눈" -> "비와 눈이 섞여 내리고 있었습니다"
    "소나기" -> "소나기가 내리고 있었습니다"
    "빗방울" -> "빗방울이 떨어지고 있었습니다"
    "빗방울눈날림" -> "빗방울과 눈발이 날리고 있었습니다"
    "눈날림" -> "눈발이 날리고 있었습니다"
    else -> "${p}${subjectJosa(p)} 내리고 있었습니다"
}

private val OrdinalDays = listOf("첫째", "둘째", "셋째", "넷째", "다섯째", "여섯째", "일곱째", "여덟째", "아홉째", "열째")
/** "첫째 날" … "열째 날", 그 뒤 "11일째" */
fun koOrdinalDay(n: Int): String = OrdinalDays.getOrNull(n - 1)?.let { "$it 날" } ?: "${n}일째"

private fun hasJong(word: String): Boolean? {
    val c = word.lastOrNull() ?: return null
    return if (c in '가'..'힣') (c - '가') % 28 != 0 else null
}
/** 주격 조사: 받침 있으면 "이", 없으면 "가" (한글이 아니면 "가") */
private fun subjectJosa(word: String) = if (hasJong(word) == true) "이" else "가"
/** 목적격 조사: "넷째 날을", "11일째를" */
private fun objectJosa(word: String) = if (hasJong(word) == true) "을" else "를"
/** "한글날이었습니다" / "설날이었습니다" / "어린이날이었습니다", 받침 없으면 "였습니다" */
private fun copulaPast(word: String) = if (hasJong(word) == false) "였습니다" else "이었습니다"

private fun String?.toJsonOrNull(): Any = this?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL
private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }
private fun JSONObject.num(k: String): Double? = if (isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
