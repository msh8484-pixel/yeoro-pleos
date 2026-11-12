package ai.yeoro.service

import ai.yeoro.engine.Gear
import org.junit.Assert.*
import org.junit.Test

class ArrivalPolicyTest {
    private val now = 1_000_000L
    private fun due(trip: Long? = 7, fired: Long? = null, navi: Long? = null, gear: Gear = Gear.P, parked: Long? = now - 120_000, dist: Double? = 250.0) =
        arrivalDue(trip, fired, navi, gear, parked, dist, now)

    @Test fun `parked 2 minutes within 300m is arrival`() = assertTrue(due())
    @Test fun `exactly 300m counts`() = assertTrue(due(dist = 300.0))
    @Test fun `beyond 300m is not arrival`() = assertFalse(due(dist = 301.0))
    @Test fun `parked under 2 minutes is not arrival`() = assertFalse(due(parked = now - 119_999))
    @Test fun `unknown distance without navi arrival is not arrival`() = assertFalse(due(dist = null))
    @Test fun `not parked is not arrival`() = assertFalse(due(parked = null))
    @Test fun `no open trip never arrives`() = assertFalse(due(trip = null, navi = null))
    @Test fun `fires once per trip`() = assertFalse(due(fired = 7))
    @Test fun `previous trip fired does not block new trip`() = assertTrue(due(fired = 6))
    @Test fun `navi arrival for this trip is arrival once in P even far and just parked`() = assertTrue(due(navi = 7, parked = now, dist = null))
    @Test fun `navi arrival waits for P (screen cards only in P)`() = assertFalse(due(navi = 7, gear = Gear.D, parked = null))
    @Test fun `navi arrival of an older trip does not count`() = assertFalse(due(navi = 6, parked = now, dist = null))

    @Test fun `closes after 30 seconds`() {
        assertFalse(arrivalCloseDue(shownMs = now, nowMs = now + 29_999, gear = Gear.P))
        assertTrue(arrivalCloseDue(shownMs = now, nowMs = now + 30_000, gear = Gear.P))
    }
    @Test fun `closes when gear leaves P but not on unknown`() {
        assertTrue(arrivalCloseDue(now, now + 1, Gear.D))
        assertTrue(arrivalCloseDue(now, now + 1, Gear.R))
        assertTrue(arrivalCloseDue(now, now + 1, Gear.N))
        assertFalse(arrivalCloseDue(now, now + 1, Gear.UNKNOWN))
    }

    @Test fun `visit ordinal words`() {
        assertEquals("첫 번째", visitOrdinal(1)); assertEquals("두 번째", visitOrdinal(2)); assertEquals("세 번째", visitOrdinal(3))
        assertEquals("열 번째", visitOrdinal(10)); assertEquals("11번째", visitOrdinal(11))
    }
    @Test fun `regular stage caps at 3 and texts`() {
        assertEquals(1, regularStage(1)); assertEquals(2, regularStage(2)); assertEquals(3, regularStage(3)); assertEquals(3, regularStage(9))
        assertEquals("단골까지 두 번 남았어요", regularStageText(1))
        assertEquals("단골까지 한 번 남았어요", regularStageText(2))
        assertEquals("단골이에요", regularStageText(4))
    }
    @Test fun `stage legend promises 그때 vs 지금 only when a comparable album exists`() {
        assertEquals("1회 스탬프 · 2회 그때 vs 지금 · 3회 단골", stageLegend(1, hasThen = false))
        assertEquals("1회 스탬프 · 2회 그때 vs 지금 · 3회 단골", stageLegend(2, hasThen = true))
        assertEquals("1회 스탬프 · 2회 다시 찾음 · 3회 단골", stageLegend(2, hasThen = false))
        assertEquals("1회 스탬프 · 2회 다시 찾음 · 3회 단골", stageLegend(3, hasThen = false))
    }
    @Test fun `title names place and ordinal`() {
        assertEquals("강릉 경포해변, 두 번째 방문이에요", arrivalTitle("강릉 경포해변", 2))
        assertEquals("이곳, 첫 번째 방문이에요", arrivalTitle(null, 1))
    }
    @Test fun `upload url is our supabase function with the share token, not the trip id`() =
        assertEquals("https://dghpdvpkgvamsaidndqa.supabase.co/functions/v1/upload?t=AbC-_x", arrivalUrl("AbC-_x"))
    @Test fun `stamp place is first word of destination`() {
        assertEquals("강릉", stampPlace("강릉 경포해변")); assertEquals("이곳", stampPlace(null)); assertEquals("이곳", stampPlace("  "))
    }

    @Test fun `restored flag is valid only for the same open trip`() {
        assertEquals(7L, restoredTripFlag(7, 7))
        assertNull(restoredTripFlag(6, 7))
        assertNull(restoredTripFlag(NO_TRIP, 7))
        assertNull(restoredTripFlag(7, null))
    }
    @Test fun `restored fired trip blocks a second arrival after restart`() =
        assertFalse(arrivalDue(7, restoredTripFlag(7, 7), null, Gear.P, now - 120_000, 100.0, now))
}
