package ai.yeoro.engine
import org.junit.Assert.assertEquals
import org.junit.Test

class MomentDetectorTest {
    private val min = 60_000L
    private val four = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_RIGHT)
    private fun snap(t: Long, gear: Gear = Gear.D, wiper: Boolean = false, windows: Int = 0, door: Boolean = false, seats: Set<Seat> = four) =
        Snapshot(t * min, gear, if (gear == Gear.D) 90f else 0f, seats, false, wiper, windows, door, 50.0)

    @Test fun rainNeedsThreeMinutesAfterLongDryPeriod() {
        val d = MomentDetector()
        val out = (0L..40L).flatMap { d.feed(snap(it, wiper = it >= 35)) }
        assertEquals(listOf(MomentType.RAIN_START), out.map { it.type })
        assertEquals(38 * min, out.first().timeMs)
    }
    @Test fun shortWiperFlickIsIgnored() {
        val d = MomentDetector()
        val out = (0L..40L).flatMap { d.feed(snap(it, wiper = it in 35..36)) }
        assertEquals(emptyList<MomentType>(), out.map { it.type })
    }
    @Test fun rainStopsAfterTenDryMinutes() {
        val d = MomentDetector()
        val out = (0L..60L).flatMap { d.feed(snap(it, wiper = it in 31..40)) }
        assertEquals(listOf(MomentType.RAIN_START, MomentType.RAIN_STOP), out.map { it.type })
    }
    @Test fun threeWindowsOpenAtOnce() {
        val d = MomentDetector()
        val out = listOf(snap(0), snap(1, windows = 1), snap(2, windows = 4), snap(3, windows = 4)).flatMap(d::feed)
        assertEquals(listOf(MomentType.WINDOWS_OPEN), out.map { it.type })
    }
    @Test fun stopCountsOnlyIfTenMinutesWithDoor() {
        val d = MomentDetector()
        val seq = listOf(snap(0)) + (1L..38L).map { snap(it, Gear.P, door = it == 2L) } + snap(39)
        val out = seq.flatMap(d::feed)
        assertEquals(listOf(MomentType.STOP), out.map { it.type }); assertEquals("38", out.first().detail)
        val short = MomentDetector()
        val out2 = (listOf(snap(0)) + (1L..6L).map { snap(it, Gear.P, door = true) } + snap(7)).flatMap(short::feed)
        assertEquals(emptyList<MomentType>(), out2.map { it.type })
    }
    @Test fun companionJoinsAfterStop() {
        val d = MomentDetector()
        val five = four + Seat.REAR_CENTER
        val seq = listOf(snap(0)) + (1L..15L).map { snap(it, Gear.P, door = true) } + snap(16, seats = five)
        val out = seq.flatMap(d::feed).map { it.type }
        assertEquals(listOf(MomentType.STOP, MomentType.COMPANION_JOINED), out)
    }
    @Test fun rainAlreadyFallingAtStartIsDetected() {
        val d = MomentDetector()
        val out = (0L..5L).flatMap { d.feed(snap(it, wiper = true)) }
        assertEquals(listOf(MomentType.RAIN_START), out.map { it.type })
        assertEquals(3 * min, out.first().timeMs)
    }
    @Test fun companionJoinsEvenWhenFirstSnapshotIsParked() {
        val d = MomentDetector()
        val seq = (0L..15L).map { snap(it, Gear.P, door = it == 1L) } + snap(16, seats = four + Seat.REAR_CENTER)
        val out = seq.flatMap(d::feed).map { it.type }
        assertEquals(listOf(MomentType.STOP, MomentType.COMPANION_JOINED), out)
    }
}
