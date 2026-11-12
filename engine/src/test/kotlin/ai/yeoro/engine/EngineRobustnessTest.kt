package ai.yeoro.engine
import org.junit.Assert.*
import org.junit.Test

class EngineRobustnessTest {
    private val min = 60_000L
    private val family = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_RIGHT)
    private val three = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT)
    private val solo = setOf(Seat.DRIVER)
    private val sat = CalendarInfo(weekend = true, holiday = false, hour = 8)
    private val tue = CalendarInfo(weekend = false, holiday = false, hour = 7)
    private fun tick(t: Long, gear: Gear, seats: Set<Seat> = family, trunk: Boolean = false, home: Double? = 0.0, cal: CalendarInfo = sat, door: Boolean = false, day: String = "d1", newRoute: Boolean = false) =
        Event.Tick(Snapshot(t * min, gear, if (gear == Gear.D) 90f else 0f, seats, trunk, false, 0, door, home), cal, day, newRoute)
    private fun List<Action>.has(c: Class<*>) = any { c.isInstance(it) }
    private val gangneung = Destination("강릉 경포해변", 238.0, PlaceKind.TOURIST)

    private fun plannedAsk(e: TripEngine, t0: Long, day: String): List<Action> =
        e.handle(tick(t0, Gear.P, day = day)) + e.handle(tick(t0 + 1, Gear.P, trunk = true, day = day)) + e.handle(tick(t0 + 2, Gear.P, day = day)) +
        e.handle(tick(t0 + 3, Gear.P, trunk = true, day = day)) + e.handle(tick(t0 + 4, Gear.P, day = day)) +
        e.handle(Event.DestinationSet(gangneung))

    private fun soloCommute(e: TripEngine, t0: Long, day: String): List<Action> =
        e.handle(tick(t0, Gear.P, solo, cal = tue, day = day)) + (t0 + 1..t0 + 30).flatMap { e.handle(tick(it, Gear.D, solo, cal = tue, home = 5.0, day = day)) }

    /** B 시나리오처럼 즉흥 출발 → 86km에서 P 1시간 → 소급 질문 1회. 답하지 않은 상태로 반환. */
    private fun retroAsked(e: TripEngine): Action.AskRetroactive {
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true)); assertEquals(TripState.CANDIDATE, e.state)
        (4L..80L).forEach { e.handle(tick(it, Gear.D, three, home = 86.0, newRoute = true)) }
        return (81L..145L).flatMap { e.handle(tick(it, Gear.P, three, home = 86.0, newRoute = true)) }.filterIsInstance<Action.AskRetroactive>().single()
    }
    private fun driveHome(e: TripEngine, from: Long, to: Long, startKm: Double = 86.0): List<Action> {
        val n = (to - from).coerceAtLeast(1)
        return (from..to).flatMap { e.handle(tick(it, Gear.D, three, home = (startKm * (to - it) / n).coerceAtLeast(2.0), newRoute = true)) }
    }

    // 1. 출발 전 답변 만료
    @Test fun acceptedAsk_expiresWithNullDestinationAndNewDay() {
        val e = TripEngine()
        assertTrue(plannedAsk(e, 0, "d1").has(Action.AskBeforeDeparture::class.java)); e.handle(Event.Answer(true))
        e.handle(Event.DestinationSet(null))
        val out = soloCommute(e, 1000, "d2")
        assertFalse(out.has(Action.StartTrip::class.java)); assertEquals(TripState.DAILY, e.state)
    }
    @Test fun acceptedAsk_expiresOnNewDay() {
        val e = TripEngine()
        plannedAsk(e, 0, "d1"); e.handle(Event.Answer(true))
        val out = soloCommute(e, 1000, "d2")
        // 목적지(강릉)는 내비에 남아 있어 후보로 평가될 수 있지만, 전날의 수락으로 여행이 시작되면 안 된다
        assertFalse(out.has(Action.StartTrip::class.java)); assertNotEquals(TripState.TRIP, e.state)
    }
    @Test fun acceptedAsk_expiresWithNullDestination_sameDay() {
        val e = TripEngine()
        plannedAsk(e, 0, "d1"); e.handle(Event.Answer(true))
        e.handle(Event.DestinationSet(null))
        val out = e.handle(tick(10, Gear.D, home = 2.0))
        assertFalse(out.has(Action.StartTrip::class.java)); assertNotEquals(TripState.TRIP, e.state)
    }
    @Test fun declinedAsk_doesNotBlockNextDaysPlannedTrip() {
        val e = TripEngine()
        plannedAsk(e, 0, "d1"); e.handle(Event.Answer(false))
        assertTrue(plannedAsk(e, 2000, "d2").has(Action.AskBeforeDeparture::class.java))
    }

    // 2. 무시된 소급 질문
    @Test fun ignoredRetroAsk_reAsksWhenHome_thenAcceptStarts() {
        val e = TripEngine(); retroAsked(e)
        val home = driveHome(e, 146, 200) + (201L..215L).flatMap { e.handle(tick(it, Gear.P, three, home = 0.3, newRoute = true)) }
        assertTrue(home.has(Action.AskRetroactive::class.java)); assertFalse(home.has(Action.Discard::class.java))
        assertEquals(TripState.CANDIDATE, e.state)
        val acc = e.handle(Event.Answer(true))
        assertTrue(acc.any { it is Action.StartTrip && it.retroactive && it.sinceMs == 3 * min }); assertEquals(TripState.TRIP, e.state)
    }
    @Test fun retroReAsk_afterLeavingPark_onlyOncePerReturn_andMaxThree() {
        val e = TripEngine(); retroAsked(e)
        // 다른 곳 정차: 다시 차에 탔을 때 1회 재질문
        val stop2 = (146L..160L).flatMap { e.handle(tick(it, Gear.D, three, home = 100.0, newRoute = true)) } +
            (161L..200L).flatMap { e.handle(tick(it, Gear.P, three, home = 100.0, newRoute = true)) }
        assertEquals(1, stop2.filterIsInstance<Action.AskRetroactive>().size)
        // 세 번째 정차: 재질문은 사이클당 한 번이라 묻지 않음
        val stop3 = (201L..215L).flatMap { e.handle(tick(it, Gear.D, three, home = 100.0, newRoute = true)) } +
            (216L..230L).flatMap { e.handle(tick(it, Gear.P, three, home = 100.0, newRoute = true)) }
        assertFalse(stop3.has(Action.AskRetroactive::class.java))
        // 귀가: 세 번째(마지막) 질문
        val home = driveHome(e, 231, 300, 100.0) + (301L..315L).flatMap { e.handle(tick(it, Gear.P, three, home = 0.3, newRoute = true)) }
        assertEquals(1, home.filterIsInstance<Action.AskRetroactive>().size); assertFalse(home.has(Action.Discard::class.java))
        // 집 질문에 답 없이 30분 이상 지남(다음 날) → 확인하지 않은 여행으로 보관
        val next = e.handle(tick(2000, Gear.P, three, home = 0.3, day = "d2"))
        assertTrue(next.any { it is Action.KeepUnconfirmed && it.sinceMs == 3 * min })
        assertFalse(next.has(Action.Discard::class.java)); assertFalse(next.has(Action.AskRetroactive::class.java))
        assertEquals(TripState.DAILY, e.state)
    }
    @Test fun retroAskExhausted_atHome_keepsUnconfirmed() {
        val e = TripEngine(); retroAsked(e)
        // 재질문(2), 다음 날 재질문(3)을 쓰고, 귀가하면 남은 질문이 없으니 확인하지 않은 여행으로 보관
        (146L..160L).forEach { e.handle(tick(it, Gear.D, three, home = 100.0, newRoute = true)) }
        assertTrue(e.handle(tick(161, Gear.P, three, home = 100.0, newRoute = true)).has(Action.AskRetroactive::class.java))
        assertTrue(e.handle(tick(1500, Gear.P, three, home = 100.0, day = "d2")).has(Action.AskRetroactive::class.java))
        val home = (1501L..1560L).flatMap { e.handle(tick(it, Gear.D, three, home = 50.0, day = "d2")) } +
            (1561L..1570L).flatMap { e.handle(tick(it, Gear.P, three, home = 0.3, day = "d2")) }
        assertTrue(home.has(Action.KeepUnconfirmed::class.java)); assertFalse(home.has(Action.Discard::class.java)); assertFalse(home.has(Action.AskRetroactive::class.java))
        assertEquals(TripState.DAILY, e.state)
    }

    // 3. 대체 종료 경로
    @Test fun candidateWithoutAsk_survivesDayChangeInPark() {
        val e = TripEngine()
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true)); assertEquals(TripState.CANDIDATE, e.state)
        (4L..30L).forEach { e.handle(tick(it, Gear.D, three, home = 20.0, newRoute = true)) }
        (31L..40L).forEach { e.handle(tick(it, Gear.P, three, home = 20.0, newRoute = true)) }
        assertEquals(TripState.CANDIDATE, e.state)
        val out = e.handle(tick(41, Gear.P, three, home = 20.0, day = "d2"))
        assertFalse(out.has(Action.Discard::class.java)); assertEquals(TripState.CANDIDATE, e.state)
    }
    @Test fun candidateWithPendingAsk_asksAgainOnNextDay() {
        val e = TripEngine(); retroAsked(e)
        val out = e.handle(tick(1500, Gear.P, three, home = 86.0, day = "d2"))
        assertTrue(out.has(Action.AskRetroactive::class.java)); assertFalse(out.has(Action.Discard::class.java))
        assertEquals(TripState.CANDIDATE, e.state)
        assertFalse(e.handle(tick(1501, Gear.P, three, home = 86.0, day = "d2")).has(Action.AskRetroactive::class.java))
    }
    @Test fun tripOlderThanSevenDays_closes() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(gangneung)); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D, home = 10.0))
        assertEquals(TripState.TRIP, e.state)
        assertFalse(e.handle(tick(7 * 24 * 60L, Gear.P, home = 238.0, day = "d7")).has(Action.TripClosed::class.java))
        val out = e.handle(tick(7 * 24 * 60L + 2, Gear.P, home = 238.0, day = "d8"))
        val closed = out.filterIsInstance<Action.TripClosed>().single()
        assertNull(closed.notifyOnPhoneAtHour); assertEquals(TripState.ALBUM_READY, e.state)
    }

    // 4. 위치 모름
    @Test fun unknownLocation_candidateParkedDoesNotDiscard() {
        val e = TripEngine()
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        e.handle(tick(3, Gear.D, three, home = null, newRoute = true)); assertEquals(TripState.CANDIDATE, e.state)
        val out = (4L..34L).flatMap { e.handle(tick(it, Gear.P, three, home = null, newRoute = true)) }
        assertFalse(out.has(Action.Discard::class.java)); assertEquals(TripState.CANDIDATE, e.state)
    }
    @Test fun unknownLocation_tripNeitherStaysNorCloses() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(gangneung)); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D, home = 10.0))
        val night = CalendarInfo(true, false, 22)
        val out = (2L..300L).flatMap { e.handle(tick(it, Gear.P, home = null, cal = night)) }
        assertFalse(out.has(Action.TripClosed::class.java)); assertEquals(TripState.TRIP, e.state)
        assertTrue(out.filterIsInstance<Action.ScoreUpdated>().none { s -> s.score.signals.any { it.source == "위치" || it.source == "기어·위치" } })
    }

    // 5. 동의 철회
    @Test fun consentRevokedDuringTrip_closesTrip() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(gangneung)); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D, home = 10.0))
        e.handle(tick(5, Gear.D, home = 30.0))
        val out = e.handle(Event.ProfileChanged(false))
        assertEquals(listOf<Action>(Action.TripClosed(5 * min, null)), out); assertEquals(TripState.OFF, e.state)
    }
    @Test fun consentRevokedDuringCandidate_discards() {
        val e = TripEngine()
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true)); assertEquals(TripState.CANDIDATE, e.state)
        assertEquals(listOf<Action>(Action.Discard), e.handle(Event.ProfileChanged(false))); assertEquals(TripState.OFF, e.state)
    }

    // 6. 재시작 후 복원
    @Test fun restoreTrip_thenHomeParkCloses() {
        val e = TripEngine()
        e.handle(tick(0, Gear.P, home = 50.0))
        e.handle(Event.Restore(TripState.TRIP, -100 * min)); assertEquals(TripState.TRIP, e.state)
        (1L..10L).forEach { e.handle(tick(it, Gear.D, home = 20.0)) }
        val out = (11L..135L).flatMap { e.handle(tick(it, Gear.P, home = 0.2)) }
        assertTrue(out.has(Action.TripClosed::class.java)); assertEquals(TripState.ALBUM_READY, e.state)
    }
    @Test fun restoreStaying_resumesTripOnDeparture() {
        val e = TripEngine(); e.handle(tick(0, Gear.P, home = 200.0))
        e.handle(Event.Restore(TripState.STAYING, -600 * min)); assertEquals(TripState.STAYING, e.state)
        val out = e.handle(tick(1, Gear.D, home = 200.0))
        assertFalse(out.has(Action.StartTrip::class.java)); assertEquals(TripState.TRIP, e.state)
    }
    @Test fun restoreIgnoredWhenNotIdle() {
        val e = TripEngine()
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true)); assertEquals(TripState.CANDIDATE, e.state)
        e.handle(Event.Restore(TripState.TRIP, 0)); assertEquals(TripState.CANDIDATE, e.state)
        val d = TripEngine(); d.handle(Event.Restore(TripState.CANDIDATE, 0)); assertEquals(TripState.DAILY, d.state)
    }
    @Test fun restoreOldTrip_closesBySevenDayRule() {
        val e = TripEngine()
        e.handle(Event.Restore(TripState.TRIP, 0))
        val out = e.handle(tick(8 * 24 * 60L, Gear.P, home = 200.0, day = "d9"))
        assertTrue(out.has(Action.TripClosed::class.java))
    }

    // 7. 목적지 이름
    @Test fun startTrip_carriesDestinationName() {
        val e = TripEngine(); plannedAsk(e, 0, "d1"); e.handle(Event.Answer(true))
        val go = e.handle(tick(10, Gear.D, home = 2.0)).filterIsInstance<Action.StartTrip>().single()
        assertEquals("강릉 경포해변", go.destinationName)
    }

    // 8. 기어 UNKNOWN
    @Test fun unknownGear_isNotADeparture() {
        val e = TripEngine(); plannedAsk(e, 0, "d1"); e.handle(Event.Answer(true))
        val u = e.handle(tick(10, Gear.UNKNOWN, home = 0.0))
        assertFalse(u.has(Action.StartTrip::class.java)); assertEquals(TripState.DAILY, e.state)
        assertFalse(e.handle(tick(11, Gear.P, home = 0.0)).has(Action.StartTrip::class.java))
        e.handle(tick(12, Gear.UNKNOWN, home = 0.0))
        assertTrue(e.handle(tick(13, Gear.D, home = 1.0)).has(Action.StartTrip::class.java)); assertEquals(TripState.TRIP, e.state)
    }

    @Test fun yesterdaysTrunkOpens_doNotScoreToday() {
        val e = TripEngine()
        e.handle(tick(0, Gear.P, day = "d1")); e.handle(tick(1, Gear.P, trunk = true, day = "d1")); e.handle(tick(2, Gear.P, day = "d1"))
        e.handle(tick(3, Gear.P, trunk = true, day = "d1"))
        val d1 = e.handle(tick(4, Gear.P, day = "d1")).filterIsInstance<Action.ScoreUpdated>().single().score.total
        val d2 = e.handle(tick(2000, Gear.P, day = "d2")).filterIsInstance<Action.ScoreUpdated>().single().score.total
        assertTrue("d1=$d1 d2=$d2", d2 < d1)
    }

    // A. 후보 수명: 날이 바뀌어도 버리지 않고, 확인 못 한 여행은 보관
    @Test fun overnightCandidate_asksNextMorningAtLodging() {
        val e = TripEngine()
        assertTrue(plannedAsk(e, 0, "d1").has(Action.AskBeforeDeparture::class.java))   // 무시
        e.handle(tick(10, Gear.D, home = 2.0)); assertEquals(TripState.CANDIDATE, e.state)
        (11L..200L).forEach { e.handle(tick(it, Gear.D, home = 120.0)) }
        val night = CalendarInfo(true, false, 22)
        val d1 = (201L..600L).flatMap { e.handle(tick(it, Gear.P, home = 238.0, cal = night)) }
        assertFalse(d1.has(Action.AskRetroactive::class.java)); assertEquals(TripState.CANDIDATE, e.state)
        val morning = e.handle(tick(1500, Gear.P, home = 238.0, day = "d2"))
        assertTrue(morning.any { it is Action.AskRetroactive && it.sinceMs == 10 * min }); assertFalse(morning.has(Action.Discard::class.java))
        assertEquals(TripState.CANDIDATE, e.state)
    }
    @Test fun lateDeparture_restStopAfterMidnight_doesNotDiscard() {
        val e = TripEngine(); val late = CalendarInfo(true, false, 23); val night = CalendarInfo(true, false, 0)
        e.handle(tick(0, Gear.P, three, cal = late)); e.handle(tick(1, Gear.P, three, trunk = true, cal = late)); e.handle(tick(2, Gear.P, three, cal = late))
        e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true, cal = late)); assertEquals(TripState.CANDIDATE, e.state)
        (4L..40L).forEach { e.handle(tick(it, Gear.D, three, home = 60.0, newRoute = true, cal = late)) }
        val out = (41L..60L).flatMap { e.handle(tick(it, Gear.P, three, home = 60.0, newRoute = true, cal = night, day = "d2")) }
        assertFalse(out.has(Action.Discard::class.java)); assertEquals(TripState.CANDIDATE, e.state)
    }
    @Test fun homeAskIgnored_leavingPark_keepsUnconfirmed_thenNextDayAsks() {
        val e = TripEngine(); retroAsked(e)
        val home = driveHome(e, 146, 200) + (201L..215L).flatMap { e.handle(tick(it, Gear.P, three, home = 0.3, newRoute = true)) }
        assertTrue(home.has(Action.AskRetroactive::class.java)); assertFalse(home.has(Action.KeepUnconfirmed::class.java))
        val leave = e.handle(tick(216, Gear.D, three, home = 0.3))
        assertTrue(leave.any { it is Action.KeepUnconfirmed && it.sinceMs == 3 * min }); assertFalse(leave.has(Action.Discard::class.java))
        assertEquals(TripState.DAILY, e.state)
        assertTrue(plannedAsk(e, 2000, "d2").has(Action.AskBeforeDeparture::class.java))
    }
    @Test fun homeAskIgnored_thirtyMinutes_keepsUnconfirmed() {
        val e = TripEngine(); retroAsked(e)
        driveHome(e, 146, 200)
        val out = (201L..250L).map { e.handle(tick(it, Gear.P, three, home = 0.3, newRoute = true)) }
        val askAt = out.indexOfFirst { it.has(Action.AskRetroactive::class.java) }; val keepAt = out.indexOfFirst { it.has(Action.KeepUnconfirmed::class.java) }
        assertTrue("ask=$askAt keep=$keepAt", askAt >= 0 && keepAt - askAt == 30)
        assertFalse(out.flatten().has(Action.Discard::class.java)); assertEquals(TripState.DAILY, e.state)
    }
    @Test fun ordinaryDrive_neverConfident_discardsAtHome() {
        val e = TripEngine()
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true)); assertEquals(TripState.CANDIDATE, e.state)
        (4L..45L).forEach { e.handle(tick(it, Gear.D, three, home = 20.0, newRoute = true)) }
        val back = (46L..52L).flatMap { e.handle(tick(it, Gear.P, three, home = 0.5, newRoute = true)) }
        assertTrue(back.has(Action.Discard::class.java)); assertFalse(back.has(Action.KeepUnconfirmed::class.java)); assertFalse(back.has(Action.AskRetroactive::class.java))
        assertEquals(TripState.DAILY, e.state)
    }
    @Test fun dayChangeAsk_recordsProposal() {
        val e = TripEngine(); retroAsked(e)
        assertTrue(e.handle(tick(1500, Gear.P, three, home = 86.0, day = "d2")).has(Action.AskRetroactive::class.java))
        assertTrue(e.handle(Event.Answer(false)).has(Action.Discard::class.java))
        assertFalse(plannedAsk(e, 1600, "d2").has(Action.AskBeforeDeparture::class.java))
        assertTrue(plannedAsk(e, 3000, "d3").has(Action.AskBeforeDeparture::class.java))
    }

    // B. 복원 직후 집에 서 있으면 바로 닫는다
    @Test fun restore_firstTickParkedAtHome_closesImmediately() {
        val e = TripEngine(); e.handle(Event.Restore(TripState.TRIP, 0))
        val out = e.handle(tick(10, Gear.P, home = 0.2, cal = CalendarInfo(false, false, 23)))
        assertEquals(8, out.filterIsInstance<Action.TripClosed>().single().notifyOnPhoneAtHour); assertEquals(TripState.ALBUM_READY, e.state)
        val d = TripEngine(); d.handle(Event.Restore(TripState.STAYING, 0))
        assertNull(d.handle(tick(10, Gear.P, home = 0.8, cal = CalendarInfo(false, false, 14))).filterIsInstance<Action.TripClosed>().single().notifyOnPhoneAtHour)
        val f = TripEngine(); f.handle(Event.Restore(TripState.TRIP, 0))
        assertFalse(f.handle(tick(10, Gear.P, home = 5.0)).has(Action.TripClosed::class.java)); assertEquals(TripState.TRIP, f.state)
    }

    // C. 답의 종류가 대기 중인 질문과 다르면 무시
    @Test fun answerWithMismatchedKind_isIgnored() {
        val e = TripEngine(); retroAsked(e)
        assertTrue(e.handle(Event.Answer(true, "departure")).isEmpty()); assertEquals(TripState.CANDIDATE, e.state)
        assertTrue(e.handle(Event.Answer(true, "retro")).has(Action.StartTrip::class.java)); assertEquals(TripState.TRIP, e.state)
    }

    // E. 소소한 것들
    @Test fun consentRevokedAfterRestoreWithoutTicks_closesAtDeparture() {
        val e = TripEngine(); e.handle(Event.Restore(TripState.TRIP, 500 * min))
        assertEquals(listOf<Action>(Action.TripClosed(500 * min, null)), e.handle(Event.ProfileChanged(false)))
    }
    @Test fun singleUnknownLocationTick_doesNotResetHomeParkTimer() {
        val e = TripEngine()
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true)); assertEquals(TripState.CANDIDATE, e.state)
        (4L..6L).forEach { e.handle(tick(it, Gear.P, three, home = 0.5, newRoute = true)) }
        e.handle(tick(7, Gear.P, three, home = null, newRoute = true))
        val out = (8L..9L).flatMap { e.handle(tick(it, Gear.P, three, home = 0.5, newRoute = true)) }
        assertTrue(out.has(Action.Discard::class.java)); assertEquals(TripState.DAILY, e.state)
    }

    // final-fix-3
    // 1. 후보가 살아 있는 동안 출발 시각을 노출 (버퍼 정리 보호용)
    @Test fun candidateSinceMs_setOnlyInCandidate() {
        val e = TripEngine(); assertNull(e.candidateSinceMs)
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        assertNull(e.candidateSinceMs)
        e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true)); assertEquals(TripState.CANDIDATE, e.state)
        assertEquals(3 * min, e.candidateSinceMs)
        (4L..45L).forEach { e.handle(tick(it, Gear.D, three, home = 20.0, newRoute = true)) }
        (46L..52L).forEach { e.handle(tick(it, Gear.P, three, home = 0.5, newRoute = true)) }
        assertEquals(TripState.DAILY, e.state); assertNull(e.candidateSinceMs)
        val t = TripEngine(); t.handle(tick(0, Gear.P)); t.handle(Event.DestinationSet(gangneung)); t.handle(Event.Answer(true)); t.handle(tick(1, Gear.D, home = 10.0))
        assertEquals(TripState.TRIP, t.state); assertNull(t.candidateSinceMs)
    }

    // 2. 복원 확인은 위치를 알 때까지 기다린다
    @Test fun restoreCheck_waitsForKnownLocation() {
        val e = TripEngine(); e.handle(Event.Restore(TripState.TRIP, 0))
        val unknown = (10L..12L).flatMap { e.handle(tick(it, Gear.P, home = null)) }
        assertFalse(unknown.has(Action.TripClosed::class.java)); assertEquals(TripState.TRIP, e.state)
        assertTrue(e.handle(tick(13, Gear.P, home = 0.3)).has(Action.TripClosed::class.java)); assertEquals(TripState.ALBUM_READY, e.state)
    }
    @Test fun restoreCheck_clearedWhenLeavingPark() {
        val e = TripEngine(); e.handle(Event.Restore(TripState.TRIP, 0))
        e.handle(tick(10, Gear.P, home = null)); e.handle(tick(11, Gear.D, home = null))
        assertFalse(e.handle(tick(12, Gear.P, home = 0.3)).has(Action.TripClosed::class.java)); assertEquals(TripState.TRIP, e.state)
    }

    // 3. 확신한 적도, 물은 적도 없는 후보는 24시간 뒤 폐기 (위치와 무관)
    @Test fun neverConfidentCandidate_discardedAfter24h() {
        val e = TripEngine()
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true)); assertEquals(TripState.CANDIDATE, e.state)
        (4L..20L).forEach { e.handle(tick(it, Gear.D, three, home = 2.0, newRoute = true)) }
        val before = (21L..(3 + 24 * 60L) step 60).flatMap { e.handle(tick(it, Gear.P, three, home = 2.0, day = if (it < 24 * 60) "d1" else "d2")) }
        assertFalse(before.has(Action.Discard::class.java)); assertFalse(before.has(Action.AskRetroactive::class.java)); assertEquals(TripState.CANDIDATE, e.state)
        val after = e.handle(tick(3 + 25 * 60L, Gear.P, three, home = 2.0, day = "d2"))
        assertTrue(after.has(Action.Discard::class.java)); assertEquals(TripState.DAILY, e.state); assertNull(e.candidateSinceMs)
    }

    // KeepUnconfirmed는 틱 시각을 끝 시각으로 넘긴다
    @Test fun keepUnconfirmed_carriesTickTimeAsEnd() {
        val e = TripEngine(); retroAsked(e)
        driveHome(e, 146, 200); (201L..215L).forEach { e.handle(tick(it, Gear.P, three, home = 0.3, newRoute = true)) }
        val keep = e.handle(tick(216, Gear.D, three, home = 0.3)).filterIsInstance<Action.KeepUnconfirmed>().single()
        assertEquals(3 * min, keep.sinceMs); assertEquals(216 * min, keep.endMs)
    }
}
