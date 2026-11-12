package ai.yeoro.ui.album

import ai.yeoro.data.companionDetail
import ai.yeoro.data.LocationSampleEntity
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.engine.Seat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChaptersTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun t(h: Int, m: Int) = LocalDateTime.of(2026, 10, 24, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun mo(h: Int, m: Int, type: String, detail: String = "") = MomentEntity(tripId = 1, timeMs = t(h, m), type = type, detail = detail)
    private fun s(h: Int, m: Int, lat: Double, lon: Double) = LocationSampleEntity(tripId = 1, timeMs = t(h, m), lat = lat, lon = lon)

    private val home = 37.5563 to 126.9220
    private val samples = listOf(
        s(8, 52, 37.5563, 126.9220), s(9, 53, 37.49, 127.98), s(11, 42, 37.68, 128.72), s(13, 20, 37.8055, 128.8960),
    )
    private val trip = TripEntity(id = 1, startMs = t(8, 52), endMs = t(13, 20), destination = "강릉 경포해변", state = "ALBUM_READY", destLat = 37.8055, destLon = 128.8960)
    private val moments = listOf(
        mo(8, 52, "COMPANION_JOINED", "2→4"),
        mo(9, 53, "STOP", "38"),
        mo(10, 58, "RAIN_START"),
        mo(11, 42, "STOP", "14"),
        mo(12, 40, "RAIN_STOP"),
        mo(13, 2, "WINDOWS_OPEN", "4"),
    )

    @Test fun `출발, 정차마다 한 장, 도착으로 나눈다`() {
        val ch = buildChapters(trip, samples, moments)
        assertEquals(listOf(ChapterKind.DEPARTURE, ChapterKind.STOP, ChapterKind.STOP, ChapterKind.ARRIVAL), ch.map { it.kind })
        assertEquals(listOf(t(8, 52), t(9, 53), t(11, 42), t(13, 20)), ch.map { it.startMs })
    }

    @Test fun `이름 - 목적지명, 이름 없는 정차는 N번째 쉼, 집 근처 출발은 집 앞 출발`() {
        assertEquals(listOf("집 앞 출발", "첫 번째 쉼", "두 번째 쉼", "강릉 경포해변"), buildChapters(trip, samples, moments, home).map { it.name })
        assertEquals("출발", buildChapters(trip, samples, moments, home = null).first().name)
        assertEquals("도착", buildChapters(trip.copy(destination = null), samples, moments).last().name)
    }

    @Test fun `머문 시간 - 정차는 분, 도착은 여행 끝까지, 출발은 없음`() {
        val ch = buildChapters(trip.copy(endMs = t(14, 5)), samples, moments)
        assertNull(ch[0].stayMin)
        assertEquals(38, ch[1].stayMin); assertEquals(14, ch[2].stayMin)
        assertEquals(45, ch[3].stayMin)
        assertNull(buildChapters(trip, samples, moments).last().stayMin)
    }

    @Test fun `순간은 시간상 가장 가까운 장에 들어간다`() {
        val ch = buildChapters(trip, samples, moments)
        assertEquals(listOf("COMPANION_JOINED"), ch[0].moments.map { it.type })
        assertEquals(listOf("STOP", "RAIN_START"), ch[1].moments.map { it.type })   // 10:31 출발 뒤 27분 vs 다음 정차까지 44분
        assertEquals(listOf("STOP"), ch[2].moments.map { it.type })
        assertEquals(listOf("RAIN_STOP", "WINDOWS_OPEN"), ch[3].moments.map { it.type })
    }

    @Test fun `도착 뒤의 정차는 도착 장에 합친다`() {
        val late = moments + mo(13, 25, "STOP", "70")
        val ch = buildChapters(trip.copy(endMs = t(14, 40)), samples, late)
        assertEquals(4, ch.size)
        assertEquals(ChapterKind.ARRIVAL, ch.last().kind); assertEquals(70, ch.last().stayMin)
    }

    @Test fun `샘플이 없어도 여행 시각으로 장을 만든다`() {
        val ch = buildChapters(trip, emptyList(), emptyList())
        assertEquals(listOf(t(8, 52), t(13, 20)), ch.map { it.startMs })
    }

    @Test fun `제목 규칙`() {
        assertEquals("비가 그친 오후, 강릉 경포해변으로", albumTitle("강릉 경포해변", moments, 251.4, zone))
        assertEquals("비가 그친 아침, 속초로", albumTitle("속초", listOf(mo(8, 0, "RAIN_START"), mo(9, 30, "RAIN_STOP")), 200.0, zone))
        assertEquals("창문을 연 날, 강릉 경포해변", albumTitle("강릉 경포해변", listOf(mo(13, 2, "WINDOWS_OPEN", "4")), 251.0, zone))
        // 비가 시작만 하고 그치지 않았으면 비 제목이 아니다
        assertEquals("창문을 연 날, 강릉 경포해변", albumTitle("강릉 경포해변", listOf(mo(10, 0, "RAIN_START"), mo(13, 2, "WINDOWS_OPEN", "4")), 251.0, zone))
        assertEquals("강릉 경포해변까지 251km", albumTitle("강릉 경포해변", listOf(mo(10, 0, "STOP", "20")), 251.4, zone))
        assertEquals("251km를 달린 날", albumTitle(null, emptyList(), 251.4, zone))
    }

    @Test fun `기록문 문장 형식`() {
        assertEquals("08시 52분, 네 자리에 가족이 앉았습니다.", momentSentence(mo(8, 52, "COMPANION_JOINED", "2→4"), zone))
        assertEquals("09시 10분, 탄 사람이 1명에서 2명으로 늘었습니다.", momentSentence(mo(9, 10, "COMPANION_JOINED", "1→2"), zone))
        assertEquals("10시 58분부터 와이퍼가 움직였습니다.", momentSentence(mo(10, 58, "RAIN_START"), zone))
        assertEquals("12시 40분, 비가 그쳤습니다.", momentSentence(mo(12, 40, "RAIN_STOP"), zone))
        assertEquals("13시 02분, 창문 네 개가 내려갔습니다.", momentSentence(mo(13, 2, "WINDOWS_OPEN", "4"), zone))
        assertEquals("09시 53분부터 38분 동안 쉬었습니다.", momentSentence(mo(9, 53, "STOP", "38"), zone))
        assertEquals("14시 10분부터 1시간 12분 동안 쉬었습니다.", momentSentence(mo(14, 10, "STOP", "72"), zone))
        assertNull(momentSentence(mo(9, 0, "UNKNOWN"), zone))
    }

    @Test fun `장 기록문은 사실 문장을 시간순으로 잇는다`() {
        val ch = buildChapters(trip, samples, moments)
        assertEquals("08시 52분, 네 자리에 가족이 앉았습니다. 목적지는 강릉 경포해변.", chapterDiary(ch[0], "강릉 경포해변", zone))
        assertEquals("09시 53분부터 38분 동안 쉬었습니다. 10시 58분부터 와이퍼가 움직였습니다.", chapterDiary(ch[1], "강릉 경포해변", zone))
        assertEquals("12시 40분, 비가 그쳤습니다. 13시 02분, 창문 네 개가 내려갔습니다. 13시 20분, 강릉 경포해변에 도착했습니다.",
            chapterDiary(ch[3], "강릉 경포해변", zone))
        val quiet = buildChapters(trip.copy(destination = null), samples, emptyList())
        assertEquals("08시 52분에 출발했습니다.", chapterDiary(quiet[0], null, zone))
        assertEquals("13시 20분에 도착했습니다.", chapterDiary(quiet.last(), null, zone))
    }

    @Test fun `거리, 소요 시간, 한국어 수`() {
        assertTrue(routeKm(samples) in 150.0..260.0)
        assertEquals(0.0, routeKm(emptyList()), 0.0)
        assertEquals("4:28", durationText(t(13, 20) - t(8, 52)))
        assertEquals("38분", stayText(38)); assertEquals("1시간", stayText(60)); assertEquals("1시간 12분", stayText(72))
        assertEquals(listOf("한", "두", "세", "네", "다섯", "11"), listOf(1, 2, 3, 4, 5, 11).map(::koCount))
        assertEquals(listOf("첫 번째", "두 번째", "세 번째", "12번째"), listOf(1, 2, 3, 12).map(::koOrdinal))
        assertEquals(listOf("강릉으로", "속초로", "서울로", "Beach로"), listOf("강릉", "속초", "서울", "Beach").map(::withRo))
    }

    @Test fun `탑승 인원은 좌석 순간의 최댓값`() {
        assertEquals(4, seatCount(moments)); assertNull(seatCount(emptyList()))
    }

    @Test fun `투영 - 여백 8퍼센트 안에 들어가고 가로세로 비율을 지킨다`() {
        val pts = projectRoute(samples.map { it.lat to it.lon }, 1000f, 800f)
        val xs = pts.filterIndexed { i, _ -> i % 2 == 0 }; val ys = pts.filterIndexed { i, _ -> i % 2 == 1 }
        assertTrue(xs.all { it in 79.5f..920.5f }); assertTrue(ys.all { it in 63.5f..736.5f })
        assertEquals(80f, xs.min(), 0.5f); assertEquals(920f, xs.max(), 0.5f)   // 동서로 긴 경로는 폭을 채운다
        assertTrue(ys.first() > ys.last())                                     // 북쪽(위도 큼)이 위
    }

    @Test fun `순간 칩은 짧은 글과 출처`() {
        assertEquals(MomentChip("정차 38분", "도어·P단", ai.yeoro.ui.live.MomentIcon.STOP), momentChip(mo(9, 53, "STOP", "38"), zone))
        assertEquals("10:58 비 시작", momentChip(mo(10, 58, "RAIN_START"), zone)?.text)
        assertEquals("12:40 비 그침", momentChip(mo(12, 40, "RAIN_STOP"), zone)?.text)
        assertEquals("창문 4개 열림", momentChip(mo(13, 2, "WINDOWS_OPEN", "4"), zone)?.text)
        assertEquals("4석 착석", momentChip(mo(8, 52, "COMPANION_JOINED", "2→4"), zone)?.text)
        assertNull(momentChip(mo(9, 0, "UNKNOWN"), zone))
    }

    // 가족 이름: 좌석 순간에 그때 앉은 좌석을 함께 남기고("2→4|DRIVER,…"), 기록문은 이름(없으면 좌석 이름)을 괄호로 붙인다
    private val four = setOf(Seat.REAR_RIGHT, Seat.DRIVER, Seat.REAR_LEFT, Seat.PASSENGER)
    private val names = mapOf(Seat.DRIVER to "아빠", Seat.PASSENGER to "엄마", Seat.REAR_LEFT to "민지")

    @Test fun `좌석 순간 detail에 좌석을 좌석 순서로 붙인다`() {
        assertEquals("2→4|DRIVER,PASSENGER,REAR_LEFT,REAR_RIGHT", companionDetail("2→4", four))
        assertEquals("2→4", companionDetail("2→4", emptySet()))
    }

    @Test fun `좌석이 있는 좌석 순간 - 가족 이름, 없으면 좌석 이름`() {
        val m = mo(8, 52, "COMPANION_JOINED", companionDetail("2→4", four))
        assertEquals("08시 52분, 네 자리에 가족이 앉았습니다(아빠·엄마·민지·뒷좌석 오른쪽).", momentSentence(m, zone, names))
        assertEquals("08시 52분, 네 자리에 가족이 앉았습니다(운전석·조수석·뒷좌석 왼쪽·뒷좌석 오른쪽).", momentSentence(m, zone))
        val three = mo(9, 10, "COMPANION_JOINED", companionDetail("1→3", setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT)))
        assertEquals("09시 10분, 탄 사람이 1명에서 3명으로 늘었습니다(아빠·엄마·민지).", momentSentence(three, zone, names))
        // 좌석 정보가 없는 예전 기록은 그대로
        assertEquals("08시 52분, 네 자리에 가족이 앉았습니다.", momentSentence(mo(8, 52, "COMPANION_JOINED", "2→4"), zone, names))
    }

    @Test fun `좌석이 붙은 detail도 인원과 칩을 읽는다`() {
        val m = mo(8, 52, "COMPANION_JOINED", companionDetail("2→4", four))
        assertEquals(4, seatCount(listOf(m)))
        assertEquals("4석 착석", momentChip(m, zone)?.text)
        val ch = buildChapters(trip, samples, listOf(m))
        assertEquals("08시 52분, 네 자리에 가족이 앉았습니다(아빠·엄마·민지·뒷좌석 오른쪽). 목적지는 강릉 경포해변.", chapterDiary(ch[0], "강릉 경포해변", zone, names))
    }

    // 왕복: 13:20 경포 도착, 15:30 경포에서 40분 정차, 17:05 떠남, 18:12 귀갓길 휴게소 15분, 19:00 비, 19:40 집
    private val roundSamples = samples + listOf(
        s(15, 0, 37.8050, 128.8970), s(17, 5, 37.8058, 128.8950), s(18, 12, 37.49, 127.98), s(19, 40, 37.5565, 126.9225),
    )
    private val roundTrip = trip.copy(endMs = t(19, 40))
    private val roundMoments = moments + listOf(mo(15, 30, "STOP", "40"), mo(18, 12, "STOP", "15"), mo(19, 0, "RAIN_START"))

    @Test fun `왕복이면 돌아오는 길 한 장, 귀갓길 정차는 그 장의 순간`() {
        val ch = buildChapters(roundTrip, roundSamples, roundMoments, home)
        assertEquals(listOf(ChapterKind.DEPARTURE, ChapterKind.STOP, ChapterKind.STOP, ChapterKind.ARRIVAL, ChapterKind.RETURN), ch.map { it.kind })
        val ret = ch.last()
        assertEquals("돌아오는 길", ret.name)
        assertEquals(t(17, 5), ret.startMs)
        assertEquals(routeMidpoint(roundSamples.drop(5).map { it.lat to it.lon }), ret.lat to ret.lon)   // 핀은 귀갓길 가운데(집 핀과 겹치지 않게)
        assertEquals(listOf(t(18, 12), t(19, 0)), ret.moments.map { it.timeMs })
        // 도착~떠남 사이 정차는 도착 장에, 머문 시간은 떠남 − 도착
        val arrival = ch[3]
        assertEquals(t(13, 20), arrival.startMs)
        assertEquals(225, arrival.stayMin)
        assertEquals(listOf("RAIN_STOP", "WINDOWS_OPEN", "STOP"), arrival.moments.map { it.type })
    }

    @Test fun `돌아오는 길 기록문 - 집으로 출발, 순간, 집에 도착`() {
        val ret = buildChapters(roundTrip, roundSamples, roundMoments, home).last()
        assertEquals("17시 05분, 집으로 출발했습니다. 18시 12분부터 15분 동안 쉬었습니다. 19시 00분부터 와이퍼가 움직였습니다. 19시 40분, 집에 도착했습니다.",
            chapterDiary(ret, "강릉 경포해변", zone))
    }

    @Test fun `집을 모르면 왕복으로 나누지 않는다`() {
        val ch = buildChapters(roundTrip, roundSamples, roundMoments, home = null)
        assertEquals(ChapterKind.ARRIVAL, ch.last().kind)
        assertEquals(t(13, 20), ch.last().startMs)
        assertEquals(40, ch.last().stayMin)
    }

    @Test fun `떠나는 순간에 시작한 정차는 도착 장에 들어간다`() {
        val ms = moments + mo(17, 5, "STOP", "3")
        val ch = buildChapters(roundTrip, roundSamples, ms, home)
        assertEquals(listOf("RAIN_STOP", "WINDOWS_OPEN", "STOP"), ch[3].moments.map { it.type })
        assertEquals(t(17, 5), ch[3].moments.last().timeMs)
        assertTrue(ch[4].moments.isEmpty())
    }

    @Test fun `거리상 가운데 지점`() {
        assertNull(routeMidpoint(emptyList()))
        assertEquals(37.0 to 127.0, routeMidpoint(listOf(37.0 to 127.0)))
        val mid = routeMidpoint(listOf(37.0 to 127.0, 37.0 to 127.0, 37.2 to 127.0))!!   // 길이 0 구간은 건너뛴다
        assertEquals(37.1, mid.first, 1e-9); assertEquals(127.0, mid.second, 1e-9)
    }
    @Test fun `도착 장 기록문 끝에 그날의 바깥 문장을 잇고, 다른 장에는 붙이지 않는다`() {
        val ch = buildChapters(trip, samples, emptyList())
        val o = ai.yeoro.cloud.OutsideInfo(t(13, 20), ai.yeoro.cloud.Weather(18.0, "맑음", null, true), ai.yeoro.cloud.SunTimes("06:41", "17:36"), null, null, "한글날")
        assertEquals("13시 20분, 강릉 경포해변에 도착했습니다. 도착할 무렵 기온은 18℃, 하늘은 맑았습니다. 해는 17시 36분에 졌습니다. 이날은 한글날이었습니다.",
            chapterDiary(ch.last(), "강릉 경포해변", zone, outside = o))
        assertEquals("08시 52분에 출발했습니다. 목적지는 강릉 경포해변.", chapterDiary(ch.first(), "강릉 경포해변", zone, outside = o))
    }
}
