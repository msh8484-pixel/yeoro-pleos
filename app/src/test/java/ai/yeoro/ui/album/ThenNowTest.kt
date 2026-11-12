package ai.yeoro.ui.album

import ai.yeoro.cloud.OutsideInfo
import ai.yeoro.cloud.TripOutside
import ai.yeoro.cloud.Weather
import ai.yeoro.data.LocationSampleEntity
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ThenNowTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val gp = 37.8055 to 128.9087   // 강릉 경포해변
    private fun trip(id: Long, start: Long, end: Long?, lat: Double = gp.first, lon: Double = gp.second, state: String = "ALBUM_READY") =
        TripEntity(id = id, startMs = start, endMs = end, destination = "강릉 경포해변", state = state, destLat = lat, destLon = lon)

    private val now = trip(10, ms(2026, 10, 31, 8, 52), null)

    // ── previousVisit ──

    @Test fun `반경 안의 이전 여행을 고른다`() {
        val prev = trip(1, ms(2025, 11, 1, 10, 5), ms(2025, 11, 1, 18, 0), lat = 37.8080, lon = 128.9087)   // 약 280m
        assertEquals(prev, previousVisit(listOf(prev, now), now))
    }

    @Test fun `반경 밖이면 없다`() {
        val far = trip(1, ms(2025, 11, 1, 10, 5), ms(2025, 11, 1, 18, 0), lat = 37.8120, lon = 128.9087)   // 약 720m
        assertNull(previousVisit(listOf(far, now), now))
        // 반경을 넓히면 들어온다
        assertEquals(far, previousVisit(listOf(far, now), now, radiusM = 1000))
    }

    @Test fun `이번 여행 이후에 끝난 여행과 앨범이 없는 여행은 빼고, 가장 늦은 것`() {
        val old = trip(1, ms(2024, 8, 3, 9, 0), ms(2024, 8, 3, 20, 0))
        val last = trip(2, ms(2025, 11, 1, 10, 5), ms(2025, 11, 1, 18, 0))
        val unconfirmed = trip(3, ms(2026, 3, 1, 9, 0), ms(2026, 3, 1, 18, 0), state = "UNCONFIRMED")
        val overlapping = trip(4, ms(2026, 10, 31, 7, 0), ms(2026, 10, 31, 9, 0))   // 이번 출발 뒤에 끝남
        val later = trip(5, ms(2026, 11, 7, 9, 0), ms(2026, 11, 7, 18, 0))
        val noDest = TripEntity(id = 6, startMs = ms(2026, 5, 1, 9, 0), endMs = ms(2026, 5, 1, 18, 0), state = "ALBUM_READY")
        assertEquals(last, previousVisit(listOf(later, overlapping, unconfirmed, old, last, noDest, now), now))
        assertEquals(old, previousVisit(listOf(old, later), now))
        assertNull(previousVisit(listOf(later, overlapping), now))
    }

    @Test fun `이번 목적지를 모르면 없다`() {
        val prev = trip(1, ms(2025, 11, 1, 10, 5), ms(2025, 11, 1, 18, 0))
        assertNull(previousVisit(listOf(prev), now.copy(destLat = null, destLon = null)))
    }

    // ── visitFact ──

    private fun v(
        arrive: Long, rained: Boolean = false, tempC: Double? = null, sky: String? = null, seats: Int? = null,
        precip: String? = null, observed: Boolean? = if (tempC != null || sky != null || precip != null) true else null,
        onWay: Boolean = rained, atArrival: Boolean = false,
    ) = VisitSummary(1, arrive, rained, tempC, sky, seats, null, precip = precip, observed = observed, rainOnWay = onWay, rainAtArrival = atArrival)
    private fun texts(l: List<Comparison>) = l.map { it.text }

    @Test fun `날씨가 있으면 하늘 기온 도착 시각, 강수가 있으면 하늘 대신 강수`() {
        assertEquals("흐림 · 14℃ · 오후 2시 41분 도착", visitFact(v(ms(2025, 11, 1, 14, 41), tempC = 14.2, sky = "흐림"), zone))
        assertEquals("맑음 · 오전 9시 5분 도착", visitFact(v(ms(2025, 11, 1, 9, 5), sky = "맑음"), zone))
        assertEquals("비 · 9℃ · 오전 9시 5분 도착", visitFact(v(ms(2025, 11, 1, 9, 5), sky = "흐림", precip = "비", tempC = 9.0), zone))
    }

    @Test fun `날씨가 없으면 와이퍼로 - 도착할 때 비, 오는 길에 비, 비 온 날, 또는 도착 시각만`() {
        val t = ms(2025, 11, 1, 14, 41)
        assertEquals("비 · 오후 2시 41분 도착", visitFact(v(t, rained = true, atArrival = true), zone))
        assertEquals("오는 길에 비 · 오후 2시 41분 도착", visitFact(v(t, rained = true), zone))
        assertEquals("비 온 날 · 오후 2시 41분 도착", visitFact(v(t, rained = true, onWay = false), zone))   // 도착 뒤(돌아오는 길)에만 비
        assertEquals("오후 1시 20분 도착", visitFact(v(ms(2026, 10, 31, 13, 20)), zone))
        assertEquals("오후 12시 도착", visitFact(v(ms(2026, 10, 31, 12, 0)), zone))
    }

    @Test fun `날씨 출처 - 실황, 예보, 와이퍼, 없음`() {
        val t = ms(2025, 11, 1, 14, 41)
        assertEquals("맑음 · 12℃" to "기상청 실황", visitWeather(v(t, sky = "맑음", tempC = 12.0)))
        assertEquals("맑음 · 12℃" to "기상청 예보", visitWeather(v(t, sky = "맑음", tempC = 12.0, observed = false)))
        assertEquals("오는 길에 비" to "와이퍼", visitWeather(v(t, rained = true)))
        assertNull(visitWeather(v(t)))
        // 기상청 값이 있으면 캡션은 그 값(와이퍼가 켜져 있었어도)
        assertEquals("맑음" to "기상청 실황", visitWeather(v(t, sky = "맑음", rained = true, atArrival = true)))
    }

    // ── comparisons ──

    @Test fun `도착 시각 차이 - 작년과 지난번`() {
        val then = v(ms(2025, 11, 1, 14, 41))
        assertEquals(listOf(Comparison("작년보다 1시간 21분 일찍 도착", "운행 기록", ComparisonKind.TIME)), comparisons(then, v(ms(2026, 10, 31, 13, 20)), zone))
        assertEquals(listOf("지난번보다 40분 늦게 도착"), texts(comparisons(v(ms(2026, 6, 6, 14, 0)), v(ms(2026, 10, 31, 14, 40)), zone)))
        // 10분 미만이면 말하지 않는다
        assertEquals(emptyList<String>(), texts(comparisons(then, v(ms(2026, 10, 31, 14, 50)), zone)))
        assertEquals(listOf("작년보다 10분 늦게 도착"), texts(comparisons(then, v(ms(2026, 10, 31, 14, 51)), zone)))
    }

    private val t0 = ms(2025, 11, 1, 14, 41)
    private val n0 = ms(2026, 10, 31, 14, 41)

    @Test fun `도착할 때 날씨 - 캡션과 같은 순간을 비교한다`() {
        // 그때 비(실황), 이번 맑음(실황)
        assertEquals(listOf(Comparison("그때는 비, 이번엔 맑음", "기상청 실황", ComparisonKind.SKY)),
            comparisons(v(t0, sky = "흐림", precip = "비"), v(n0, sky = "맑음"), zone))
        // 둘 다 같은 강수
        assertEquals(listOf("그때도 이번에도 비"), texts(comparisons(v(t0, precip = "비"), v(n0, precip = "비"), zone)))
        // 둘 다 같은 맑음이면 말하지 않는다
        assertEquals(emptyList<String>(), texts(comparisons(v(t0, sky = "맑음"), v(n0, sky = "맑음"), zone)))
        // 하늘만 달라도 말한다
        assertEquals(listOf("그때는 흐림, 이번엔 맑음"), texts(comparisons(v(t0, sky = "흐림"), v(n0, sky = "맑음"), zone)))
        // 기상청이 없는 쪽은 도착할 때 와이퍼가 켜져 있었을 때만 "비"로 비교한다
        assertEquals(listOf(Comparison("그때는 비, 이번엔 맑음", "기상청 실황·와이퍼", ComparisonKind.SKY)),
            comparisons(v(t0, rained = true, atArrival = true), v(n0, sky = "맑음"), zone))
        assertEquals(listOf(Comparison("그때도 이번에도 비", "와이퍼", ComparisonKind.SKY)),
            comparisons(v(t0, rained = true, atArrival = true), v(n0, rained = true, atArrival = true), zone))
        // 예보는 비교하지 않는다
        assertEquals(emptyList<String>(), texts(comparisons(v(t0, precip = "비"), v(n0, sky = "맑음", observed = false), zone)))
        // 한쪽을 모르면 비교하지 않는다
        assertEquals(emptyList<String>(), texts(comparisons(v(t0), v(n0, sky = "맑음"), zone)))
    }

    @Test fun `오는 길에 그친 비는 따로, 도착 날씨와 어긋나지 않게`() {
        // 그때 오는 길에 비(도착할 땐 그침, 기상청 없음), 이번 오는 길에 비가 그치고 도착은 맑음(실황): 도착 날씨는 비교 못 함
        assertEquals(listOf(Comparison("두 번 모두 오는 길에 비가 그쳤어요", "와이퍼", ComparisonKind.RAIN_ON_WAY)),
            comparisons(v(t0, rained = true), v(n0, rained = true, sky = "맑음", tempC = 12.0), zone))
        assertEquals(listOf("이번엔 오는 길에 비가 그쳤어요"), texts(comparisons(v(t0), v(n0, rained = true, sky = "맑음"), zone)))
        assertEquals(listOf("그때는 오는 길에 비가 그쳤어요"), texts(comparisons(v(t0, rained = true), v(n0), zone)))
        // 도착할 때도 비면 "그쳤다"고 하지 않는다 (와이퍼가 켜져 있거나, 기상청이 강수라고 할 때)
        assertEquals(emptyList<String>(), texts(comparisons(v(t0), v(n0, rained = true, atArrival = true), zone)))
        assertEquals(listOf("그때는 맑음, 이번엔 비"), texts(comparisons(v(t0, sky = "맑음"), v(n0, rained = true, precip = "비"), zone)))
        // 도착 뒤에만 온 비는 오는 길의 비가 아니다
        assertEquals(emptyList<String>(), texts(comparisons(v(t0, rained = true, onWay = false), v(n0), zone)))
        // 예전 "두 번 모두 비가 왔어요"는 더 이상 만들지 않는다 (지금 쪽이 맑음일 때 모순)
        assertTrue(texts(comparisons(v(t0, rained = true), v(n0, rained = true, sky = "맑음"), zone)).none { it == "두 번 모두 비가 왔어요" })
    }

    @Test fun `기온 차이 2도 이상, 두 쪽 모두 실황일 때만`() {
        assertEquals(listOf(Comparison("4℃ 더 따뜻했어요", "기상청 실황", ComparisonKind.TEMP)), comparisons(v(t0, tempC = 14.0), v(n0, tempC = 18.2), zone))
        assertEquals(listOf("3℃ 더 쌀쌀했어요"), texts(comparisons(v(t0, tempC = 14.0), v(n0, tempC = 11.0), zone)))
        assertEquals(emptyList<String>(), texts(comparisons(v(t0, tempC = 14.0), v(n0, tempC = 15.9), zone)))
        assertEquals(emptyList<String>(), texts(comparisons(v(t0), v(n0, tempC = 18.0), zone)))
        // 한쪽이라도 예보면 비교하지 않는다
        assertEquals(emptyList<String>(), texts(comparisons(v(t0, tempC = 14.0, observed = false), v(n0, tempC = 18.2), zone)))
        assertEquals(emptyList<String>(), texts(comparisons(v(t0, tempC = 14.0), v(n0, tempC = 18.2, observed = false), zone)))
    }

    @Test fun `인원 차이`() {
        assertEquals(listOf(Comparison("이번엔 한 명 더 함께", "좌석", ComparisonKind.SEATS)), comparisons(v(t0, seats = 3), v(n0, seats = 4), zone))
        assertEquals(listOf("이번엔 두 명 더 함께"), texts(comparisons(v(t0, seats = 2), v(n0, seats = 4), zone)))
        assertEquals(listOf("이번엔 한 명 적게"), texts(comparisons(v(t0, seats = 4), v(n0, seats = 3), zone)))
        assertEquals(emptyList<String>(), texts(comparisons(v(t0, seats = 4), v(n0, seats = 4), zone)))
        assertEquals(emptyList<String>(), texts(comparisons(v(t0), v(n0, seats = 4), zone)))
    }

    @Test fun `최대 세 개, 정해진 순서`() {
        val then = v(ms(2025, 11, 1, 14, 41), sky = "흐림", precip = "비", tempC = 14.0, seats = 3)
        val now = v(ms(2026, 10, 31, 13, 20), rained = true, sky = "맑음", tempC = 18.0, seats = 4)
        assertEquals(listOf("작년보다 1시간 21분 일찍 도착", "그때는 비, 이번엔 맑음", "이번엔 오는 길에 비가 그쳤어요"), texts(comparisons(then, now, zone)))
        // 앞의 것이 빠지면 기온·인원이 들어온다
        assertEquals(listOf("그때는 비, 이번엔 맑음", "4℃ 더 따뜻했어요", "이번엔 한 명 더 함께"),
            texts(comparisons(then, now.copy(arriveMs = ms(2026, 10, 31, 14, 45), rainOnWay = false), zone)))
    }

    // ── heading ──

    @Test fun `일 년이면 같은 곳 일 년의 차이, 아니면 지난번과 오늘`() {
        assertEquals("같은 곳, 일 년의 차이", thenNowHeading(ms(2025, 11, 1, 14, 41), ms(2026, 10, 31, 13, 20), zone))   // 364일
        assertEquals("같은 곳, 일 년의 차이", thenNowHeading(ms(2025, 12, 5, 14, 0), ms(2026, 10, 31, 13, 20), zone))    // 330일
        assertEquals("지난번과 오늘", thenNowHeading(ms(2025, 12, 6, 14, 0), ms(2026, 10, 31, 13, 20), zone))            // 329일
        assertEquals("지난번과 오늘", thenNowHeading(ms(2025, 9, 25, 14, 0), ms(2026, 10, 31, 13, 20), zone))            // 401일
        assertEquals("지난번과 오늘", thenNowHeading(ms(2026, 6, 6, 14, 0), ms(2026, 10, 31, 13, 20), zone))
    }

    @Test fun `그때 쪽 날짜와 사실, 구조화된 도착 값`() {
        val s = VisitSummary(7, ms(2025, 11, 1, 14, 41), true, null, null, 3, "/p/seed-prev-1.jpg", rainOnWay = true)
        assertEquals(VisitSide(7, "2025.11.01", "오는 길에 비 · 오후 2시 41분 도착", "/p/seed-prev-1.jpg",
            weather = "오는 길에 비", weatherSource = "와이퍼", arriveMs = ms(2025, 11, 1, 14, 41), arriveText = "오후 2시 41분 도착", hour = 14, rained = true),
            visitSide(s, zone))
        val n = visitSide(VisitSummary(8, ms(2026, 10, 31, 13, 22), false, 12.4, "맑음", 4, null, observed = true), zone)
        assertEquals("맑음 · 12℃", n.weather); assertEquals("기상청 실황", n.weatherSource); assertEquals(13, n.hour)
        assertEquals("맑음 · 12℃ · 오후 1시 22분 도착", n.fact)
    }

    // ── summaryOf / visitSummary ──

    private fun mo(at: Long, type: String, detail: String = "") = MomentEntity(tripId = 1, timeMs = at, type = type, detail = detail)
    private fun outside(w: Weather?) = TripOutside(OutsideInfo(n0, w, null, null, null, null), null)
    private fun ch(kind: ChapterKind, at: Long) = Chapter(kind, kind.name, at, null, null, null, emptyList())

    @Test fun `summaryOf - 도착 장 시작, 와이퍼 오는 길과 도착할 때, 실황 예보, 표지`() {
        val dep = ms(2026, 10, 31, 8, 52); val arr = ms(2026, 10, 31, 13, 22)
        val chapters = listOf(ch(ChapterKind.DEPARTURE, dep), ch(ChapterKind.STOP, ms(2026, 10, 31, 10, 0)), ch(ChapterKind.ARRIVAL, arr))
        val rainStopped = listOf(mo(dep, "COMPANION_JOINED", "2→4"), mo(ms(2026, 10, 31, 10, 58), "RAIN_START"), mo(ms(2026, 10, 31, 12, 40), "RAIN_STOP"))
        val cover = AlbumPhoto("p", arr, 1600, 1200, "/t.jpg", "/f.jpg")
        val s = summaryOf(9, chapters, rainStopped, outside(Weather(12.4, "맑음", null, observed = true)), cover, 0L)
        assertEquals(VisitSummary(9, arr, rained = true, tempC = 12.4, sky = "맑음", seats = 4, photoFile = "/f.jpg",
            precip = null, observed = true, rainOnWay = true, rainAtArrival = false), s)

        // 도착할 때도 와이퍼가 켜져 있음, 예보, 표지는 원본이 없으면 썸네일
        val stillRaining = listOf(mo(ms(2026, 10, 31, 12, 50), "RAIN_START"))
        val f = summaryOf(9, chapters, stillRaining, outside(Weather(9.0, "흐림", "비", observed = false)), cover.copy(fullFile = null), 0L)
        assertTrue(f.rainOnWay); assertTrue(f.rainAtArrival); assertEquals(false, f.observed); assertEquals("비", f.precip); assertEquals("/t.jpg", f.photoFile)

        // 도착 뒤(돌아오는 길)에만 비: 비 온 날이지만 오는 길의 비는 아님. 날씨 없음 → observed null
        val after = listOf(mo(ms(2026, 10, 31, 16, 0), "RAIN_START"), mo(ms(2026, 10, 31, 16, 30), "RAIN_STOP"))
        val a = summaryOf(9, chapters, after, null, null, 0L)
        assertTrue(a.rained); assertFalse(a.rainOnWay); assertFalse(a.rainAtArrival); assertNull(a.observed); assertNull(a.photoFile)

        // 값이 하나도 없는 날씨는 날씨 없음으로
        assertNull(summaryOf(9, chapters, emptyList(), outside(Weather(null, null, null, observed = true)), null, 0L).observed)
        // 도착 장이 없으면 fallback
        assertEquals(77L, summaryOf(9, chapters.take(1), emptyList(), null, null, 77L).arriveMs)
    }

    @Test fun `visitSummary - 저장된 여행에서 장과 사진을 앨범과 같은 규칙으로`() {
        fun t(h: Int, m: Int) = ms(2025, 11, 1, h, m)
        val trip = TripEntity(id = 3, startMs = t(10, 5), endMs = t(14, 41), destination = "강릉 경포해변", state = "ALBUM_READY", destLat = gp.first, destLon = gp.second)
        val samples = listOf(LocationSampleEntity(tripId = 3, timeMs = t(10, 5), lat = 37.5563, lon = 126.9220),
            LocationSampleEntity(tripId = 3, timeMs = t(11, 40), lat = 37.49, lon = 127.98),
            LocationSampleEntity(tripId = 3, timeMs = t(14, 41), lat = gp.first, lon = gp.second))
        val moments = listOf(mo(t(10, 5), "COMPANION_JOINED", "1→3"), mo(t(11, 50), "RAIN_START"), mo(t(13, 10), "RAIN_STOP"))
        val photo = AlbumPhoto("seed-prev-1", t(14, 50), 1600, 1200, "/t.jpg", "/f.jpg")
        val s = visitSummary(trip, samples, moments, null, listOf(photo), home = 37.5563 to 126.9220)
        assertEquals(t(14, 41), s.arriveMs)
        assertEquals("/f.jpg", s.photoFile)
        assertEquals(3, s.seats)
        assertTrue(s.rainOnWay); assertFalse(s.rainAtArrival)
        assertEquals("오는 길에 비 · 오후 2시 41분 도착", visitFact(s, zone))
    }
}
