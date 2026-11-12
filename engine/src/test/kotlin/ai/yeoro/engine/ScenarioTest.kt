package ai.yeoro.engine
import org.junit.Assert.*
import org.junit.Test

class ScenarioTest {
    private val min = 60_000L
    private val family = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_RIGHT)
    private val sat = CalendarInfo(weekend = true, holiday = false, hour = 8)
    private fun tick(t: Long, gear: Gear, seats: Set<Seat> = family, trunk: Boolean = false, home: Double = 0.0, cal: CalendarInfo = sat, door: Boolean = false, wiper: Boolean = false, day: String = "d1", newRoute: Boolean = false) =
        Event.Tick(Snapshot(t * min, gear, if (gear == Gear.D) 90f else 0f, seats, trunk, wiper, 0, door, home), cal, day, newRoute)
    private fun List<Action>.has(c: Class<*>) = any { c.isInstance(it) }

    @Test fun A_plannedTrip_asksBeforeDeparture_thenStarts() {
        val e = TripEngine()
        e.handle(tick(0, Gear.P)); e.handle(tick(1, Gear.P, trunk = true)); e.handle(tick(2, Gear.P))
        e.handle(tick(3, Gear.P, trunk = true)); e.handle(tick(4, Gear.P))
        val ask = e.handle(Event.DestinationSet(Destination("강릉 경포해변", 238.0, PlaceKind.TOURIST)))
        assertTrue(ask.has(Action.AskBeforeDeparture::class.java))
        e.handle(Event.Answer(true))
        val go = e.handle(tick(6, Gear.D, home = 2.0))
        assertTrue(go.has(Action.StartTrip::class.java)); assertEquals(TripState.TRIP, e.state)
    }
    @Test fun B_spontaneous_silentThenRetroactiveAsk() {
        val e = TripEngine(); val three = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT)
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        val go = e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true))
        assertFalse(go.has(Action.AskBeforeDeparture::class.java)); assertEquals(TripState.CANDIDATE, e.state)
        (4L..80L).forEach { e.handle(tick(it, Gear.D, three, home = 86.0, newRoute = true)) }
        val asks = (81L..145L).flatMap { e.handle(tick(it, Gear.P, three, home = 86.0, newRoute = true)) }
        val ask = asks.filterIsInstance<Action.AskRetroactive>().single()
        assertEquals(3 * min, ask.sinceMs)
        val acc = e.handle(Event.Answer(true))
        assertTrue(acc.any { it is Action.StartTrip && it.retroactive }); assertEquals(TripState.TRIP, e.state)
    }
    @Test fun B_declined_discards() {
        val e = TripEngine(); val three = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT)
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        e.handle(tick(3, Gear.D, three, newRoute = true)); (4L..80L).forEach { e.handle(tick(it, Gear.D, three, home = 86.0, newRoute = true)) }
        (81L..145L).forEach { e.handle(tick(it, Gear.P, three, home = 86.0, newRoute = true)) }
        assertTrue(e.handle(Event.Answer(false)).has(Action.Discard::class.java)); assertEquals(TripState.DAILY, e.state)
    }
    @Test fun C_commute_neverAsks() {
        val e = TripEngine(); val solo = setOf(Seat.DRIVER); val tue = CalendarInfo(false, false, 7)
        e.handle(tick(0, Gear.P, solo, cal = tue))
        val a = e.handle(Event.DestinationSet(Destination("회사", 9.0, PlaceKind.WORK, frequent = true)))
        val b = (1L..30L).flatMap { e.handle(tick(it, Gear.D, solo, cal = tue)) }
        assertFalse((a + b).has(Action.AskBeforeDeparture::class.java)); assertEquals(TripState.DAILY, e.state)
    }
    @Test fun F_overnightStay_continuesWithoutAsking() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D))
        val night = CalendarInfo(true, false, 22)
        (2L..250L).forEach { e.handle(tick(it, Gear.P, home = 238.0, cal = night)) }
        assertEquals(TripState.STAYING, e.state)
        val next = e.handle(tick(251, Gear.D, home = 238.0, cal = CalendarInfo(false, false, 9), day = "d2"))
        assertFalse(next.has(Action.AskBeforeDeparture::class.java)); assertEquals(TripState.TRIP, e.state)
    }
    @Test fun J_lateReturn_notifiesOnPhoneNextMorning() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D, home = 10.0))
        val late = CalendarInfo(false, false, 22)
        val out = (2L..125L).flatMap { e.handle(tick(it, Gear.P, home = 0.2, cal = late)) }
        val closed = out.filterIsInstance<Action.TripClosed>().single()
        assertEquals(8, closed.notifyOnPhoneAtHour); assertEquals(TripState.ALBUM_READY, e.state)
    }
    @Test fun L_guestProfile_doesNothing() {
        val e = TripEngine(); e.handle(Event.ProfileChanged(consented = false))
        val out = e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))) + e.handle(tick(0, Gear.P))
        assertTrue(out.isEmpty()); assertEquals(TripState.OFF, e.state)
    }
    @Test fun I_rainDuringTrip_isRecorded() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true))
        val out = (1L..45L).flatMap { e.handle(tick(it, Gear.D, home = 50.0, wiper = it >= 40)) }
        assertTrue(out.any { it is Action.RecordMoment && it.moment.type == MomentType.RAIN_START })
    }

    private fun plannedAsk(e: TripEngine, t0: Long, day: String): List<Action> =
        e.handle(tick(t0, Gear.P, day = day)) + e.handle(tick(t0 + 1, Gear.P, trunk = true, day = day)) + e.handle(tick(t0 + 2, Gear.P, day = day)) +
        e.handle(tick(t0 + 3, Gear.P, trunk = true, day = day)) + e.handle(tick(t0 + 4, Gear.P, day = day)) +
        e.handle(Event.DestinationSet(Destination("강릉 경포해변", 238.0, PlaceKind.TOURIST)))
    @Test fun commuteThenPlannedTrip_stillAsks() {
        val e = TripEngine(); val solo = setOf(Seat.DRIVER); val tue = CalendarInfo(false, false, 7)
        e.handle(tick(0, Gear.P, solo, cal = tue))
        e.handle(Event.DestinationSet(Destination("회사", 9.0, PlaceKind.WORK, frequent = true)))
        (1L..30L).forEach { e.handle(tick(it, Gear.D, solo, cal = tue, home = 5.0)) }
        (31L..35L).forEach { e.handle(tick(it, Gear.P, solo, cal = tue, home = 9.0)) }
        assertEquals(TripState.DAILY, e.state)
        val ask = plannedAsk(e, 100, "d2")
        assertTrue(ask.has(Action.AskBeforeDeparture::class.java))
        e.handle(Event.Answer(true))
        assertTrue(e.handle(tick(110, Gear.D, home = 2.0, day = "d2")).has(Action.StartTrip::class.java))
        assertEquals(TripState.TRIP, e.state)
    }
    @Test fun afterTripClosed_nextTripIsEvaluated() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D, home = 10.0))
        val out = (2L..125L).flatMap { e.handle(tick(it, Gear.P, home = 0.2)) }
        assertTrue(out.has(Action.TripClosed::class.java))
        assertTrue(plannedAsk(e, 200, "d2").has(Action.AskBeforeDeparture::class.java))
    }
    @Test fun albumReady_releasedOnNextDay() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D, home = 10.0))
        val states = (2L..125L).flatMap { e.handle(tick(it, Gear.P, home = 0.2)) }.filterIsInstance<Action.ScoreUpdated>().map { it.state }
        assertEquals(TripState.ALBUM_READY, states.last()); assertEquals(TripState.ALBUM_READY, e.state)
        val next = e.handle(tick(126, Gear.P, home = 0.2, day = "d2")).filterIsInstance<Action.ScoreUpdated>().single()
        assertEquals(TripState.DAILY, next.state)
    }
    @Test fun lateReturn_usesArrivalHour_notCloseHour() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D, home = 10.0))
        val early = CalendarInfo(false, false, 21); val late = CalendarInfo(false, false, 23)
        val out = (2L..61L).flatMap { e.handle(tick(it, Gear.P, home = 0.2, cal = early)) } + (62L..125L).flatMap { e.handle(tick(it, Gear.P, home = 0.2, cal = late)) }
        assertNull(out.filterIsInstance<Action.TripClosed>().single().notifyOnPhoneAtHour)
    }
    @Test fun profileChanged_consentedKeepsTripState() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D, home = 10.0))
        assertEquals(TripState.TRIP, e.state)
        e.handle(Event.ProfileChanged(true)); assertEquals(TripState.TRIP, e.state)
        e.handle(Event.ProfileChanged(false)); assertEquals(TripState.OFF, e.state)
        e.handle(Event.ProfileChanged(true)); assertEquals(TripState.DAILY, e.state)
    }
    @Test fun candidateReturnsHome_discardsAndReArms() {
        val e = TripEngine(); val three = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT)
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true)); assertEquals(TripState.CANDIDATE, e.state)
        (4L..45L).forEach { e.handle(tick(it, Gear.D, three, home = 20.0, newRoute = true)) }
        val back = (46L..52L).flatMap { e.handle(tick(it, Gear.P, three, home = 0.5, newRoute = true)) }
        assertTrue(back.has(Action.Discard::class.java)); assertEquals(TripState.DAILY, e.state)
        assertTrue(plannedAsk(e, 200, "d2").has(Action.AskBeforeDeparture::class.java))
    }
    @Test fun staleDestinationDoesNotTriggerNextDay() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D, home = 10.0))
        val out = (2L..125L).flatMap { e.handle(tick(it, Gear.P, home = 0.2)) }
        assertTrue(out.has(Action.TripClosed::class.java))
        val next = (200L..205L).flatMap { e.handle(tick(it, Gear.P, home = 0.2, day = "d2")) }
        assertFalse(next.has(Action.AskBeforeDeparture::class.java))
        assertTrue(e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))).has(Action.AskBeforeDeparture::class.java))
    }
    @Test fun declinedTrip_restStopDoesNotRevive() {
        val e = TripEngine(); e.handle(tick(0, Gear.P))
        assertTrue(e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))).has(Action.AskBeforeDeparture::class.java))
        e.handle(Event.Answer(false)); e.handle(tick(1, Gear.D, home = 1.0))
        (2L..30L).forEach { e.handle(tick(it, Gear.D, home = 120.0)) }
        (31L..45L).forEach { e.handle(tick(it, Gear.P, home = 120.0)) }
        (46L..50L).forEach { e.handle(tick(it, Gear.D, home = 125.0)) }
        assertEquals(TripState.DAILY, e.state)
    }
    @Test fun candidateNearHome_needsFiveMinuteDwell() {
        fun start(): TripEngine {
            val e = TripEngine(); val three = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT)
            e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
            e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true)); return e
        }
        val three = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT)
        val e1 = start(); e1.handle(tick(4, Gear.P, three, home = 0.5, newRoute = true)); e1.handle(tick(5, Gear.P, three, home = 0.5, newRoute = true))
        assertEquals(TripState.CANDIDATE, e1.state)
        val e2 = start(); val out = (4L..10L).flatMap { e2.handle(tick(it, Gear.P, three, home = 0.5, newRoute = true)) }
        assertTrue(out.has(Action.Discard::class.java)); assertEquals(TripState.DAILY, e2.state)
    }
    @Test fun destinationSetWhileDriving_survivesFirstPark() {
        val e = TripEngine(); val wk = CalendarInfo(false, false, 14)
        e.handle(tick(0, Gear.P, cal = wk)); e.handle(tick(1, Gear.D, cal = wk, home = 1.0))
        assertEquals(TripState.DAILY, e.state)
        e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST)))
        val out = (2L..6L).flatMap { e.handle(tick(it, Gear.P, cal = wk, home = 1.0, day = "d9")) }
        assertTrue(out.has(Action.AskBeforeDeparture::class.java))
    }
    private fun declinedRestStop(e: TripEngine) {
        e.handle(tick(0, Gear.P))
        e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST)))
        e.handle(Event.Answer(false)); e.handle(tick(1, Gear.D, home = 1.0))
        (2L..30L).forEach { e.handle(tick(it, Gear.D, home = 150.0)) }
        (31L..45L).forEach { e.handle(tick(it, Gear.P, home = 150.0)) }
    }
    @Test fun declineGuard_releasesOnNewDay() {
        val e = TripEngine(); declinedRestStop(e)
        e.handle(tick(2000, Gear.P, home = 150.0, day = "d2"))
        val ask = e.handle(Event.DestinationSet(Destination("속초", 200.0, PlaceKind.TOURIST)))
        assertTrue(ask.has(Action.AskBeforeDeparture::class.java))
    }
    @Test fun declineGuard_releasesWhenBackHome() {
        val e = TripEngine(); declinedRestStop(e)
        (46L..80L).forEach { e.handle(tick(it, Gear.D, home = 150.0 - (it - 45) * 4)) }
        (81L..85L).forEach { e.handle(tick(it, Gear.P, home = 2.0)) }
        // 같은 dayKey(d1)에서 사이클이 리셋됐는지: 새 출발이 거절 가드(DAILY 고정)에 막히지 않고 후보로 평가돼야 한다
        e.handle(Event.DestinationSet(Destination("속초", 200.0, PlaceKind.TOURIST)))
        e.handle(tick(86, Gear.D, home = 2.0))
        assertEquals(TripState.CANDIDATE, e.state)
    }
    @Test fun destinationSetMidTrip_clearedAfterTripCloses() {
        val e = TripEngine(); e.handle(tick(0, Gear.P))
        e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D, home = 10.0))
        e.handle(Event.DestinationSet(Destination("강릉 경포해변", 238.0, PlaceKind.TOURIST)))
        val late = CalendarInfo(false, false, 22)
        (2L..125L).forEach { e.handle(tick(it, Gear.P, home = 0.2, cal = late)) }
        assertEquals(TripState.ALBUM_READY, e.state)
        val out = e.handle(tick(2000, Gear.P, home = 0.2, day = "d2"))
        assertFalse(out.has(Action.AskBeforeDeparture::class.java))
        assertFalse(e.handle(tick(2001, Gear.P, home = 0.2, day = "d2")).has(Action.AskBeforeDeparture::class.java))
    }
}
