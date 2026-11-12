package ai.yeoro.debug

import ai.yeoro.EngineBus
import ai.yeoro.engine.Seat
import ai.yeoro.engine.TripState
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 디버그 전용 시연 주행 (scripts/scenario_demo.sh). 에뮬레이터 위치는 고정이라 시연 경로를 위치 신호로 흘려 넣고,
 * 엔진·기록 시계를 앞으로 돌려 몇 시간짜리 여행을 1~2분에 재연한다. DB에 직접 쓰지 않는다:
 * 위치는 RecorderService.tick이 내비 위치 대신 읽고(같은 위치 샘플·순간 좌표 경로), 시각은 EngineBus.nowMs가 보정한다.
 *
 * - demo_route "a-b": SeedTrip.ROUTE의 a..b번 지점을 1.2초 간격으로 위치로 내보내며, 지점 사이 시각 차만큼 시계를 앞당긴다.
 * - demo_loc "lat,lon" | "clear": 위치 재정의
 * - demo_clock "HH:MM": 시계를 다가오는 토요일(오늘이 토요일이면 오늘) HH:MM(기기 시간대)으로 맞춘다. 날짜·주말 신호·앨범 날짜가 모두 같은 시계를 따른다. 발표 시각과 관계없이 같은 앨범(토요일 08:52 출발 → 13:20 도착)이 나오게
 * - skip_min N: 시계를 N분 앞당긴다 (정차·도착 대기)
 * - close_trip: 위치는 그대로 두고 엔진에 "집에 서 있음"(집까지 0km)을 알린 뒤 시계를 121분 앞당긴다.
 *   엔진의 귀가 규칙(집 P단 120분)이 그대로 여행을 닫는다. 닫히면(또는 15초 뒤) 재정의를 거둔다.
 * - outside_time "real" | "demo": 시계 보정이 있을 때 공공데이터의 날씨를 묻는 시각을 실제 지금으로(real) 바꾼다. 기본 demo(보정된 시각 그대로).
 *   시연 날짜(다가오는 토요일)는 기상청 창(실황 24시간·단기예보 그제~4일 앞) 밖일 수 있어서다. 일몰·축제·특일은 시연 날짜 그대로 묻는다.
 * 시계 보정과 위치는 앱을 다시 시작해도(force-stop 뒤 재기동) 이어지도록 저장한다. reset_policy가 모두 지운다.
 */
object DemoDrive {
    private const val TAG = "YeoroEngine"
    private const val PREFS = "yeoro_debug"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playing: Job? = null
    @Volatile private var outsideReal = false

    /** 날씨를 물을 시각 (CloudSync.weatherAt). 시계 보정이 없으면 늘 그대로 */
    fun weatherAt(atMs: Long): Long = if (outsideReal && EngineBus.clockOffsetMs != 0L) System.currentTimeMillis() else atMs

