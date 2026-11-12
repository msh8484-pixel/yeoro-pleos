package ai.yeoro.ui.album

import ai.yeoro.data.LocationSampleEntity
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripLegsTest {
    private var clock = 0L
    private fun s(lat: Double, lon: Double) = LocationSampleEntity(tripId = 1, timeMs = 60_000L * clock++, lat = lat, lon = lon)

    private val home = 37.5563 to 126.9220
    private val dest = 37.8055 to 128.9087

    /** 서울 → 강릉(도착 3, 근처 4·5) → 서울 */
    private fun roundTrip(): List<LocationSampleEntity> { clock = 0; return listOf(
        s(37.5563, 126.9220), s(37.49, 127.98), s(37.68, 128.72),
        s(37.8050, 128.9080), s(37.8045, 128.9070), s(37.8060, 128.9090),   // 3 도착, 4·5 근처
        s(37.68, 128.72), s(37.49, 127.98), s(37.5560, 126.9230),
    ) }

    @Test fun `서울 강릉 서울 - 도착, 떠남, 귀가`() {
        assertEquals(TripLegs(arriveIdx = 3, leaveIdx = 5, returnsHome = true), tripLegs(roundTrip(), dest, home))
    }

    @Test fun `편도 - 마지막이 목적지면 왕복이 아니다`() {
        val oneWay = roundTrip().take(6)
        assertEquals(TripLegs(arriveIdx = 3, leaveIdx = null, returnsHome = false), tripLegs(oneWay, dest, home))
    }

    @Test fun `목적지 없음, 집 있음 - 집에서 가장 먼 점이 도착`() {
        clock = 0
        val ss = listOf(s(37.5563, 126.9220), s(37.49, 127.98), s(37.70, 128.80), s(37.70, 128.80), s(37.49, 127.98), s(37.5563, 126.9220))
        assertEquals(TripLegs(arriveIdx = 2, leaveIdx = 3, returnsHome = true), tripLegs(ss, null, home))
        // 목적지 근처에 한 번도 안 들었으면 같은 규칙
        assertEquals(TripLegs(arriveIdx = 2, leaveIdx = 3, returnsHome = true), tripLegs(ss, 35.1 to 129.0, home))
    }

    @Test fun `집 모름 - 마지막 점이 도착, 왕복 아님`() {
        assertEquals(TripLegs(arriveIdx = 8, leaveIdx = null, returnsHome = false), tripLegs(roundTrip(), null, null))
        assertEquals(TripLegs(arriveIdx = 3, leaveIdx = null, returnsHome = false), tripLegs(roundTrip(), dest, null))
    }

    @Test fun `목적지 근처를 두 번 들락날락 - 마지막 근처 샘플이 떠남`() {
        clock = 0
        val ss = listOf(
            s(37.5563, 126.9220), s(37.68, 128.72),
            s(37.8055, 128.9087), s(37.7950, 128.8900), s(37.8050, 128.9080),   // 2 도착, 3 잠깐 벗어남(1.2km), 4 다시 근처
            s(37.68, 128.72), s(37.5563, 126.9220),
        )
        assertEquals(TripLegs(arriveIdx = 2, leaveIdx = 4, returnsHome = true), tripLegs(ss, dest, home))
    }

    @Test fun `도착 지점이 집에서 1점5km면 왕복이 아니다`() {
        clock = 0
        val near = 37.5563 + 0.0135 to 126.9220   // 북쪽 약 1.5km
        val ss = listOf(s(37.5563, 126.9220), s(near.first, near.second), s(37.5563, 126.9220))
        assertEquals(TripLegs(arriveIdx = 1, leaveIdx = null, returnsHome = false), tripLegs(ss, near, home))
    }

    @Test fun `샘플이 없으면 null`() {
        assertNull(tripLegs(emptyList(), dest, home))
    }

    @Test fun `떠남은 목적지 300m로 찾는다 - 도착 샘플에서 300~600m인 머문 샘플도 목적지 곁이면 떠나기 전`() {
        clock = 0
        val west = 37.8055 to 128.9056    // 목적지 서쪽 약 270m (도착 샘플)
        val east = 37.8055 to 128.9118    // 목적지 동쪽 약 270m, 도착 샘플에서 약 545m
        val ss = listOf(
            s(37.5563, 126.9220), s(37.68, 128.72),
            s(west.first, west.second), s(east.first, east.second), s(east.first, east.second), s(east.first, east.second),   // 2 도착, 3~5 머묾
            s(37.68, 128.72), s(37.5563, 126.9220),
        )
        assertEquals(TripLegs(arriveIdx = 2, leaveIdx = 5, returnsHome = true), tripLegs(ss, dest, home))
        val trip = TripEntity(id = 1, startMs = 0, endMs = ss.last().timeMs, destination = "경포", state = "ALBUM_READY", destLat = dest.first, destLon = dest.second)
        val arrival = buildChapters(trip, ss, emptyList(), home).first { it.kind == ChapterKind.ARRIVAL }
        assertEquals(3, arrival.stayMin)
    }

    @Test fun `지나쳤다 돌아와 세운 곳이 도착 - 지나친 샘플은 도착이 아니다`() {
        clock = 0
        val ss = listOf(
            s(37.5563, 126.9220), s(37.68, 128.72),
            s(37.8060, 128.9090),                                               // 2 지나침(300m 안, 1분 만에 벗어남)
            s(37.8150, 128.9150),                                               // 3 북쪽 약 1.2km에서 돌아섬
            s(37.8050, 128.9080), s(37.8050, 128.9080), s(37.8050, 128.9080),   // 4~6 세움(2분)
            s(37.68, 128.72), s(37.5563, 126.9220),
        )
        assertEquals(TripLegs(arriveIdx = 4, leaveIdx = 6, returnsHome = true), tripLegs(ss, dest, home))
    }

    @Test fun `목적지가 출발점 가까이여도 출발 샘플은 도착이 아니다`() {
        clock = 0
        val ss = listOf(
            s(37.8040, 128.9075),                                               // 0 출발(목적지 200m 안), 곧 떠남
            s(37.7900, 128.8800), s(37.7500, 128.8450), s(37.7900, 128.8800),   // 1~3 다녀옴
            s(37.8050, 128.9080), s(37.8050, 128.9080), s(37.8052, 128.9083),   // 4~6 목적지에 세움
        )
        val legs = tripLegs(ss, dest, null)!!
        assertEquals(4, legs.arriveIdx)
        val trip = TripEntity(id = 1, startMs = 0, endMs = ss.last().timeMs, destination = "경포", state = "ALBUM_READY", destLat = dest.first, destLon = dest.second)
        val ch = buildChapters(trip, ss, emptyList())
        assertTrue(ch.last { it.kind == ChapterKind.ARRIVAL }.startMs - ch.first().startMs > 0)   // 0:00 소요가 아니다
    }

    @Test fun `편도 - 300m 안에서 머문 첫 샘플이 도착, 정차 순간도 머묾으로 친다`() {
        clock = 0
        val ss = listOf(
            s(37.5563, 126.9220), s(37.68, 128.72),
            s(37.8060, 128.9090), s(37.8150, 128.9150),                          // 2 지나침, 3 벗어남
            s(37.8050, 128.9080), s(37.8051, 128.9081), s(37.8050, 128.9082),   // 4~6 세움(여행 끝)
        )
        assertEquals(TripLegs(arriveIdx = 4, leaveIdx = null, returnsHome = false), tripLegs(ss, dest, home))
        // 반경 안 샘플이 하나뿐이어도 그때 시작한 정차가 있으면 머문 것
        val short = ss.take(5)
        val stop = MomentEntity(tripId = 1, timeMs = short[4].timeMs, type = "STOP", detail = "40", lat = 37.8050, lon = 128.9080)
        assertEquals(4, tripLegs(short, dest, home, listOf(stop))!!.arriveIdx)
        // 정차가 반경 밖이면 머묾이 아니고, 머문 샘플이 없으니 처음 든 샘플
        val far = stop.copy(lat = 37.79, lon = 128.88)
        assertEquals(2, tripLegs(short, dest, home, listOf(far))!!.arriveIdx)
    }

    @Test fun `GPS 튐 - 반경 안에 한 번 튄 샘플은 무시한다`() {
        clock = 0
        val ss = listOf(
            s(37.5563, 126.9220), s(37.7800, 128.8800),
            s(37.8040, 128.9070),                                               // 2 튐(반경 안 한 번)
            s(37.7850, 128.8850), s(37.7900, 128.8900),                         // 3·4 실제로는 아직 오는 길
            s(37.8055, 128.9087), s(37.8055, 128.9087), s(37.8056, 128.9088),   // 5~7 도착해 머묾
        )
        assertEquals(5, tripLegs(ss, dest, home)!!.arriveIdx)
    }

    @Test fun `떠남이 도착과 같은 샘플이어도 왕복`() {
        clock = 0
        val ss = listOf(s(37.5563, 126.9220), s(37.68, 128.72), s(37.8055, 128.9087), s(37.68, 128.72), s(37.5563, 126.9220))
        assertEquals(TripLegs(arriveIdx = 2, leaveIdx = 2, returnsHome = true), tripLegs(ss, dest, home))
        val trip = TripEntity(id = 1, startMs = 0, endMs = ss.last().timeMs, destination = "경포", state = "ALBUM_READY", destLat = dest.first, destLon = dest.second)
        val ch = buildChapters(trip, ss, emptyList(), home)
        assertEquals(listOf(ChapterKind.DEPARTURE, ChapterKind.ARRIVAL, ChapterKind.RETURN), ch.map { it.kind })
        assertEquals(0, ch[1].stayMin)
        assertEquals(ch[1].startMs, ch[2].startMs)
    }
}
