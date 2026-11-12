package ai.yeoro.cloud

import ai.yeoro.engine.PlaceKind
import ai.yeoro.ui.album.ChapterKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutsideTest {
    /** Task 3 실제 응답 (경포 2026-10-24 13:20) */
    private val oct24 = """
        {"at":"2026-10-24T13:20:00+09:00","weather":null,
         "sun":{"sunrise":"06:41","sunset":"17:36","source":"천문연"},
         "place":{"kind":"TOURIST","name":"경포호수광장","contentId":"3454461","distanceM":859,"homepage":null,"source":"TourAPI"},
         "festival":{"name":"강릉커피축제","start":"2026-10-21","end":"2026-10-25","dayIndex":4,"distanceKm":5,
                     "contentId":"825295","homepage":"https://www.coffeefestival.net/","source":"TourAPI"},
         "holiday":null,"errors":["weather:out_of_window"]}
    """.trimIndent()
    /** Task 3 실제 응답 (경포 지금) */
    private val now = """
        {"at":"2026-10-28T13:26:35+09:00",
         "weather":{"tempC":11.1,"sky":"맑음","precip":null,"kind":"observed","source":"기상청"},
         "sun":{"sunrise":"06:45","sunset":"17:31","source":"천문연"},
         "place":{"kind":"TOURIST","name":"경포호수광장","contentId":"3454461","distanceM":859,"homepage":null,"source":"TourAPI"},
         "festival":null,"holiday":null,"errors":[]}
    """.trimIndent()

    private fun w(temp: Double?, sky: String?, precip: String?, observed: Boolean) = Weather(temp, sky, precip, observed)
    private fun info(weather: Weather? = null, sun: SunTimes? = null, festival: Festival? = null, holiday: String? = null) =
        OutsideInfo(0L, weather, sun, null, festival, holiday)
    private val coffee = Festival("강릉커피축제", LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 25), 4, 5.0, null)

    @Test fun `Task 3 응답 파싱 - 축제·일몰·장소, 날씨 null`() {
        val o = parseOutside(oct24)!!
        assertEquals(OffsetDateTime.parse("2026-10-24T13:20:00+09:00").toInstant().toEpochMilli(), o.atMs)
        assertNull(o.weather)
        assertEquals(SunTimes("06:41", "17:36"), o.sun)
        assertEquals(PlaceKind.TOURIST, o.place!!.kind)
        assertEquals("경포호수광장", o.place!!.name)
        assertEquals(859, o.place!!.distanceM)
        assertNull(o.place!!.homepage)
        assertEquals(Festival("강릉커피축제", LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 25), 4, 5.0, "https://www.coffeefestival.net/"), o.festival)
        assertNull(o.holiday)
    }

    @Test fun `실황 날씨 파싱, 휴일 이름`() {
        val o = parseOutside(now)!!
        assertEquals(w(11.1, "맑음", null, true), o.weather)
        assertNull(o.festival)
        assertEquals("한글날", parseOutside("""{"at":"2026-10-09T12:00:00+09:00","holiday":{"name":"한글날"}}""")!!.holiday)
    }

    @Test fun `null 칸·모르는 필드·모르는 장소 종류는 견디고, 깨진 JSON은 null`() {
        val o = parseOutside("""{"at":"2026-10-24T13:20:00+09:00","weather":{"tempC":null,"sky":null,"precip":"비","kind":"forecast","source":"기상청"},"new":{"x":1},"place":{"kind":"SPACE","name":null}}""")!!
        assertEquals(w(null, null, "비", false), o.weather)
        assertNull(o.sun); assertNull(o.festival); assertNull(o.holiday)
        assertEquals(PlaceInfo(PlaceKind.OTHER, null, null), o.place)
        assertNull(parseOutside("{not json"))
        assertNull(parseOutside("""{"weather":null}"""))   // at 없음
    }

    @Test fun `여행 바깥 JSON 왕복`() {
        val j = tripOutsideJson(now, null)
        val t = parseTripOutside(j)
        assertEquals(parseOutside(now), t.arrival)
        assertNull(t.departure)
        assertEquals(TripOutside(null, null), parseTripOutside("{}"))
        assertEquals(TripOutside(null, null), parseTripOutside("broken"))
        assertEquals(now.let { parseOutside(it) }, parseOutside(tripOutsideParts(j).first!!))
        assertNull(tripOutsideParts(j).second)
    }

    @Test fun `날씨 칩 - 실황·예보 출처, 비`() {
        assertEquals(OutsideChipData("18℃ 맑음", "기상청 실황", OutsideChipKind.WEATHER), weatherChip(w(18.2, "맑음", null, true)))
        assertEquals(OutsideChipData("16℃ 비", "기상청 예보", OutsideChipKind.WEATHER), weatherChip(w(15.6, "흐림", "비", false)))
        assertEquals(OutsideChipData("맑음", "기상청 예보", OutsideChipKind.WEATHER), weatherChip(w(null, "맑음", null, false)))
        assertNull(weatherChip(w(null, null, null, true)))
    }

    @Test fun `장 종류별 칩 - 출발은 departure 날씨만, 도착은 arrival의 날씨·일몰·축제·휴일, 정차·돌아오는 길은 없음`() {
        val o = TripOutside(
            arrival = info(w(18.0, "맑음", null, true), SunTimes("06:41", "17:36"), coffee, "한글날").copy(place = PlaceInfo(PlaceKind.TOURIST, "경포호수광장", null)),
            departure = info(w(11.0, "구름많음", null, false), SunTimes("06:40", "17:40"), coffee, "한글날"),
        )
        assertEquals(listOf(OutsideChipData("11℃ 구름많음", "기상청 예보", OutsideChipKind.WEATHER)), outsideChips(o, ChapterKind.DEPARTURE))
        assertEquals(listOf(
            OutsideChipData("18℃ 맑음", "기상청 실황", OutsideChipKind.WEATHER),
            OutsideChipData("일몰 17:36", "천문연", OutsideChipKind.SUN),
            OutsideChipData("강릉커피축제 넷째 날", "TourAPI", OutsideChipKind.FESTIVAL),
            OutsideChipData("한글날", "천문연", OutsideChipKind.HOLIDAY),
        ), outsideChips(o, ChapterKind.ARRIVAL))
        assertEquals(emptyList<OutsideChipData>(), outsideChips(o, ChapterKind.STOP))
        assertEquals(emptyList<OutsideChipData>(), outsideChips(o, ChapterKind.RETURN))
    }

    @Test fun `예시 판단 - trip_outside 없음이면 예시, 일몰만 있어도 실제 칩만`() {
        assertTrue(showExampleOutside(null))
        assertTrue(showExampleOutside(TripOutside(null, null)))
        assertTrue(showExampleOutside(TripOutside(info(), info())))   // 받았지만 값이 하나도 없음
        val sunOnly = TripOutside(info(sun = SunTimes("06:41", "17:36")), null)
        assertFalse(showExampleOutside(sunOnly))
        assertEquals(listOf(OutsideChipData("일몰 17:36", "천문연", OutsideChipKind.SUN)), outsideChips(sunOnly, ChapterKind.ARRIVAL))
        assertEquals(emptyList<OutsideChipData>(), outsideChips(sunOnly, ChapterKind.DEPARTURE))
        // 출발 날씨만 있어도 실제
        assertFalse(showExampleOutside(TripOutside(null, info(w(11.0, "맑음", null, true)))))
    }

    @Test fun `기록문 문장 - 실황, 비, 예보, 일몰, 축제, 휴일`() {
        assertEquals(listOf("도착할 무렵 기온은 18℃, 하늘은 맑았습니다."), outsideSentences(info(w(18.3, "맑음", null, true))))
        assertEquals(listOf("도착할 무렵 비가 내리고 있었습니다."), outsideSentences(info(w(16.0, "흐림", "비", true))))
        assertEquals(listOf("기상청 예보로는 맑음, 18℃였습니다."), outsideSentences(info(w(18.0, "맑음", null, false))))
        assertEquals(listOf("해는 17시 36분에 졌습니다."), outsideSentences(info(sun = SunTimes("06:41", "17:36"))))
        assertEquals(listOf("가까운 곳에서 강릉커피축제가 넷째 날을 맞고 있었습니다."), outsideSentences(info(festival = coffee)))
        assertEquals(listOf("이날은 한글날이었습니다."), outsideSentences(info(holiday = "한글날")))
        assertEquals(emptyList<String>(), outsideSentences(info()))
        assertEquals(listOf("도착할 무렵 기온은 18℃, 하늘은 맑았습니다.", "해는 17시 36분에 졌습니다.",
            "가까운 곳에서 강릉커피축제가 넷째 날을 맞고 있었습니다.", "이날은 한글날이었습니다."),
            outsideSentences(info(w(18.0, "맑음", null, true), SunTimes("06:41", "17:36"), coffee, "한글날")))
    }

    @Test fun `먼 축제는 가족이 간 것처럼 쓰지 않는다`() {
        assertEquals(listOf("그날 이 근처에서는 강릉커피축제가 한창이었습니다."), outsideSentences(info(festival = coffee.copy(distanceKm = 8.4))))
    }

    @Test fun `기록문 - 기온만, 하늘만, 구름많음·흐림`() {
        assertEquals(listOf("도착할 무렵 기온은 18℃였습니다."), outsideSentences(info(w(18.0, null, null, true))))
        assertEquals(listOf("도착할 무렵 기온은 18℃, 구름이 많았습니다."), outsideSentences(info(w(18.0, "구름많음", null, true))))
        assertEquals(listOf("도착할 무렵 기온은 18℃, 하늘이 흐렸습니다."), outsideSentences(info(w(18.0, "흐림", null, true))))
        assertEquals(listOf("도착할 무렵 눈이 내리고 있었습니다."), outsideSentences(info(w(-1.0, "흐림", "눈", true))))
    }

    @Test fun `koOrdinalDay`() {
        assertEquals("첫째 날", koOrdinalDay(1)); assertEquals("둘째 날", koOrdinalDay(2)); assertEquals("셋째 날", koOrdinalDay(3))
        assertEquals("넷째 날", koOrdinalDay(4)); assertEquals("다섯째 날", koOrdinalDay(5)); assertEquals("열째 날", koOrdinalDay(10))
        assertEquals("11일째", koOrdinalDay(11)); assertEquals("30일째", koOrdinalDay(30))
    }

    @Test fun `날씨만 따로 물은 응답을 합친다`() {
        val merged = mergeOutsideWeather(oct24, now)!!
        val o = parseOutside(merged)!!
        assertEquals(w(11.1, "맑음", null, true), o.weather)
        assertEquals("강릉커피축제", o.festival!!.name)
        assertEquals(parseOutside(oct24)!!.atMs, o.atMs)
        assertEquals(parseOutside(now), parseOutside(mergeOutsideWeather(null, now)!!))
        assertEquals(parseOutside(oct24), parseOutside(mergeOutsideWeather(oct24, null)!!))
        assertNull(mergeOutsideWeather(null, null))
    }
}
