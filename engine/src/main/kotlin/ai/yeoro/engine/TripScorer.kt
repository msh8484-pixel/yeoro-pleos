package ai.yeoro.engine

data class ScoreInput(
    val destination: Destination?, val seats: Set<Seat>, val trunkOpens: Int, val calendar: CalendarInfo,
    val newRoute: Boolean, val homeDistanceKm: Double?, val parkedAwayMin: Int,
)
data class ScoredSignal(val label: String, val points: Int, val source: String)
enum class Band { CONFIDENT, CANDIDATE, DAILY }

data class ScoreResult(val signals: List<ScoredSignal>, val thresholdBoost: Int = 0) {
    val total: Int get() = signals.sumOf { it.points }.coerceIn(0, 100)
    val band: Band get() = when {
        total >= 70 + thresholdBoost -> Band.CONFIDENT
        total >= 40 -> Band.CANDIDATE
        else -> Band.DAILY
    }
}

object TripScorer {
    fun score(input: ScoreInput, thresholdBoost: Int = 0): ScoreResult {
        val s = mutableListOf<ScoredSignal>()
        input.destination?.let { d ->
            val km = d.distanceKm.toInt()
            when {
                d.distanceKm >= 150 -> s += ScoredSignal("목적지 ${km}km", 35, "내비")
                d.distanceKm >= 50 -> s += ScoredSignal("목적지 ${km}km", 25, "내비")
            }
            when (d.kind) {
                PlaceKind.TOURIST, PlaceKind.LODGING, PlaceKind.CAMPING -> s += ScoredSignal("목적지 관광·숙박지", 20, "TourAPI")
                PlaceKind.WORK -> s += ScoredSignal("목적지 업무 장소", -20, "TourAPI")
                PlaceKind.OTHER -> Unit
            }
            if (d.frequent) s += ScoredSignal("자주 가는 곳", -40, "여로 기록")
            if (d.regular) s += ScoredSignal("정기 방문 장소", 30, "여로 기록")
        }
        val rear = input.seats.count { it.rear }
        when {
            rear >= 2 -> s += ScoredSignal("뒷좌석 ${rear}명", 20, "좌석")
            rear == 1 -> s += ScoredSignal("뒷좌석 1명", 10, "좌석")
        }
        if (input.seats.size <= 1) s += ScoredSignal("혼자 탑승", -15, "좌석")
        if (input.trunkOpens > 0) s += ScoredSignal("출발 전 트렁크 ${input.trunkOpens}회", 15, "트렁크")
        when {
            input.calendar.holiday -> s += ScoredSignal("연휴", 15, "천문연")
            input.calendar.weekend -> s += ScoredSignal("주말", 10, "천문연")
        }
        if (input.calendar.commuteHour) s += ScoredSignal("평일 출퇴근 시간", -10, "시계")
        if (input.newRoute) s += ScoredSignal("처음 가는 방향", 10, "여로 기록")
        input.homeDistanceKm?.let { if (it >= 80) s += ScoredSignal("집에서 ${it.toInt()}km", 15, "위치") }
        if (input.parkedAwayMin >= 60) s += ScoredSignal("낯선 곳 P단 ${input.parkedAwayMin / 60}시간", 20, "기어·위치")
        return ScoreResult(s, thresholdBoost)
    }
}
