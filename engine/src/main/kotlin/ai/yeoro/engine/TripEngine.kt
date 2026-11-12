package ai.yeoro.engine

enum class TripState { OFF, DAILY, CANDIDATE, TRIP, STAYING, ALBUM_READY }

sealed interface Event {
    data class ProfileChanged(val consented: Boolean) : Event
    data class DestinationSet(val destination: Destination?) : Event
    data class Tick(val snapshot: Snapshot, val calendar: CalendarInfo, val dayKey: String, val newRoute: Boolean = false) : Event
    data class Answer(val accepted: Boolean, val kind: String? = null) : Event   // kind: "departure" | "retro" | null(무관)
    data class Restore(val state: TripState, val sinceMs: Long) : Event
}

sealed interface Action {
    data class AskBeforeDeparture(val score: ScoreResult) : Action
    data class AskRetroactive(val score: ScoreResult, val sinceMs: Long) : Action
    data class StartTrip(val sinceMs: Long, val retroactive: Boolean, val destinationName: String?) : Action
    data class RecordMoment(val moment: Moment) : Action
    data class TripClosed(val endMs: Long, val notifyOnPhoneAtHour: Int?) : Action
    data object Discard : Action
    /** 확인받지 못한 여행: sinceMs 이후 버퍼를 "UNCONFIRMED" 여행으로 보관 */
    data class KeepUnconfirmed(val sinceMs: Long, val endMs: Long) : Action
    data class ScoreUpdated(val score: ScoreResult, val state: TripState) : Action
}

class TripEngine(private val policy: NotificationPolicy = NotificationPolicy()) {
    private val min = 60_000L
    private val maxTripMs = 7 * 24 * 60 * min
    private val idleCandidateMs = 24 * 60 * min
    private val maxRetroAsks = 3
    var state: TripState = TripState.DAILY; private set
    /** CANDIDATE 동안의 출발 시각 (그 이후 버퍼는 정리하면 안 된다). 아니면 null */
    val candidateSinceMs: Long? get() = if (state == TripState.CANDIDATE) departedAt else null
    private var consented = true
    private var destination: Destination? = null
    private var trunkOpens = 0
    private var lastTrunk = false
    private var trunkDayKey: String? = null      // trunkOpens가 집계된 날
    private var pending: String? = null          // "departure" | "retro"
    private var accepted: Boolean? = null
    private var askDayKey: String? = null        // 출발 전 질문·답변이 있었던 날 (다른 날이면 만료)
    private var departedAt: Long? = null
    private var departedDayKey: String? = null   // null = 모름 (Restore)
    private var destinationSetMs = 0L
    private var awayParkSince: Long? = null
    private var homeParkSince: Long? = null
    private var homeArrivalHour = 0
    private var closedDayKey: String? = null
    private var last: Event.Tick? = null
    private var lastKnownGear: Gear? = null      // UNKNOWN을 건너뛴 마지막 기어
    // 소급 질문 (사이클 단위)
    private var retroAsks = 0
    private var retroReAsked = false             // 다시 차에 탔을 때의 재질문 (사이클당 1회)
    private var retroHomeAsked = false           // 집 도착 재질문 (사이클당 1회)
    private var retroAskedThisPark = false
    private var lastRetroAskDayKey: String? = null
    private var lastRetroAskMs = 0L
    private var homeAskAt: Long? = null          // 집 질문 시각 (답이 없으면 출발·30분 후 보관)
    private var everConfident = false            // 이번 사이클에 후보가 CONFIDENT에 닿은 적 있음
    private var restoreCheck = false             // 복원 직후: 위치를 아는 첫 P 틱에서 집이면 바로 닫는다
    private val detector = MomentDetector()

    private fun resetCycle(keepInCycleDestination: Boolean = false) {
        if (!keepInCycleDestination || departedAt == null || destinationSetMs < departedAt!!) destination = null
        departedAt = null; departedDayKey = null; accepted = null; pending = null; askDayKey = null; trunkOpens = 0
        awayParkSince = null; homeParkSince = null
        retroAsks = 0; retroReAsked = false; retroHomeAsked = false; retroAskedThisPark = false; lastRetroAskDayKey = null
        homeAskAt = null; everConfident = false
        detector.reset()
    }

