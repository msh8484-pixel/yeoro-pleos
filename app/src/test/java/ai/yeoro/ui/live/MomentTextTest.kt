package ai.yeoro.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MomentTextTest {
    @Test fun rainStart() = assertEquals(MomentLine("비가 오기 시작했어요", "와이퍼", MomentIcon.RAIN), momentLine("RAIN_START", ""))
    @Test fun rainStop() = assertEquals(MomentLine("비가 그쳤어요", "와이퍼", MomentIcon.SUN), momentLine("RAIN_STOP", ""))
    @Test fun windows() = assertEquals(MomentLine("창문 3개가 열렸어요", "창문", MomentIcon.WINDOW), momentLine("WINDOWS_OPEN", "3"))
    @Test fun stop() = assertEquals(MomentLine("38분 쉬어 갔어요", "도어·P단", MomentIcon.STOP), momentLine("STOP", "38"))
    @Test fun companion() = assertEquals(MomentLine("함께하는 사람이 늘었어요", "좌석", MomentIcon.USER), momentLine("COMPANION_JOINED", "3→4"))
    @Test fun departureSeats() = assertEquals(MomentLine("4명이 함께 출발했어요", "좌석", MomentIcon.USER), momentLine("COMPANION_JOINED", "→4|DRIVER,PASSENGER,REAR_LEFT,REAR_RIGHT"))
    @Test fun unknownTypeIsHidden() = assertNull(momentLine("SOMETHING", ""))
    @Test fun seatNames() = assertEquals(listOf("운전석", "조수석", "뒷좌석 왼쪽", "뒷좌석 가운데", "뒷좌석 오른쪽"),
        ai.yeoro.engine.Seat.entries.map { seatName(it) })
    @Test fun personNameFallsBackToSeat() {
        val names = mapOf(ai.yeoro.engine.Seat.DRIVER to "아빠", ai.yeoro.engine.Seat.PASSENGER to " ")
        assertEquals("아빠", personName(ai.yeoro.engine.Seat.DRIVER, names))
        assertEquals("조수석", personName(ai.yeoro.engine.Seat.PASSENGER, names))
        assertEquals("뒷좌석 왼쪽", personName(ai.yeoro.engine.Seat.REAR_LEFT, emptyMap()))
    }
}
