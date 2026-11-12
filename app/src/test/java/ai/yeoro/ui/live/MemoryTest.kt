package ai.yeoro.ui.live

import ai.yeoro.data.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MemoryTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 10, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun trip(id: Long, start: Long, end: Long? = start + 6 * 3_600_000L, state: String = "ALBUM_READY", dest: String? = "강릉 경포해변") =
        TripEntity(id = id, startMs = start, endMs = end, destination = dest, state = state)

    private val now = ms(2026, 10, 31, 9, 0)   // 기준: 2025-10-31 ± 3일

    @Test fun `±3일 경계 - 안쪽은 고르고 4일은 뺀다`() {
        assertEquals(1L, memoryTrip(listOf(trip(1, ms(2025, 10, 28, 23, 59))), now, zone)?.id)   // −3일
        assertEquals(2L, memoryTrip(listOf(trip(2, ms(2025, 11, 3, 0, 1))), now, zone)?.id)      // +3일
        assertNull(memoryTrip(listOf(trip(3, ms(2025, 10, 27, 23, 59))), now, zone))             // −4일
        assertNull(memoryTrip(listOf(trip(4, ms(2025, 11, 4, 0, 1))), now, zone))                // +4일
    }

    @Test fun `2년 전 이번 주와 올해 여행은 빼고, 확인 안 한 여행·기록 중 여행도 뺀다`() {
        assertNull(memoryTrip(listOf(trip(1, ms(2024, 10, 31))), now, zone))
        assertNull(memoryTrip(listOf(trip(2, ms(2026, 10, 30))), now, zone))
        assertNull(memoryTrip(listOf(trip(3, ms(2025, 10, 31), state = "UNCONFIRMED")), now, zone))
        assertNull(memoryTrip(listOf(trip(4, ms(2025, 10, 31), end = null, state = "TRIP")), now, zone))
    }

    @Test fun `가장 가까운 날, 같으면 늦게 끝난 여행`() {
        val far = trip(1, ms(2025, 10, 29))          // 2일
        val near = trip(2, ms(2025, 11, 1))          // 1일
        assertEquals(2L, memoryTrip(listOf(far, near), now, zone)?.id)
        val early = trip(3, ms(2025, 10, 31, 8), end = ms(2025, 10, 31, 12))
        val late = trip(4, ms(2025, 10, 31, 15), end = ms(2025, 10, 31, 20))
        assertEquals(4L, memoryTrip(listOf(near, late, early, far), now, zone)?.id)
        // 앞뒤 같은 거리(−1일, +1일)면 늦게 끝난 쪽
        val before = trip(5, ms(2025, 10, 30), end = ms(2025, 10, 30, 18))
        assertEquals(2L, memoryTrip(listOf(before, near), now, zone)?.id)
    }

    @Test fun `윤년 2월 29일은 작년 2월 28일 기준`() {
        val leap = ms(2028, 2, 29, 9)
        assertEquals(1L, memoryTrip(listOf(trip(1, ms(2027, 3, 3)), trip(2, ms(2027, 3, 4))), leap, zone)?.id)   // 2/28 + 3일 = 3/3
        assertNull(memoryTrip(listOf(trip(2, ms(2027, 3, 4))), leap, zone))
        assertEquals(3L, memoryTrip(listOf(trip(3, ms(2027, 2, 25))), leap, zone)?.id)   // 2/28 − 3일
    }

    @Test fun `memoryVisible - 한 번 보인 뒤 안 보이게 되면 그날은 다시 안 나온다`() {
        val day = "2026-10-31"
        assertTrue(memoryVisible(null, day, 7, null))                       // 아직 안 띄움
        assertTrue(memoryVisible(day, day, 7, day to 7L))                   // 떠 있는 카드는 날짜가 적힌 뒤에도 유지
        assertFalse(memoryVisible(day, day, 7, null))                       // 닫힘/P 이탈/화면 떠남 후(onScreen 비움)
        assertFalse(memoryVisible(day, day, 7, "2026-10-30" to 7L))         // 다른 날 표시는 무시
        assertFalse(memoryVisible(day, day, 8, day to 7L))                  // 다른 여행 카드
        assertTrue(memoryVisible("2026-10-30", day, 7, null))               // 어제 띄웠으면 오늘 새로
    }

    @Test fun `memoryDue - 하루 한 번`() {
        assertTrue(memoryDue(null, "2026-10-31"))
        assertTrue(memoryDue("2026-10-30", "2026-10-31"))
        assertFalse(memoryDue("2026-10-31", "2026-10-31"))
    }

    @Test fun `회상 카드 문구`() {
        val t = trip(9, ms(2025, 11, 1, 10, 5))
        assertEquals(Promo.Memory(9, "1년 전 이번 주, 우리는 강릉 경포해변에 있었어요", "2025년 11월 1일 · 사진 1장"), memoryText(t, 1, zone))
        assertEquals(Promo.Memory(9, "1년 전 이번 주의 여행", "2025년 11월 1일"), memoryText(t.copy(destination = null), 0, zone))
        assertEquals("2025년 11월 1일 · 사진 12장", memoryText(t.copy(destination = " "), 12, zone).sub)
        assertEquals("1년 전 이번 주의 여행", memoryText(t.copy(destination = " "), 12, zone).title)
    }
}