    private fun releaseAlbum() { if (state == TripState.ALBUM_READY) { state = TripState.DAILY; resetCycle() } }

    /** 출발 전 질문의 답(또는 대기 중인 질문)을 버린다. */
    private fun expireDepartureAnswer() { accepted = null; if (pending == "departure") pending = null; askDayKey = null }

    fun handle(e: Event): List<Action> {
        if (e is Event.ProfileChanged) {
            val wasOff = state == TripState.OFF
            consented = e.consented
            val out = mutableListOf<Action>()
            if (!consented) {
                when (state) {
                    TripState.TRIP, TripState.STAYING -> out += Action.TripClosed(last?.snapshot?.timeMs ?: departedAt ?: 0L, null)
                    TripState.CANDIDATE -> out += Action.Discard
                    else -> Unit
                }
                state = TripState.OFF; resetCycle()
            }
            else if (wasOff) { state = TripState.DAILY; resetCycle(); lastTrunk = last?.snapshot?.trunkOpen ?: false; last = null; lastKnownGear = null }
            return out
        }
        if (!consented) return emptyList()
        return when (e) {
            is Event.DestinationSet -> {
                releaseAlbum()
                if (e.destination == null && departedAt == null) expireDepartureAnswer()
                destination = e.destination; destinationSetMs = last?.snapshot?.timeMs ?: 0L
                last?.let { maybeAskBeforeDeparture(it) } ?: emptyList()
            }
            is Event.Answer -> answer(e.accepted, e.kind)
            is Event.Tick -> tick(e)
            is Event.Restore -> { restore(e); emptyList() }
            is Event.ProfileChanged -> emptyList()
        }
    }

    private fun restore(e: Event.Restore) {
        if (state != TripState.DAILY || departedAt != null) return
        if (e.state != TripState.TRIP && e.state != TripState.STAYING) return
        state = e.state; departedAt = e.sinceMs; departedDayKey = null
        pending = null; accepted = null; askDayKey = null
        detector.reset(); restoreCheck = true
    }

    private fun scoreOf(t: Event.Tick, kind: String): ScoreResult {
        val s = t.snapshot
        val parkedAway = awayParkSince?.let { ((s.timeMs - it) / min).toInt() } ?: 0
        return TripScorer.score(ScoreInput(destination, s.seats, trunkOpens, t.calendar, t.newRoute, s.homeDistanceKm, parkedAway), policy.boostFor(kind))
    }

    private fun maybeAskBeforeDeparture(t: Event.Tick): List<Action> {
        if (departedAt != null || pending != null || accepted != null) return emptyList()
        val sc = scoreOf(t, "departure")
        if (sc.band == Band.CONFIDENT && policy.canPropose(t.dayKey, t.snapshot.gear)) {
            policy.recordProposal(t.dayKey); pending = "departure"; askDayKey = t.dayKey; return listOf(Action.AskBeforeDeparture(sc))
        }
        return emptyList()
    }

    private fun retroAsk(t: Event.Tick): Action {
        retroAsks++; retroAskedThisPark = true; lastRetroAskDayKey = t.dayKey; lastRetroAskMs = t.snapshot.timeMs; pending = "retro"
        return Action.AskRetroactive(scoreOf(t, "retro"), departedAt ?: t.snapshot.timeMs)
    }

    private fun discardCandidate(out: MutableList<Action>) { out += Action.Discard; state = TripState.DAILY; resetCycle() }
    private fun keepUnconfirmed(out: MutableList<Action>, now: Long) { out += Action.KeepUnconfirmed(departedAt ?: now, now); state = TripState.DAILY; resetCycle() }
    private fun closeTrip(out: MutableList<Action>, t: Event.Tick, arrivalHour: Int?) {
        state = TripState.ALBUM_READY; closedDayKey = t.dayKey
        out += Action.TripClosed(t.snapshot.timeMs, arrivalHour?.let { if (it >= 22 || it < 6) 8 else null })
    }

