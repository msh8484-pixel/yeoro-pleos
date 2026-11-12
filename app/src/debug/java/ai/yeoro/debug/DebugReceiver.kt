package ai.yeoro.debug

import ai.yeoro.EngineBus
import ai.yeoro.data.YeoroDb
import ai.yeoro.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ai.yeoro.vehicle.NaviDestination
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 디버그 빌드에서만 RecorderService가 동적으로 등록한다 (릴리스·매니페스트에는 없음).
 * adb shell am broadcast --user 10 -a ai.yeoro.DEBUG -p ai.yeoro --es route 강릉 --es weekend true --ez reset_policy true --es home here|clear|lat,lon --ez arrive true
 * --ez seed_trip true (또는 --es seed_trip true): 샘플 여행(강릉, 위치 30개·정차 2번·비·창문)을 DB에 넣는다. 다시 보내면 바꿔 넣는다. 앨범 탭이 가장 최근 여행으로 연다.
 *   --es seed_trip round: 같은 여행의 왕복판(17:05 경포 출발, 서울양양고속도로로 귀가, 18:40 가평휴게소 정차, 19:40 집). 집(home)이 마포로 정해져 있어야 돌아오는 길 장이 생긴다.
 * --ez seed_prev true (또는 --es seed_prev true): 그때 vs 지금의 지난 방문 — 강릉 경포해변을 시연 토요일 − 364일에 다녀온 여행(14:41 도착, 비, 3명, 사진 1장, 날씨 없음).
 *   demo_clock 뒤에 보내야 시연 토요일 기준이다(없으면 실제 오늘). 다시 보내면 바꿔 넣는다. clear_trips가 함께 지운다.
 * --ez seed_home true: 홈(책장) 샘플 — seed_trip 여행 + 2025 강릉(정기 방문 2회) + 2026 전주 + 확인 안 한 2026-10-18 여행. 다시 보내면 바꿔 넣는다.
 * --es consent true|false: 여로 기록 동의를 바꾼다(설정 화면 스위치와 같다). prep.sh가 기본으로 true를 보낸다.
 * --ez memory_reset true: 1년 전 이번 주 회상 카드의 "오늘 띄움" 표시(memory_shown_date)를 지운다(하루 한 번 규칙을 다시 보기).
 * --ez clear_trips true: 모든 여행과 "열어 본 앨범" 표시를 지운다(빈 책장 확인용).
 * 시연(scenario_demo.sh, DemoDrive): --es demo_route a-b (샘플 경로 a..b번 지점을 위치로 재생하며 시계를 앞당김)  | --es demo_loc lat,lon|clear
 *   | --es demo_clock HH:MM (시계를 다가오는 토요일 그 시각으로)
 *   | --es outside_time real|demo (시계 보정 중 날씨를 실제 지금으로 묻기, 기본 demo. 시연 날짜는 기상청 창 밖일 수 있다)
 *   | --ei skip_min N (시계 N분 앞당김) | --ez close_trip true (집 0km로 알리고 121분 → 엔진의 귀가 규칙으로 닫힘). reset_policy가 모두 지운다.
 * --es debug_panel true|false: 지금 화면의 "판단 엔진" 패널 보이기/숨기기 (저장됨, reset_policy가 지우지 않음. 시연 스크립트는 기본 숨김)
 * --es seat rear_center:off|on|auto: 좌석 재정의(seat_override 저장, reset_policy가 지움). 에뮬레이터는 뒷좌석 가운데를 조작할 수 없어 늘 앉은 것으로 오므로 시연은 비운다.
 *   좌석 이름: driver, passenger, rear_left, rear_center, rear_right. auto = 재정의 해제(차량 값)
 * --ez sync_now true: 서버 동기화 한 번(CloudSync.syncOnce("debug")). 동의·키가 없으면 아무 호출도 하지 않고 "cloud sync" 로그도 없다.
 * 음성: --es voice_pcm yes|no|maybe|off (듣기에 마이크 대신 넣을 PCM, off = 실제 마이크. reset_policy가 off로) | --es voice_say <문장> (아무 문장이나 말해 보기)
 *   | --es voice_guide true|false (도착 인사) | --es voice_answer true|false (출발 질문에 말로 답하기. 설정 화면과 달리 권한을 묻지 않는다: pm grant로 RECORD_AUDIO를 따로 준다)
 * arrive: 판정 규칙을 건너뛰고 도착 카드를 바로 띄운다 (열린 여행이 없으면 tripId 0, 목적지는 내비/디버그 목적지).
 */
