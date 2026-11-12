package ai.yeoro.engine

enum class MomentType { RAIN_START, RAIN_STOP, WINDOWS_OPEN, STOP, COMPANION_JOINED }
data class Moment(val type: MomentType, val timeMs: Long, val detail: String = "")

class MomentDetector {
    private val min = 60_000L
    private var wiperOnSince: Long? = null
    private var wiperOffSince: Long? = null
    private var dryBeforeOnMs = Long.MAX_VALUE
    private var raining = false
    private var lastWindows = 0
    private var parkedSince: Long? = null
    private var doorOpenedWhileParked = false
    private var seatsBeforeStop = 0
    private var started = false

    fun reset() { wiperOnSince = null; wiperOffSince = null; dryBeforeOnMs = Long.MAX_VALUE; raining = false; lastWindows = 0; parkedSince = null; doorOpenedWhileParked = false; seatsBeforeStop = 0; started = false }

    fun feed(s: Snapshot): List<Moment> {
        val out = mutableListOf<Moment>()
        if (!started) { started = true; wiperOffSince = Long.MIN_VALUE / 2; lastWindows = s.openWindows; seatsBeforeStop = s.seats.size }
        // 비
        if (s.wiperOn) {
            if (wiperOnSince == null) { dryBeforeOnMs = wiperOffSince?.let { s.timeMs - it } ?: 0L; wiperOnSince = s.timeMs; wiperOffSince = null }
            if (!raining && dryBeforeOnMs >= 30 * min && s.timeMs - wiperOnSince!! >= 3 * min) { raining = true; out += Moment(MomentType.RAIN_START, s.timeMs) }
        } else {
            if (wiperOffSince == null) { wiperOffSince = s.timeMs; wiperOnSince = null }
            if (raining && s.timeMs - wiperOffSince!! >= 10 * min) { raining = false; out += Moment(MomentType.RAIN_STOP, s.timeMs) }
        }
        // 창문
        if (lastWindows < 3 && s.openWindows >= 3) out += Moment(MomentType.WINDOWS_OPEN, s.timeMs, s.openWindows.toString())
        lastWindows = s.openWindows
        // 정차와 동행자
        if (s.gear == Gear.P) {
            if (parkedSince == null) { parkedSince = s.timeMs; doorOpenedWhileParked = false }
            if (s.doorOpen) doorOpenedWhileParked = true
        } else if (parkedSince != null) {
            val mins = ((s.timeMs - parkedSince!!) / min).toInt()
            if (doorOpenedWhileParked && mins >= 10) {
                out += Moment(MomentType.STOP, parkedSince!!, mins.toString())
                if (seatsBeforeStop in 1 until s.seats.size) out += Moment(MomentType.COMPANION_JOINED, s.timeMs, "${seatsBeforeStop}→${s.seats.size}")
            }
            parkedSince = null
        }
        if (s.gear != Gear.P) seatsBeforeStop = s.seats.size
        return out
    }
}