    private fun answer(yes: Boolean, answeredKind: String?): List<Action> {
        val kind = pending ?: return emptyList()
        if (answeredKind != null && answeredKind != kind) return emptyList()   // 다른 질문에 대한 늦은 답
        pending = null
        if (!yes) policy.recordDecline(kind)
        return when (kind) {
            "departure" -> { accepted = yes; askDayKey = last?.dayKey ?: askDayKey; emptyList() }
            else -> if (yes) { state = TripState.TRIP; listOf(Action.StartTrip(departedAt ?: 0L, retroactive = true, destinationName = destination?.name)) }
                    else { state = TripState.DAILY; resetCycle(); listOf(Action.Discard) }
        }
    }

    private fun tick(t: Event.Tick): List<Action> {
        val out = mutableListOf<Action>()
        val s = t.snapshot; val prevGear = lastKnownGear; last = t
        if (s.gear != Gear.UNKNOWN) lastKnownGear = s.gear
        val home = s.homeDistanceKm   // null = 위치 모름: 집·원거리 주차 규칙을 건너뛴다
        val atHome = home != null && home <= 1
        // 복원 직후 집에 서 있으면 (귀가를 못 본 재시작) 바로 닫는다. 위치를 모르면 알 때까지 기다리고, 차가 P를 벗어나면 그만둔다
        if (restoreCheck) {
            if (state != TripState.TRIP && state != TripState.STAYING) restoreCheck = false
            else if (s.gear == Gear.P && home != null) {
                restoreCheck = false
                if (atHome) { closeTrip(out, t, t.calendar.hour); out += Action.ScoreUpdated(scoreOf(t, "retro"), state); return out }
            } else if (s.gear != Gear.P && s.gear != Gear.UNKNOWN) restoreCheck = false
        }
        // 출발 또는 숙박 후 재출발 (UNKNOWN 기어는 출발이 아님)
        val leavingPark = prevGear == Gear.P && s.gear != Gear.P && s.gear != Gear.UNKNOWN
        val enteringPark = prevGear != null && prevGear != Gear.P && s.gear == Gear.P
        // 집 질문에 답이 없음: 차가 P를 벗어나거나 30분이 지나면 확인하지 않은 여행으로 보관
        if (state == TripState.CANDIDATE && pending == "retro" && homeAskAt?.let { leavingPark || s.timeMs - it >= 30 * min } == true) keepUnconfirmed(out, s.timeMs)
        // 사이클 재무장: 앨범 확인 후, 또는 일상 운행 종료 후
        if (state == TripState.ALBUM_READY && (s.gear != Gear.P || t.dayKey != closedDayKey)) releaseAlbum()
        else if (state == TripState.DAILY && departedAt != null && s.gear == Gear.P && !(accepted == false && (home == null || home > 3) && t.dayKey == departedDayKey)) resetCycle(keepInCycleDestination = true)  // 거절한 여행은 멀리서(또는 위치 모를 때) 쉬어도 되살리지 않음
        // 출발 전 답변은 그날만 유효
        if (departedAt == null && (accepted != null || pending == "departure") && t.dayKey != askDayKey) expireDepartureAnswer()
        // 날이 바뀌면 어제의 트렁크 열림은 오늘 점수에 쓰지 않는다 (출발 전에만)
        if (departedAt == null && trunkDayKey != null && trunkDayKey != t.dayKey) { trunkOpens = 0; lastTrunk = s.trunkOpen }
        trunkDayKey = t.dayKey
        // 출발 전 트렁크 (P단에서만)
        if (departedAt == null && s.gear == Gear.P && s.trunkOpen && !lastTrunk) trunkOpens++
        lastTrunk = s.trunkOpen
        if (departedAt == null && s.gear == Gear.P) out += maybeAskBeforeDeparture(t)
        if (leavingPark) retroAskedThisPark = false
        if (leavingPark && departedAt == null) {
            departedAt = s.timeMs; departedDayKey = t.dayKey
            val sc = scoreOf(t, "departure")
            state = when {
                accepted == true -> TripState.TRIP.also { out += Action.StartTrip(s.timeMs, false, destination?.name) }
                accepted == false -> TripState.DAILY
                sc.band != Band.DAILY -> TripState.CANDIDATE
                else -> TripState.DAILY
            }
            pending = null
            if (state == TripState.TRIP || state == TripState.CANDIDATE) detector.reset()
        } else if (leavingPark && state == TripState.STAYING) state = TripState.TRIP
        // P단 시간 계산 (P에서 위치를 한 번 놓치면 타이머를 그대로 둔다)
        if (s.gear != Gear.P) { awayParkSince = null; homeParkSince = null }
        else if (home != null) {
            if (home > 3) { if (awayParkSince == null) awayParkSince = s.timeMs } else awayParkSince = null
            if (home <= 1) { if (homeParkSince == null) { homeParkSince = s.timeMs; homeArrivalHour = t.calendar.hour } } else homeParkSince = null
        }
        if (state == TripState.CANDIDATE && !everConfident && scoreOf(t, "retro").band == Band.CONFIDENT) everConfident = true
        // 답 없는 소급 질문: 다시 차에 타서 집이 아닌 곳의 P에 서면 한 번 더 묻는다 (하루 1회 제한 무시)
        if (state == TripState.CANDIDATE && pending == "retro" && enteringPark && !atHome && !retroReAsked && retroAsks < maxRetroAsks) {
            retroReAsked = true; out += retroAsk(t)
        }
        // 후보 상태로 집에 5분 주차: 확신한 적 없는 일상 운행은 폐기, 아니면 한 번 더 묻고(제한 무시), 질문을 다 썼으면 보관
        if (state == TripState.CANDIDATE && s.gear == Gear.P && homeParkSince?.let { s.timeMs - it >= 5 * min } == true && !retroHomeAsked) {
            when {
                !everConfident && retroAsks == 0 -> discardCandidate(out)
                retroAskedThisPark -> { retroHomeAsked = true; homeAskAt = lastRetroAskMs }
                retroAsks < maxRetroAsks -> { retroHomeAsked = true; out += retroAsk(t); homeAskAt = s.timeMs }
                else -> keepUnconfirmed(out, s.timeMs)
            }
        }
        // 순간
        if (state == TripState.TRIP || state == TripState.STAYING || state == TripState.CANDIDATE) detector.feed(s).forEach { out += Action.RecordMoment(it) }
        // 후보 → 소급 질문
        val sc = scoreOf(t, if (departedAt == null) "departure" else "retro")
        // 첫 질문, 또는 답 없는 질문이 다른 날로 넘어가면 그날의 제안으로 한 번 더 (집에서는 집 규칙을 따른다)
        val askable = pending == null || (pending == "retro" && !retroHomeAsked && lastRetroAskDayKey != t.dayKey)
        if (state == TripState.CANDIDATE && askable && !atHome && retroAsks < maxRetroAsks && sc.band == Band.CONFIDENT && policy.canPropose(t.dayKey, s.gear)) {
            policy.recordProposal(t.dayKey); out += retroAsk(t)
        }
        // 숙박
        val night = t.calendar.hour >= 20 || t.calendar.hour < 6
        if (state == TripState.TRIP && night && awayParkSince?.let { s.timeMs - it >= 240 * min } == true) state = TripState.STAYING
        // 귀가
        if ((state == TripState.TRIP || state == TripState.STAYING) && homeParkSince?.let { s.timeMs - it >= 120 * min } == true) {
            closeTrip(out, t, homeArrivalHour)
        }
        // 7일 넘은 여행은 닫는다 (귀가를 못 본 경우의 대체 경로)
        if ((state == TripState.TRIP || state == TripState.STAYING) && departedAt?.let { s.timeMs - it > maxTripMs } == true) {
            closeTrip(out, t, null)
        }
        // 7일 넘은 후보도 끝낸다 (집에 돌아오지 않는 경우)
        if (state == TripState.CANDIDATE && departedAt?.let { s.timeMs - it > maxTripMs } == true) { if (everConfident) keepUnconfirmed(out, s.timeMs) else discardCandidate(out) }
        // 확신한 적도, 물은 적도 없는 후보가 24시간 넘게 남아 있으면 폐기 (위치와 무관)
        if (state == TripState.CANDIDATE && !everConfident && retroAsks == 0 && departedAt?.let { s.timeMs - it > idleCandidateMs } == true) discardCandidate(out)
        out += Action.ScoreUpdated(sc, state)
        return out
    }
}