class DebugReceiver(
    private val onRoute: (name: String, lat: Double, lon: Double) -> Unit,
    private val onResetPolicy: () -> Unit,
    private val onHome: (String) -> Unit,
    private val onArrive: () -> Unit,
    private val onSyncNow: () -> Unit,
    private val onVoiceSay: (String) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        i.getStringExtra("weekend")?.let { EngineBus.debugWeekend.value = when (it) { "true" -> true; "false" -> false; else -> null }; Log.i(TAG, "debug weekend=$it") }
        // 동의 스위치 (설정 화면과 같은 경로: Settings → RecorderService가 ProfileChanged를 보낸다)
        // 가족 이름 (운전석,조수석,뒷왼,뒷가운데,뒷오른 순, 빈 칸은 지움): 시연을 새로 설치한 앱에서도 같은 이름으로
        i.getStringExtra("family")?.let { v ->
            val seats = listOf(ai.yeoro.engine.Seat.DRIVER, ai.yeoro.engine.Seat.PASSENGER, ai.yeoro.engine.Seat.REAR_LEFT, ai.yeoro.engine.Seat.REAR_CENTER, ai.yeoro.engine.Seat.REAR_RIGHT)
            val names = v.split(",")
            seats.forEachIndexed { k, seat -> Settings.get(c).setFamilyName(seat, names.getOrNull(k).orEmpty()) }
            Log.i(TAG, "debug family=$v")
        }
        // 근처 혜택·예약 컨셉 화면 (open|reserve|time:18:30|request|back|close)
        i.getStringExtra("nearby")?.let { v ->
            val ui = ai.yeoro.ui.live.Nearby
            when {
                v == "open" -> EngineBus.arrival.value.let { a -> ui.open(a?.place ?: "강릉 경포해변", a?.seats ?: 4) }
                v == "reserve" -> ui.reserve()
                v.startsWith("time:") -> ui.pick(v.removePrefix("time:"))
                v == "request" -> ui.request()
                v == "back" -> ui.back()
                v == "close" -> ui.close()
                else -> Log.w(TAG, "debug nearby=$v 모름 (open|reserve|time:HH:MM|request|back|close)")
            }
            Log.i(TAG, "debug nearby=$v")
        }
        i.getStringExtra("consent")?.let { when (it) { "true", "false" -> Settings.get(c).setConsent(it == "true"); else -> Log.w(TAG, "debug consent=$it 모름 (true|false)") }; Log.i(TAG, "debug consent=$it") }
        if (i.getBooleanExtra("reset_policy", false)) { DemoDrive.reset(c); PcmVoiceIo.feed = null; onResetPolicy(); Log.i(TAG, "debug reset_policy") }
        i.getStringExtra("voice_pcm")?.let { v -> when (v) { in PcmVoiceIo.Feeds -> PcmVoiceIo.feed = v; "off" -> PcmVoiceIo.feed = null; else -> Log.w(TAG, "debug voice_pcm=$v 모름 (yes|no|maybe|off)") }; Log.i(TAG, "debug voice_pcm=${PcmVoiceIo.feed ?: "off"}") }
        i.getStringExtra("voice_guide")?.let { when (it) { "true", "false" -> Settings.get(c).setVoiceGuide(it == "true"); else -> Log.w(TAG, "debug voice_guide=$it 모름 (true|false)") }; Log.i(TAG, "debug voice_guide=$it") }
        i.getStringExtra("voice_answer")?.let { when (it) { "true", "false" -> Settings.get(c).setVoiceAnswer(it == "true"); else -> Log.w(TAG, "debug voice_answer=$it 모름 (true|false)") }; Log.i(TAG, "debug voice_answer=$it") }
        i.getStringExtra("voice_say")?.takeIf { it.isNotBlank() }?.let { onVoiceSay(it); Log.i(TAG, "debug voice_say") }
        i.getStringExtra("debug_panel")?.let { when (it) { "true", "false" -> DebugHooks.setDebugPanel(c, it == "true"); else -> Log.w(TAG, "debug debug_panel=$it 모름 (true|false)") }; Log.i(TAG, "debug debug_panel=$it") }
        i.getStringExtra("seat")?.let { v -> if (DemoDrive.setSeat(c, v)) Log.i(TAG, "debug seat=$v -> ${EngineBus.debugSeats.value}") else Log.w(TAG, "debug seat=$v 모름 (rear_center:off|on|auto)") }
        i.getStringExtra("demo_loc")?.let { v ->
            val ll = v.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            when { v == "clear" -> DemoDrive.setLocation(c, null); ll.size == 2 -> DemoDrive.setLocation(c, ll[0] to ll[1]); else -> Log.w(TAG, "debug demo_loc=$v 모름 (lat,lon|clear)") }
            Log.i(TAG, "debug demo_loc=$v")
        }
        i.getStringExtra("demo_clock")?.let { DemoDrive.setClock(c, it) }
        i.getStringExtra("outside_time")?.let { if (DemoDrive.setOutsideTime(c, it)) Log.i(TAG, "debug outside_time=$it") else Log.w(TAG, "debug outside_time=$it 모름 (real|demo)") }
        if (i.hasExtra("skip_min")) DemoDrive.skip(c, i.getIntExtra("skip_min", 0).toLong())
        i.getStringExtra("demo_route")?.let { DemoDrive.play(c, it) }
        if (i.getBooleanExtra("close_trip", false)) { DemoDrive.closeTrip(c); Log.i(TAG, "debug close_trip") }
        i.getStringExtra("home")?.let { if (it == "here" || it == "clear" || it.matches(Regex("-?[0-9.]+,-?[0-9.]+"))) onHome(it) else Log.w(TAG, "debug home=$it 모름 (here|clear|lat,lon)") }
        if (i.getBooleanExtra("arrive", false)) { onArrive(); Log.i(TAG, "debug arrive") }
        if (i.getBooleanExtra("sync_now", false)) { onSyncNow(); Log.i(TAG, "debug sync_now") }
        @Suppress("DEPRECATION") val seedTrip = i.extras?.get("seed_trip")
        if (seedTrip == true || seedTrip == "true" || seedTrip == "round") {
            val round = seedTrip == "round"
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try { Log.i(TAG, "debug seed_trip${if (round) " round" else ""} id=${SeedTrip.seed(YeoroDb.get(c), round)}") }
                catch (e: Exception) { Log.e(TAG, "debug seed_trip 실패", e) }
                finally { pending.finish() }
            }
        }
        @Suppress("DEPRECATION") val seedPrev = i.extras?.get("seed_prev")
        if (seedPrev == true || seedPrev == "true") runAsync("seed_prev") { "id=${SeedPrev.seed(c, YeoroDb.get(c))}" }
        if (i.getBooleanExtra("seed_home", false)) runAsync("seed_home") { "ids=${SeedHome.seed(c, YeoroDb.get(c))}" }
        if (i.getBooleanExtra("memory_reset", false)) { ai.yeoro.ui.live.MemoryShown.set(c, null); Log.i(TAG, "debug memory_reset") }
        if (i.getBooleanExtra("clear_trips", false)) runAsync("clear_trips") { "deleted=${SeedHome.clear(c, YeoroDb.get(c))}" }
        when (i.getStringExtra("route")) {
            "강릉" -> { onRoute("강릉 경포해변", 37.8055, 128.9087); Log.i(TAG, "debug route requested") }
            "inject" -> { EngineBus.debugDestination.value = NaviDestination("강릉 경포해변", 37.8055, 128.9087, 0); Log.i(TAG, "debug destination injected") }
            "short" -> { EngineBus.debugDestination.value = NaviDestination("회사 (성수동)", 37.5446, 127.0559, 0); Log.i(TAG, "debug short destination injected") }
            "clear" -> { EngineBus.debugDestination.value = null; Log.i(TAG, "debug destination cleared") }
        }
    }
    private fun runAsync(what: String, block: suspend () -> String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Log.i(TAG, "debug $what ${block()}") }
            catch (e: Exception) { Log.e(TAG, "debug $what 실패", e) }
            finally { pending.finish() }
        }
    }
    companion object { const val ACTION = "ai.yeoro.DEBUG"; private const val TAG = "YeoroEngine" }
}
