package ai.yeoro.service

import ai.yeoro.EngineBus
import ai.yeoro.cloud.CloudConfig
import ai.yeoro.cloud.CloudSync
import ai.yeoro.cloud.Holidays
import ai.yeoro.cloud.OutsideRequest
import ai.yeoro.cloud.PlaceKindCache
import ai.yeoro.cloud.shouldLookupPlaceOnPark
import ai.yeoro.cloud.shouldSync
import ai.yeoro.data.LocationSampleEntity
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.data.TripRepository
import ai.yeoro.data.YeoroDb
import ai.yeoro.debug.DebugHooks
import ai.yeoro.engine.*
import ai.yeoro.geo.haversineKm
import ai.yeoro.settings.Settings
import ai.yeoro.data.companionDetail
import ai.yeoro.data.departureSeatsDetail
import ai.yeoro.vehicle.NaviSource
import ai.yeoro.vehicle.VehicleSource
import ai.yeoro.voice.VoiceSession
import ai.yeoro.voice.VoiceState
import ai.yeoro.voice.arrivalSpeech
import ai.yeoro.voice.askSpeech
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime

// 모든 engine.handle 호출은 lifecycleScope(Main)에서만 일어난다 (엔진은 스레드 안전하지 않음).
class RecorderService : LifecycleService() {
    private lateinit var vehicle: VehicleSource
    private lateinit var navi: NaviSource
    private val dao by lazy { YeoroDb.get(this).dao() }
    private companion object { const val RESTORE_WINDOW_MS = 48 * 3_600_000L; const val PARKED_SYNC_MS = 10 * 60_000L }
    private var openTripId: Long? = null
    private var currentDest: ai.yeoro.vehicle.NaviDestination? = null   // 마지막으로 알려진 내비 목적지 (좌표 기록용)
    private var engineDest: Destination? = null   // 엔진에 넘긴 목적지 (질문 카드의 이름·거리)
    private var postedAsk: ai.yeoro.PendingAsk? = null   // 알림으로 이미 띄운 질문
    private var askTimeout: kotlinx.coroutines.Job? = null
    private var lastSampleMs = 0L
    private var lastSampleLoc: Pair<Double, Double>? = null
    private var lastScoreLogMs = 0L
    private var prevGear: Gear? = null
    private var lastLoggedState: TripState? = null
    private var lastLoggedGear: Gear? = null
    private val mutex = Mutex()
    // 도착 인증: P단에 선 시각, 이미 도착 카드를 띄운 여행, 내비가 도착을 알렸을 때 열려 있던 여행
    private var parkedSinceMs: Long? = null
    private var arrivalFiredTrip: Long? = null
    private var naviArrivedTrip: Long? = null
    // 열린 여행의 목적지 캐시 (틱마다 DB를 읽지 않게). 여행이 바뀔 때만 다시 읽는다
    private var destCacheTrip: Long? = null
    private var destCache: Triple<String?, Double?, Double?> = Triple(null, null, null)
    private val repo by lazy { TripRepository(dao) }
    private val debuggable by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    private val settings by lazy { Settings.get(this) }
    private var sentConsent: Boolean? = null   // 엔진에 마지막으로 보낸 동의 (락 안에서만 읽고 쓴다)
    // 서버 동기화 (동의·키가 없으면 CloudSync가 아무 호출도 하지 않는다). 엔진 락 밖에서 따로 돈다
    private val cloud by lazy { CloudSync.get(this) }
    // 판단 엔진의 공공데이터: 특일(CalendarInfo.holiday)과 목적지 종류(TourAPI). 서버는 CloudSync와 같은 문(동의·키·P단)으로만 부른다
    private val holidays by lazy { Holidays(getSharedPreferences("yeoro_holidays", MODE_PRIVATE), if (CloudConfig.enabled) cloud.api else null, consent = { cloud.canCallNow() }) }
    private val placeKinds by lazy { PlaceKindCache(getSharedPreferences("yeoro_places", MODE_PRIVATE), now = System::currentTimeMillis) }
    private var holidaysYear: Int? = null   // 이 프로세스에서 특일을 받아 둔 해 (서비스 시작·해가 바뀔 때 다시)
    private var placeJob: kotlinx.coroutines.Job? = null
    // 음성 (덧붙이는 길: 실패해도 카드·알림·터치는 그대로). P단·동의 켜짐일 때만. 모든 호출은 메인 스레드
    private val voiceIoLazy: Lazy<ai.yeoro.voice.VoiceIo> = lazy { DebugHooks.voiceIo(this) }
    private val voiceIo: ai.yeoro.voice.VoiceIo by voiceIoLazy
    private val voiceLazy: Lazy<VoiceSession> = lazy { VoiceSession(voiceIo, lifecycleScope, EngineBus.voice) }
    private val voice: VoiceSession by voiceLazy
    /** 이미 소리로 물은 출발 질문 (같은 질문은 두 번 묻지 않는다. 동일성으로 비교) */
    private var voicedAsk: ai.yeoro.PendingAsk? = null
    /** 지금 음성이 다루는 것: 도착 카드(ArrivalInfo) 또는 출발 질문(PendingAsk). 그것이 사라지면 음성도 멈춘다 */
    private var voiceFor: Any? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
        ServiceCompat.startForeground(this, Notifier.ID_REC, Notifier.recording(this, recText(settings.consented)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        vehicle = VehicleSource(this).also { it.start() }
        navi = NaviSource(this).also { it.start() }
        lifecycleScope.launch { EngineBus.routeRequests.collect { (n, lat, lon) -> navi.requestRoute(n, lat, lon); Log.i("YeoroEngine", "route request $n") } }
        if (debuggable) registerDebugReceiver()
        lifecycleScope.launch {
            // 동의(정하지 않았으면 꺼짐)를 첫 Tick·복원보다 먼저 엔진에 알린다. 꺼져 있으면 엔진은 판단도 기록도 하지 않는다
            try { applyConsent(settings.consented) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "consent failed", e) }
            // 재생성 시 진행 중인 여행을 복원한 뒤에 루프를 시작한다
            // 엔진 상태가 TRIP/STAYING일 때만 복원, 아니면(이전 프로세스의 낡은 여행) 닫고 버퍼 모드로 시작
            try {
                val open = dao.openTrip()
                if (open != null) {
                    val st = EngineBus.engine.state
                    if (st == TripState.TRIP || st == TripState.STAYING) openTripId = open.id
                    else {
                        val now = EngineBus.nowMs()
                        if (open.startMs in (now - RESTORE_WINDOW_MS)..now) dispatch(Event.Restore(TripState.TRIP, open.startMs))
                        val st2 = EngineBus.engine.state
                        if (st2 == TripState.TRIP || st2 == TripState.STAYING) openTripId = open.id
                        else dao.updateTrip(open.copy(endMs = now, state = "ALBUM_READY"))
                    }
                }
                // 재시작해도 이 여행에서 이미 띄운 도착 카드는 다시 띄우지 않는다
                arrivalFiredTrip = restoredTripFlag(prefs.getLong(PREF_ARRIVAL_FIRED, NO_TRIP), openTripId)
                naviArrivedTrip = restoredTripFlag(prefs.getLong(PREF_NAVI_ARRIVED, NO_TRIP), openTripId)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "restore openTrip failed", e) }
            launch { combine(navi.destination, EngineBus.debugDestination, EngineBus.home) { n, dbg, h -> (dbg ?: n) to h }.collect { (d, home) ->
                currentDest = d
                placeJob?.cancel()
                try {
                    // 목적지 종류: 30일 캐시가 있으면 그 값, 없으면 일단 OTHER(2a와 같음)로 보내고 서버에 묻는다
                    val cached = d?.let { placeKinds.get(it.lat, it.lon) }
                    val dest = if (d == null) null else home?.let { Destination(d.name, haversineKm(it, d.lat to d.lon), cached ?: PlaceKind.OTHER) }
                    // 목적지가 없어진 경우는 집 위치를 몰라도 항상 전달, 있는 경우는 집 위치를 알 때만 전달
                    if (d == null || dest != null) { engineDest = dest; dispatch(Event.DestinationSet(dest)) }
                    if (d != null && dest != null && cached == null) placeJob = launch { lookupPlaceKind(d, dest) }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "destination event failed", e) }
            } }
            launch { refreshHolidays() }   // 서비스 시작 (동의·키·기어가 아니면 아무것도 부르지 않는다. P단 동기화 때 다시 본다)
            // 설정에서 동의를 바꾸면 바로 엔진에 알린다
            launch { settings.consent.collect { c ->
                try { applyConsent(c == true) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "consent event failed", e) }
            } }
            launch { EngineBus.answers.collect { (accepted, kind) ->
                try { dispatch(Event.Answer(accepted, kind)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "answer event failed", e) }
            } }
            // 지금 화면이 보이는 동안 생긴 질문은 카드로만 보인다. 지금 화면이 가려지면(다른 탭·다른 앱) 알림을 띄운다. 질문이 사라지면 알림도 거둔다
            launch { combine(EngineBus.liveVisible, EngineBus.pendingAsk) { v, p -> v to p }.collect { (visible, p) ->
                if (shouldNotifyAsk(visible, p, postedAsk)) { postedAsk = p; Notifier.ask(this@RecorderService, p!!) }
                else if (p == null && postedAsk != null) { postedAsk = null; Notifier.cancelAsk(this@RecorderService) }
            } }
            // 음성이 다루던 질문·도착 카드가 사라지거나 바뀌면(터치 답·알림 답·10분·닫기·30초) 말하기·듣기를 멈춘다
            launch { EngineBus.pendingAsk.collect { p -> if (voiceFor is ai.yeoro.PendingAsk && voiceFor != p) stopVoice("ask gone") } }
            // 말로 답하기는 지금 화면이 보일 때만: 가려지면 듣기를 멈추고, 아직 소리로 묻지 않은 출발 질문이 남은 채 다시 보이면 그때 묻는다
            launch { EngineBus.liveVisible.collect { visible ->
                if (!visible) { if (voiceFor is ai.yeoro.PendingAsk) stopVoice("hidden") }
                else EngineBus.pendingAsk.value?.let { askByVoice(it) }
            } }
            launch { EngineBus.arrival.collect { a -> if (voiceFor is ai.yeoro.ArrivalInfo && voiceFor != a) stopVoice("arrival gone") } }
            // 음성 설정을 끄면 그 자리에서 멈춘다
            launch { settings.voiceGuide.collect { on -> if (!on && voiceFor is ai.yeoro.ArrivalInfo) stopVoice("guide off") } }
            launch { settings.voiceAnswer.collect { on -> if (!on && voiceFor is ai.yeoro.PendingAsk) stopVoice("answer off") } }
            launch { navi.arrivedMs.collect { t -> if (t != null) mutex.withLock { naviArrivedTrip = openTripId; saveTripFlag(PREF_NAVI_ARRIVED, naviArrivedTrip) } } }
            launch { vehicle.state.map { it.gear }.distinctUntilChanged().collect { g ->
                EngineBus.gear.value = g
                // P를 벗어나면 음성(도착 인사·질문 듣기)은 바로 멈춘다
                if (g != Gear.P && g != Gear.UNKNOWN) stopVoice("gear $g")
                // P를 벗어나면 도착 카드는 다음 틱을 기다리지 않고 바로 닫는다
                if (g != Gear.P && g != Gear.UNKNOWN) mutex.withLock { if (EngineBus.arrival.value != null) closeArrival() }
                // 주행 중에 정한 목적지는 종류를 못 물었으니, P가 되면 같은 문으로 한 번 더 묻는다
                if (g == Gear.P) try {
                    val d = currentDest
                    val sent = mutex.withLock { engineDest }
                    if (d != null && sent != null && shouldLookupPlaceOnPark(g, true, placeKinds.get(d.lat, d.lon), placeJob?.isActive == true))
                        placeJob = launch { lookupPlaceKind(d, sent) }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "place retry failed", e) }
            } }
            // P로 바뀌면 바로, P인 동안 10분마다 동기화 (D·R·N에서는 하지 않는다)
            launch { EngineBus.gear.collectLatest { g ->
                // 동의는 매번 다시 본다 (P에 선 채로 켜도 다음 차례에 동기화)
                while (g == Gear.P) {
                    if (shouldSync(settings.consented, g, CloudConfig.enabled)) { cloudSync("parked"); refreshHolidays() }
                    delay(PARKED_SYNC_MS)
                }
            } }
            launch { while (true) { try { tick() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "tick failed", e) }; delay(1_000) } }
            launch { while (true) {
                // 후보의 출발 시각은 락 안에서 읽는다 (같은 순간 attachBuffer·Discard와 경합하지 않게)
                try { mutex.withLock { dao.purgeBuffer(purgeCutoff(EngineBus.nowMs(), EngineBus.engine.candidateSinceMs)) } } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "purge failed", e) }
                delay(3_600_000L)
            } }
        }
    }

    // 이벤트별 engine.handle + 액션 적용을 하나의 락으로 직렬화
    private suspend fun dispatch(e: Event) = mutex.withLock {
        // 기어가 P에서 D·R로 바뀌면 질문 알림 취소 (UNKNOWN은 출발이 아니라 건너뛴다. 락 안에서 처리해 진행 중인 dispatch의 ask와 경합하지 않게)
        if (e is Event.Tick) {
            val g = e.snapshot.gear
            if (prevGear == Gear.P && (g == Gear.D || g == Gear.R)) { cancelAsk(); EngineBus.departureAccepted.value = null }
            if (g != Gear.UNKNOWN) prevGear = g
        }
        handle(EngineBus.engine.handle(e))
    }

    /** 동의가 바뀌었을 때만 ProfileChanged를 보낸다. 끄면 떠 있는 질문·도착 카드도 거둔다 */
    private suspend fun applyConsent(on: Boolean) = mutex.withLock {
        if (sentConsent == on) return@withLock
        sentConsent = on
        if (debuggable) Log.i("YeoroEngine", "consent=$on")
        handle(EngineBus.engine.handle(Event.ProfileChanged(on)))
        if (!on) { cancelAsk(); EngineBus.departureAccepted.value = null; closeArrival(); stopVoice("consent off") }
        getSystemService(android.app.NotificationManager::class.java).notify(Notifier.ID_REC, Notifier.recording(this, recText(on)))
    }
    private fun recText(on: Boolean) = if (on) "조용히 기록 중" else "여로 기록이 꺼져 있어요"

    private fun cancelAsk() { Notifier.cancelAsk(this); EngineBus.pendingAsk.value = null; askTimeout?.cancel() }

    /** 질문을 대기 상태로 올린다. 앱이 보이면 카드만, 아니면 알림도. 알림과 같이 10분 뒤 사라진다. */
    private fun raiseAsk(kind: String, score: ScoreResult, sinceMs: Long?) {
        val p = ai.yeoro.PendingAsk(kind, score.total, engineDest?.name, engineDest?.distanceKm, EngineBus.lastSnapshot.value?.seats.orEmpty(),
            EngineBus.nowMs(), sinceMs, score.signals.filter { it.points > 0 }.sortedByDescending { it.points }.map { it.label })
        EngineBus.pendingAsk.value = p
        askTimeout?.cancel()
        askTimeout = lifecycleScope.launch { delay(askRemainingMs(p.askedMs, EngineBus.nowMs())); if (EngineBus.pendingAsk.compareAndSet(p, null)) Notifier.cancelAsk(this@RecorderService) }
        askByVoice(p)
    }

    /**
     * 출발 질문을 소리로 묻고 듣는다: 지금 화면이 보이고 P단·동의·"말로 답하기"·마이크 권한이 모두 있을 때만, 질문마다 한 번만.
     * 알림으로만 전달된 질문은 소리로 묻지 않는다(지금 화면이 보이게 되면 그때 묻는다). 카드·알림은 그대로 함께 뜬다
     */
    private fun askByVoice(p: ai.yeoro.PendingAsk) {
        if (!shouldVoiceAsk(EngineBus.liveVisible.value, p, voicedAsk)) return
        val q = askSpeech(p) ?: return
        if (vehicle.state.value.gear != Gear.P || !settings.consented || !settings.voiceAnswer.value) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (debuggable) Log.i("YeoroEngine", "voice ask skipped: no RECORD_AUDIO"); return
        }
        voiceFor = p; voicedAsk = p
        if (debuggable) Log.i("YeoroEngine", "voice ask '$q'")
        voice.ask(q) { accepted ->
            // 이 세션이 물은 그 질문이 아직 떠 있을 때만 (다른 세션·다음 질문에 늦게 온 최종은 버린다)
            if (EngineBus.pendingAsk.value !== p) { if (debuggable) Log.i("YeoroEngine", "voice answer stale (accepted=$accepted)"); return@ask }
            // 터치·알림과 같은 compareAndSet 경로: 이미 답했거나 사라진 질문이면 taken=false로 아무 일도 없다
            val taken = EngineBus.answer(accepted, "departure")
            if (taken) Notifier.cancelAsk(this)
            if (debuggable) Log.i("YeoroEngine", "answer kind=departure accepted=$accepted taken=$taken via=voice")
        }
    }

    private fun stopVoice(why: String) {
        voiceFor = null
        if (EngineBus.voice.value == VoiceState.IDLE) return
        if (debuggable) Log.i("YeoroEngine", "voice cancel ($why)")
        // 이 서비스가 음성을 만든 적이 없으면(앞 프로세스 상태가 남은 경우) 만들지 않고 표시만 거둔다
        if (voiceLazy.isInitialized()) voice.cancel() else EngineBus.voice.value = VoiceState.IDLE
    }

    private val prefs by lazy { getSharedPreferences("yeoro_prefs", MODE_PRIVATE) }
    private fun saveTripFlag(key: String, id: Long?) { prefs.edit().putLong(key, id ?: NO_TRIP).apply() }

    /** 내비 위치 (디버그 빌드의 시연 경로 재생 중에는 그 위치) */
    private fun location(): Pair<Double, Double>? = EngineBus.debugLocation.value ?: navi.location.value

    private suspend fun tick() {
        val v = vehicle.state.value; val loc = location()
        EngineBus.location.value = loc
        settings.learnHomeIfConsented(loc)   // 동의했을 때만, 집이 없을 때만 첫 유효 위치를 집으로 (설정 화면에 "자동으로 정해졌어요")
        val homeKm: Double? = EngineBus.debugHomeKm.value ?: EngineBus.home.value?.let { h -> loc?.let { haversineKm(h, it) } }   // 모르면 null
        val nowMs = EngineBus.nowMs()
        val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), java.time.ZoneId.systemDefault())
        val cal = CalendarInfo(weekend = EngineBus.debugWeekend.value ?: (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY),
            holiday = holidays.isHoliday(now.toLocalDate()), hour = now.hour)   // 특일 캐시가 없으면 false (주말 판단만)
        val snap = Snapshot(nowMs, v.gear, v.speedKmh, EngineBus.seatsWithOverride(v.seats), v.trunkOpen, v.wiperOn, v.openWindows, v.doorOpen, homeKm)
        EngineBus.lastSnapshot.value = snap
        dispatch(Event.Tick(snap, cal, now.toLocalDate().toString()))
        if (loc != null) recordSample(loc, snap.timeMs)
        checkArrival(snap, loc)
    }

    /** 도착 판정(ArrivalPolicy)과 카드 닫기. 판정은 여행마다 한 번. */
    private suspend fun checkArrival(snap: Snapshot, loc: Pair<Double, Double>?) = mutex.withLock {
        val g = snap.gear; val now = snap.timeMs
        if (g == Gear.P) { if (parkedSinceMs == null) parkedSinceMs = now } else if (g != Gear.UNKNOWN) parkedSinceMs = null
        EngineBus.arrival.value?.let { if (arrivalCloseDue(it.arrivedMs, now, g)) closeArrival() }
        val tid = openTripId
        if (tid == null || tid == arrivalFiredTrip || g != Gear.P) return@withLock
        if (destCacheTrip != tid) {
            val trip = dao.tripById(tid) ?: return@withLock
            destCache = Triple(trip.destination, trip.destLat, trip.destLon); destCacheTrip = tid
        }
        val (tripDest, tripLat, tripLon) = destCache
        val lat = tripLat ?: currentDest?.lat; val lon = tripLon ?: currentDest?.lon
        val dist = if (lat != null && lon != null && loc != null) haversineKm(loc, lat to lon) * 1000 else null
        if (arrivalDue(tid, arrivalFiredTrip, naviArrivedTrip, g, parkedSinceMs, dist, now)) showArrival(tid, tripDest ?: currentDest?.name, lat, lon, now)
    }

    private suspend fun showArrival(tripId: Long, place: String?, lat: Double?, lon: Double?, now: Long) {
        arrivalFiredTrip = tripId; saveTripFlag(PREF_ARRIVAL_FIRED, tripId)
        val visit = arrivalVisit(repo, lat, lon, now)
        val a = ai.yeoro.ArrivalInfo(tripId, place, visit, now, EngineBus.lastSnapshot.value?.seats?.size ?: 0,
            arrivalUrl(ai.yeoro.data.ShareTokens.get(this).token(tripId)), then = if (visit >= 2) thenSide(tripId, lat, lon, now) else null)
        EngineBus.arrival.value = a
        if (!EngineBus.liveVisible.value) Notifier.arrival(this, a)
        // 도착 인사: P단·동의·"도착을 소리로" 켜짐일 때 한 번 (이 함수가 여행마다 한 번이라 다시 말하지 않는다)
        if (vehicle.state.value.gear == Gear.P && settings.consented && settings.voiceGuide.value) {
            voiceFor = a
            if (debuggable) Log.i("YeoroEngine", "voice arrival '${arrivalSpeech(a)}'")
            voice.announce(arrivalSpeech(a))
        }
        if (debuggable) { Log.i("YeoroEngine", "arrival trip=$tripId place=$place visit=${a.visit}"); Log.i("YeoroEngine", "upload_url=${a.uploadUrl}") }
        // QR이 열리도록 여행을 등록하고 밀린 일을 처리한다 (도착 카드는 P단에서만 뜬다). 엔진 락을 잡지 않도록 따로 띄운다
        lifecycleScope.launch {
            // ensureRegistered: 이번에 새로 등록했으면 true. 동의·키가 없거나 그새 P를 벗어났으면 아무것도 부르지 않는다
            val fresh = cloud.ensureRegistered(tripId)
            // 그날의 바깥: 이 여행의 arrival(목적지 좌표, 도착 시각)도 받는다
            cloudSync("arrival", registeredBefore = if (fresh) 1 else 0, arrival = OutsideRequest(tripId, now))
        }
    }

    /** 그때 vs 지금의 그때 쪽 (도착 카드). 열린 여행이 없으면(tripId 0, 디버그) 지금 출발한 것으로 본다. 읽기 실패는 카드를 막지 않는다 */
    private suspend fun thenSide(tripId: Long, lat: Double?, lon: Double?, now: Long): ai.yeoro.ui.album.VisitSide? = try {
        val current = dao.tripById(tripId) ?: ai.yeoro.data.TripEntity(startMs = now, destLat = lat, destLon = lon)
        ai.yeoro.ui.album.loadThenSide(dao, current.copy(destLat = current.destLat ?: lat, destLon = current.destLon ?: lon), EngineBus.home.value, java.time.ZoneId.systemDefault())?.second
    } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "then side failed", e); null }

    /** 한 번 동기화. 동의·키가 없으면 아무것도 하지 않고 로그도 남기지 않는다 */
    private suspend fun cloudSync(reason: String, registeredBefore: Int = 0, arrival: OutsideRequest? = null, closedTrip: Long? = null) {
        if (!cloud.active) return
        try {
            val r = cloud.syncOnce(reason, arrival, closedTrip)
            // registeredBefore: 도착 때 ensureRegistered가 방금 등록한 여행 (로그에 함께 센다)
            if (debuggable) Log.i("YeoroEngine", "cloud sync reason=$reason deleted=${r.deleted} registered=${r.registered + registeredBefore} outside=${r.outside} photos=${r.newPhotos}")
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "cloud sync failed", e) }
    }

    /**
     * 목적지 종류를 TourAPI로 묻는다(동의·키·P단일 때만, CloudSync.placeKind). 답은 좌표 키로 30일 캐시하고,
     * 같은 목적지가 아직이면 kind를 바꾼 DestinationSet을 다시 보낸다. 오프라인·실패면 OTHER 그대로.
     */
    private suspend fun lookupPlaceKind(d: ai.yeoro.vehicle.NaviDestination, sent: Destination) {
        try {
            val kind = cloud.placeKind(d.lat, d.lon, EngineBus.nowMs()) ?: return
            placeKinds.put(d.lat, d.lon, kind)
            if (debuggable) Log.i("YeoroEngine", "place kind=$kind")
            if (kind == sent.kind || currentDest != d) return
            val updated = sent.copy(kind = kind)
            mutex.withLock { if (engineDest == sent) engineDest = updated else return }
            dispatch(Event.DestinationSet(updated))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "place kind failed", e) }
    }

    /** 특일: 이 프로세스에서 아직 받지 않은 해(서비스 시작·해가 바뀜)면 받는다. 동의·키·기어가 아니면 Holidays가 아무것도 부르지 않는다 */
    private suspend fun refreshHolidays() {
        val y = java.time.Instant.ofEpochMilli(EngineBus.nowMs()).atZone(java.time.ZoneId.systemDefault()).year
        if (holidaysYear == y || !cloud.canCallNow()) return
        try {
            if (holidays.refresh(y)) { holidaysYear = y; if (debuggable) Log.i("YeoroEngine", "holidays year=$y") }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "holidays failed", e) }
    }

    private fun closeArrival() { EngineBus.arrival.value = null; Notifier.cancelArrival(this) }

    // 30초마다, 또는 마지막 샘플에서 300m 이상 움직이면 위치 샘플을 남긴다. tripId 규칙은 순간과 같다.
    private suspend fun recordSample(loc: Pair<Double, Double>, nowMs: Long) = mutex.withLock {
        val st = EngineBus.engine.state
        if (st != TripState.CANDIDATE && st != TripState.TRIP && st != TripState.STAYING) return@withLock
        val last = lastSampleLoc
        if (last != null && nowMs - lastSampleMs < 30_000 && haversineKm(last, loc) * 1000 < 300) return@withLock
        val tid = if (st == TripState.TRIP || st == TripState.STAYING) openTripId else null
        dao.insertSample(LocationSampleEntity(tripId = tid, timeMs = nowMs, lat = loc.first, lon = loc.second))
        lastSampleMs = nowMs; lastSampleLoc = loc
    }

    private suspend fun handle(actions: List<Action>) = actions.forEach { a ->
        try { apply(a) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("YeoroEngine", "action failed: $a", e) }
    }

    private suspend fun apply(a: Action) {
        if (debuggable) {
            if (a is Action.ScoreUpdated) {
                val t = System.currentTimeMillis()
                val g = vehicle.state.value.gear
                // 상태·기어가 바뀌면 즉시, 아니면 10초 간격으로 (시나리오 스크립트가 상태+기어를 함께 검사한다)
                if (t - lastScoreLogMs >= 10_000 || a.state != lastLoggedState || g != lastLoggedGear) { lastScoreLogMs = t; lastLoggedState = a.state; lastLoggedGear = g; Log.d("YeoroEngine", "score=${a.score.total} state=${a.state} gear=${vehicle.state.value.gear}") }
            }
        }
        // 시나리오 스크립트가 클래스 이름으로 검사한다 (ScoreUpdated 제외)
        if (debuggable && a !is Action.ScoreUpdated) Log.i("YeoroEngine", "action=${a::class.simpleName} $a")
        when (a) {
            is Action.ScoreUpdated -> EngineBus.lastScore.value = a
            is Action.AskBeforeDeparture -> raiseAsk("departure", a.score, null)
            is Action.AskRetroactive -> raiseAsk("retro", a.score, a.sinceMs)
            is Action.StartTrip -> {
                val id = dao.insertTrip(TripEntity(startMs = a.sinceMs, retroactive = a.retroactive, destination = a.destinationName, destLat = currentDest?.lat, destLon = currentDest?.lon)); openTripId = id
                if (a.retroactive) dao.attachBuffer(id, a.sinceMs)
                // 바로 출발한 여행은 출발 때 앉은 좌석을 남긴다 (앨범의 탑승 인원·출발 기록문). 소급 여행은 출발 때 좌석을 모르니 남기지 않는다
                else departureSeatsDetail(EngineBus.lastSnapshot.value?.seats.orEmpty())?.let { d ->
                    dao.insertMoment(MomentEntity(tripId = id, timeMs = a.sinceMs, type = MomentType.COMPANION_JOINED.name, detail = d, lat = location()?.first, lon = location()?.second))
                }
            }
            is Action.RecordMoment -> {
                val st = EngineBus.engine.state
                val tid = if (st == TripState.TRIP || st == TripState.STAYING || st == TripState.ALBUM_READY) openTripId else null   // ALBUM_READY: 같은 틱에서 곧 TripClosed가 이어짐   // 후보 단계의 순간은 버퍼(null)
                // 좌석 순간에는 그때 앉은 좌석을 붙인다 (기록문이 가족 이름으로 바꿔 쓴다)
                val detail = if (a.moment.type == MomentType.COMPANION_JOINED) companionDetail(a.moment.detail, EngineBus.lastSnapshot.value?.seats.orEmpty()) else a.moment.detail
                dao.insertMoment(MomentEntity(tripId = tid, timeMs = a.moment.timeMs, type = a.moment.type.name, detail = detail, lat = location()?.first, lon = location()?.second))
            }
            is Action.TripClosed -> {
                val closed = (openTripId?.let { id -> dao.tripById(id) } ?: dao.openTrip())?.also { dao.updateTrip(it.copy(endMs = a.endMs, state = "ALBUM_READY")) }; openTripId = null
                // 여행을 닫을 때 (엔진 락 밖에서): 그날의 바깥 departure와 비어 있는 arrival도 받는다
                lifecycleScope.launch { cloudSync(CloudSync.REASON_CLOSE, closedTrip = closed?.id) }
            }
            Action.Discard -> dao.deleteBuffer()
            is Action.KeepUnconfirmed -> {
                // 확인받지 못한 여행: 지우지 않고 "UNCONFIRMED"로 보관 (2단계 UI에서 남길지/지울지 묻는다)
                val id = dao.insertTrip(TripEntity(startMs = a.sinceMs, endMs = a.endMs, destination = null, state = "UNCONFIRMED"))
                dao.attachBuffer(id, a.sinceMs); cancelAsk()
            }
        }
    }

    private var debugReceiver: android.content.BroadcastReceiver? = null
    private fun registerDebugReceiver() {
        debugReceiver = DebugHooks.register(this,
            onRoute = { n, lat, lon -> navi.requestRoute(n, lat, lon) },
            onResetPolicy = { lifecycleScope.launch { mutex.withLock { EngineBus.resetEngine(); EngineBus.engine.handle(Event.ProfileChanged(settings.consented)); sentConsent = settings.consented; openTripId = null; arrivalFiredTrip = null; naviArrivedTrip = null; saveTripFlag(PREF_ARRIVAL_FIRED, null); saveTripFlag(PREF_NAVI_ARRIVED, null); Notifier.cancelArrival(this@RecorderService) } } },
            // 판정을 건너뛰고 도착 카드를 바로 띄운다. 열린 여행이 없으면 tripId 0(저장 안 함)과 내비/디버그 목적지를 쓴다
            onArrive = { lifecycleScope.launch { mutex.withLock {
                val tid = openTripId; val trip = tid?.let { dao.tripById(it) }; val d = currentDest
                showArrival(tid ?: 0L, trip?.destination ?: d?.name, trip?.destLat ?: d?.lat, trip?.destLon ?: d?.lon, EngineBus.nowMs())
            } } },
            onSyncNow = { lifecycleScope.launch { cloudSync("debug") } },
            // 아무 문장이나 말해 보기 (설정·기어와 무관, 디버그 전용)
            onVoiceSay = { t -> voiceFor = null; voice.announce(t) },
            onHome = { cmd -> lifecycleScope.launch { mutex.withLock {
                when (cmd) {
                    // here: 지금 위치를 집으로 저장 (위치를 모르면 지우고 다음 유효 위치에서 다시 배운다)
                    "here" -> if (!settings.setHomeHere(location())) settings.resetHome()
                    "clear" -> settings.resetHome()
                    // "lat,lon": 내비 위치가 없는 에뮬레이터용 (디버그 빌드에서만 들어온다)
                    else -> cmd.split(",").mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 2 }?.let { (la, lo) -> settings.setHomeHere(la to lo) }
                }
                Log.i("YeoroEngine", "debug home=$cmd -> ${EngineBus.home.value} saved=${prefs.getString("home_lat", null)},${prefs.getString("home_lon", null)}")
            } } })
    }

    override fun onDestroy() {
        debugReceiver?.let { unregisterReceiver(it) }; vehicle.stop(); navi.stop()
        stopVoice("destroy"); try { if (voiceIoLazy.isInitialized()) voiceIo.release() } catch (e: Exception) { Log.w("YeoroEngine", "voice release failed", e) }
        super.onDestroy()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { super.onStartCommand(intent, flags, startId); return START_STICKY }
}