    fun setOutsideTime(c: Context, v: String): Boolean {
        if (v != "real" && v != "demo") return false
        outsideReal = v == "real"
        prefs(c).edit().putBoolean("outside_real", outsideReal).apply()
        return true
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 서비스 시작 때: 저장된 시계 보정·위치를 되살린다 */
    fun restore(c: Context) {
        val p = prefs(c)
        EngineBus.clockOffsetMs = p.getLong("clock_offset", 0L)
        if (EngineBus.clockOffsetMs != 0L) Log.w(TAG, "debug clock offset ACTIVE: ${EngineBus.clockOffsetMs / 60_000} min (reset_policy로 해제)")
        val lat = p.getString("loc_lat", null)?.toDoubleOrNull(); val lon = p.getString("loc_lon", null)?.toDoubleOrNull()
        EngineBus.debugLocation.value = if (lat != null && lon != null) lat to lon else null
        EngineBus.debugSeats.value = parseSeats(p.getString("seat_override", "").orEmpty())
        outsideReal = p.getBoolean("outside_real", false)
        if (EngineBus.clockOffsetMs != 0L || lat != null) Log.i(TAG, "debug demo restored offsetMin=${EngineBus.clockOffsetMs / 60_000} loc=${EngineBus.debugLocation.value}")
    }

    fun reset(c: Context) {
        playing?.cancel()
        EngineBus.clockOffsetMs = 0L; outsideReal = false; EngineBus.debugLocation.value = null; EngineBus.debugHomeKm.value = null; EngineBus.debugSeats.value = emptyMap()
        prefs(c).edit().clear().apply()
    }

    private val SeatNames = mapOf("driver" to Seat.DRIVER, "passenger" to Seat.PASSENGER, "rear_left" to Seat.REAR_LEFT, "rear_center" to Seat.REAR_CENTER, "rear_right" to Seat.REAR_RIGHT)
    private fun parseSeats(s: String): Map<Seat, Boolean> = s.split(";").mapNotNull { e ->
        val (n, v) = e.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
        SeatNames[n]?.let { it to (v == "on") }
    }.toMap()

    /** "rear_center:off|on|auto" — 좌석 재정의를 바꾸고 저장한다. 모르는 값이면 false */
    fun setSeat(c: Context, spec: String): Boolean {
        val (n, v) = spec.split(":").takeIf { it.size == 2 } ?: return false
        val seat = SeatNames[n] ?: return false
        val cur = EngineBus.debugSeats.value
        val next = when (v) { "on" -> cur + (seat to true); "off" -> cur + (seat to false); "auto" -> cur - seat; else -> return false }
        EngineBus.debugSeats.value = next
        prefs(c).edit().putString("seat_override", next.entries.joinToString(";") { (s, on) -> "${SeatNames.entries.first { it.value == s }.key}:${if (on) "on" else "off"}" }).apply()
        return true
    }

    fun skip(c: Context, minutes: Long) {
        EngineBus.clockOffsetMs += minutes * 60_000L
        prefs(c).edit().putLong("clock_offset", EngineBus.clockOffsetMs).apply()
        Log.i(TAG, "debug skip_min=$minutes offsetMin=${EngineBus.clockOffsetMs / 60_000}")
    }

    fun setClock(c: Context, hhmm: String) {
        val t = runCatching { java.time.LocalTime.parse(hhmm) }.getOrNull() ?: run { Log.w(TAG, "debug demo_clock=$hhmm 모름 (HH:MM)"); return }
        val sat = java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY))
        val target = sat.atTime(t).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        EngineBus.clockOffsetMs = target - System.currentTimeMillis()
        prefs(c).edit().putLong("clock_offset", EngineBus.clockOffsetMs).apply()
        Log.i(TAG, "debug demo_clock=$hhmm offsetMin=${EngineBus.clockOffsetMs / 60_000}")
    }

    fun setLocation(c: Context, loc: Pair<Double, Double>?) {
        EngineBus.debugLocation.value = loc
        prefs(c).edit().apply { if (loc == null) { remove("loc_lat"); remove("loc_lon") } else { putString("loc_lat", loc.first.toString()); putString("loc_lon", loc.second.toString()) } }.apply()
    }

    fun play(c: Context, spec: String) {
        val r = Regex("(\\d+)-(\\d+)").matchEntire(spec)?.destructured?.let { (a, b) -> a.toInt()..b.toInt() }
        val route = SeedTrip.ROUTE
        if (r == null || r.first < 0 || r.last > route.lastIndex || r.first > r.last) { Log.w(TAG, "debug demo_route=$spec 모름 (0-${route.lastIndex})"); return }
        playing?.cancel()
        playing = scope.launch {
            for (i in r) {
                val wp = route[i]
                if (i > 0) {
                    val prev = route[i - 1]
                    val dMin = (wp.h * 60 + wp.m) - (prev.h * 60 + prev.m)
                    if (dMin > 0) skip(c, dMin.toLong())
                }
                setLocation(c, wp.lat to wp.lon)
                Log.i(TAG, "debug demo_route point=$i ${"%02d:%02d".format(wp.h, wp.m)} ${wp.lat},${wp.lon}")
                delay(1_200)   // 틱(1초)이 지점마다 한 번 이상 보게
            }
            Log.i(TAG, "debug demo_route done $spec")
        }
    }

    fun closeTrip(c: Context) {
        scope.launch {
            EngineBus.debugHomeKm.value = 0.0
            delay(2_500)   // 집 P단 시작을 엔진이 보게
            skip(c, 121)
            for (k in 0 until 15) { delay(1_000); if (EngineBus.engine.state == TripState.ALBUM_READY) break }
            EngineBus.debugHomeKm.value = null
            Log.i(TAG, "debug close_trip done state=${EngineBus.engine.state}")
        }
    }
}
