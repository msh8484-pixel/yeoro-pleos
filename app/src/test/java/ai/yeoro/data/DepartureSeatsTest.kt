package ai.yeoro.data

import ai.yeoro.engine.Seat
import ai.yeoro.ui.album.momentSentence
import ai.yeoro.ui.album.seatCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 바로 출발한 여행의 출발 좌석 순간: 앨범의 탑승 인원과 출발 기록문이 "기록 없음"이 되지 않게 한다. */
class DepartureSeatsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val four = setOf(Seat.REAR_RIGHT, Seat.DRIVER, Seat.REAR_LEFT, Seat.PASSENGER)

    @Test fun detail_lists_count_and_seats_in_seat_order() =
        assertEquals("→4|DRIVER,PASSENGER,REAR_LEFT,REAR_RIGHT", departureSeatsDetail(four))

    @Test fun nobody_seated_records_nothing() = assertNull(departureSeatsDetail(emptySet()))

    @Test fun album_reads_it_as_seat_count_and_departure_sentence() {
        val t = LocalDateTime.of(2026, 10, 24, 8, 52).atZone(zone).toInstant().toEpochMilli()
        val m = MomentEntity(tripId = 1, timeMs = t, type = "COMPANION_JOINED", detail = departureSeatsDetail(four)!!)
        assertEquals(4, seatCount(listOf(m)))
        assertEquals("08시 52분, 네 자리에 가족이 앉았습니다(운전석·조수석·뒷좌석 왼쪽·뒷좌석 오른쪽).", momentSentence(m, zone))
    }

    @Test fun two_seats_read_as_people_count() {
        val m = MomentEntity(tripId = 1, timeMs = 0, type = "COMPANION_JOINED", detail = departureSeatsDetail(setOf(Seat.DRIVER, Seat.PASSENGER))!!)
        assertEquals(2, seatCount(listOf(m)))
    }
}
