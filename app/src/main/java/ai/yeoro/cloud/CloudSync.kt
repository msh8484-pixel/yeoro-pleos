package ai.yeoro.cloud

import ai.yeoro.EngineBus
import ai.yeoro.data.OpenedAlbums
import ai.yeoro.data.PendingDeleteEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.data.TripOutsideEntity
import ai.yeoro.debug.DebugHooks
import ai.yeoro.engine.PlaceKind
import ai.yeoro.data.TripPhotoEntity
import ai.yeoro.ui.album.albumPhoto
import ai.yeoro.ui.album.assignPhotos
import ai.yeoro.ui.album.buildChapters
import ai.yeoro.ui.album.coverPhoto
import ai.yeoro.ui.album.tripLegsOf
import ai.yeoro.data.ShareTokens
import ai.yeoro.data.YeoroDao
import ai.yeoro.data.YeoroDb
import ai.yeoro.engine.Gear
import ai.yeoro.settings.Settings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncReport(val deleted: Int = 0, val registered: Int = 0, val outside: Int = 0, val newPhotos: Int = 0)

/** 도착 카드 직후: 이 여행의 arrival(목적지 좌표, 도착 시각 atMs)을 받는다 */
data class OutsideRequest(val tripId: Long, val atMs: Long)

/** 정기 동기화를 해도 되는 때: 동의했고, 키가 있고, P단일 때만 (주행 중에는 부르지 않는다) */
fun shouldSync(consented: Boolean, gear: Gear, enabled: Boolean): Boolean = consented && enabled && gear == Gear.P

/**
 * 지금 기어에서 서버를 불러도 되는가. P단만. 기어를 모를 때(UNKNOWN)는 릴리스에서는 막고(주행 중일 수 있다),
 * 디버그 빌드에서만 허용한다(에뮬레이터는 차량 신호가 없어 UNKNOWN일 때가 있다).
 */
fun networkGearOk(gear: Gear, debuggable: Boolean): Boolean = gear == Gear.P || (debuggable && gear == Gear.UNKNOWN)

/**
 * 차 ↔ 서버 동기화. **동의가 꺼져 있거나 키가 없으면(enabled = false) 어떤 호출도 하지 않는다.**
 * 서버 호출은 한 줄로 선다(mutex). 네트워크가 실패해도 차 안의 데이터와 화면은 그대로다.
 * 모든 api 호출 바로 앞에서 동의·키(active)와 기어(canNetworkNow)를 다시 본다: 도중에 동의를 끄거나 D로 바꾸면 그다음 호출부터 멈춘다.
 * 예외는 여행을 닫을 때(reason = "close")로, 기어와 상관없이 보낸다(동의·키는 그대로 본다).
 * - 여행 등록: QR(공유 토큰)을 띄운 여행만, 토큰·출발 시각·목적지 이름·기기 비밀만 보낸다(위치 기록은 보내지 않는다).
 * - 여행 삭제: 차 안에서는 mutex를 기다리지 않고 바로(한 트랜잭션, 취소되지 않게) 지우고, 서버 삭제는 pending_deletes 큐로 보내 다음 동기화에서라도 끝낸다.
 * - 가족 사진: 등록된 QR 여행 중 출발 30일 안인 것마다 서버 목록을 받아 새 사진을 넣고 썸네일(표지 사진만 원본도)을 filesDir에 받는다.
 *   서버 목록에 없는 사진(올린 사람이 지움)은 차에서도 지우고, 차에서 뺀 사진(photo_deletes, 숨김)은 다시 받지 않는다.
 *   사진 단계는 여행을 닫을 때(close)여도 P단(canNetworkNow)일 때만 한다(내려받기가 길다).
 * - 그날의 바깥(공공데이터): 도착 카드 직후 arrival, 여행을 닫을 때 departure와 비어 있는 arrival. 그 뒤로도 sync마다
 *   "닫힌 지 3일 안이고 trip_outside가 없거나 arrival이 null"인 여행만 다시 묻는다(기상청 실황·예보는 사흘이 지나면 영영 못 받는다).
 *   보내는 것은 목적지·첫 샘플의 좌표와 시각뿐이다(경로는 보내지 않는다). 좌표를 모르면 건너뛴다.
 * forgetOpened: "열어 본 앨범" 표시 지우기, canNetworkNow: 지금 서버를 불러도 되는 기어인가(P단).
 * weatherAt: 날씨를 물을 시각 (디버그 시연 시계에서만 실제 지금으로 바꾼다. 다르면 날씨만 따로 묻고 합친다). home: 집 좌표(도착 시각 계산용).
 */
class CloudSync(
    val api: CloudApi,
    private val dao: YeoroDao,
    private val tokens: ShareTokens,
    private val files: PhotoFiles,
    private val consent: () -> Boolean,
    private val now: () -> Long,
    private val enabled: Boolean = CloudConfig.enabled,
    private val forgetOpened: (Long) -> Unit = {},
    private val canNetworkNow: () -> Boolean = { true },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val weatherAt: (Long) -> Long = { it },
    private val home: () -> Pair<Double, Double>? = { null },
    /** 여행별 마지막 실패 시각(재시도 간격용). 없으면 이 프로세스 안에서만 기억한다 */
    private val attempts: SharedPreferences? = null,
) {
    private val mutex = Mutex()
    private val attemptMemo = mutableMapOf<Long, Long>()
    private var hardFailed = false

    private fun lastAttempt(tripId: Long): Long? =
        attempts?.getLong("t_$tripId", -1L)?.takeIf { it >= 0 } ?: attemptMemo[tripId]
    private fun setAttempt(tripId: Long, at: Long?) {
        if (at == null) { attemptMemo.remove(tripId); attempts?.edit()?.remove("t_$tripId")?.apply() }
        else { attemptMemo[tripId] = at; attempts?.edit()?.putLong("t_$tripId", at)?.apply() }
    }

    /** 지금 서버를 불러도 되는가 (동의 + 키) */
    val active: Boolean get() = enabled && consent()

    /** 호출 직전에 매번 보는 문: 동의·키, 그리고 (닫을 때가 아니면) 기어 */
    private fun gate(closing: Boolean = false): () -> Boolean = { active && (closing || canNetworkNow()) }

    /** 지금(닫을 때가 아닌 평소) 서버를 불러도 되는가. 특일 캐시(Holidays)가 같은 문을 쓴다 */
    fun canCallNow(): Boolean = gate()()

    /**
     * 목적지 종류(TourAPI, outside parts=place). 동의·키·P단이 아니거나 실패·모름이면 null(부른 쪽은 OTHER 그대로).
     * 판단 엔진의 "목적지 관광·숙박지 +20"이 이 값으로 켜진다.
     */
    suspend fun placeKind(lat: Double, lon: Double, atMs: Long): PlaceKind? {
        val go = gate()
        if (!go()) return null
        return mutex.withLock {
            if (!go()) return@withLock null
            when (val r = api.outside(lat, lon, atMs, setOf("place"))) {
                is CloudResult.Ok -> parseOutside(r.value)?.place?.kind
                is CloudResult.Fail -> null
            }
        }
    }

    /** 이 여행을 서버에 등록한다(QR이 열리게). 이번에 새로 등록했으면 true. 동의·키·기어가 아니거나, 이미 등록했거나, 실패하면 false */
    suspend fun ensureRegistered(tripId: Long): Boolean {
        val go = gate()
        if (!go()) return false
        return mutex.withLock {
            if (tokens.isRegistered(tripId)) false else register(tripId, withContext(io) { tokens.token(tripId) }, go)
        }
    }

    /**
     * 여행 지우기. 차 안 부분(서버 삭제 예약 + Room cascade 삭제는 한 트랜잭션, 그다음 사진 파일·토큰·열어 본 표시)은
     * 네트워크 mutex를 기다리지 않고 바로, 부른 쪽이 취소돼도 끝까지 한다. 토큰이 있으면(QR을 띄운 적 있으면) 등록 표시와
     * 상관없이 서버 삭제를 예약한다(등록되지 않았으면 서버가 404, 큐에서 뺀다). 그 뒤 보낼 수 있으면 큐를 비운다.
     */
    suspend fun deleteTrip(tripId: Long) {
        withContext(NonCancellable + io) {
            val token = tokens.tokenOrNull(tripId)
            dao.deleteTripQueued(tripId, token?.let { PendingDeleteEntity(it, now()) })
            files.deleteTrip(tripId)
            tokens.forget(tripId)
            forgetOpened(tripId)
        }
        val go = gate()
        if (go()) mutex.withLock { stage("delete") { drainDeletes(go) } }
    }

    /**
     * 차 앨범에서 사진 한 장 빼기. 차 안 부분(행 삭제 + 서버 삭제 예약 = 숨김, 그다음 파일)은 mutex를 기다리지 않고 취소되지 않게 바로 한다.
     * 그 뒤 보낼 수 있으면 사진 삭제 큐를 비운다. 서버가 지웠다고 답했으면(204·404) true, 보내지 못했으면 false(큐에 남아 다음 sync에 다시).
     */
    suspend fun removePhoto(tripId: Long, photoId: String): Boolean {
        withContext(NonCancellable + io) {
            dao.removePhotoQueued(tripId, photoId, now())
            files.deletePhoto(tripId, photoId)
        }
        val go = gate()
        if (!go()) return false
        return mutex.withLock {
            stage("photo-delete") { drainPhotoDeletes(go) }
            dao.photoDeletes().none { it.id == photoId }
        }
    }

    /**
     * 한 번 동기화: 삭제 큐(여행·사진) → 미등록 QR 여행 등록 → 공공데이터 → 사진. 단계마다 실패해도 다음으로.
     * arrival: 도착 카드 직후면 그 여행의 arrival을 받는다. closedTrip: 방금 닫은 여행(departure와 비어 있는 arrival을 받는다).
     */
    suspend fun syncOnce(reason: String, arrival: OutsideRequest? = null, closedTrip: Long? = null): SyncReport {
        val go = gate(closing = reason == REASON_CLOSE)
        if (!go()) return SyncReport()
        return mutex.withLock {
            val deleted = stage("delete") { drainDeletes(go) }
            val registered = stage("register") { registerShown(go) }
            stage("photo-delete") { drainPhotoDeletes(go) }
            val outside = stage("outside") { syncOutside(go, gate(), arrival, closedTrip) }
            // 사진 내려받기는 닫을 때여도 P단에서만 (기어 예외 없음)
            val photos = stage("photos") { syncPhotos(gate()) }
            SyncReport(deleted = deleted, registered = registered, outside = outside, newPhotos = photos)
        }
    }

    /**
     * 공공데이터: 도착한 여행의 arrival, 방금 닫은 여행의 departure(와 비어 있는 arrival),
     * 그리고 닫힌 지 3일 안인 여행 중 trip_outside가 없거나 arrival이 null인 것. 새로 저장한 여행 수
     */
    private suspend fun syncOutside(go: () -> Boolean, strict: () -> Boolean, arrival: OutsideRequest?, closedTrip: Long?): Int {
        val done = mutableSetOf<Long>()
        if (arrival != null) dao.tripById(arrival.tripId)?.let { t -> if (go() && fetchOutside(t, go, arrival.atMs)) done += t.id }
        if (closedTrip != null) dao.tripById(closedTrip)?.let { t -> if (go() && fetchOutside(t, go, null, departure = true)) done += t.id }
        val tried = setOfNotNull(arrival?.tripId, closedTrip)   // 이번에 이미 물은 여행은 다시 묻지 않는다
        // 재시도는 닫을 때여도 기어를 본다(기어 예외는 방금 닫은 여행 자신의 호출에만). 하드 실패한 여행은 한 시간에 한 번만
        for (t in dao.tripsClosedSince(now() - OUTSIDE_RETRY_MS)) {
            if (!strict()) break
            if (t.id in tried) continue
            if (lastAttempt(t.id)?.let { now() - it < OUTSIDE_BACKOFF_MS } == true) continue
            if (fetchOutside(t, strict, null)) done += t.id
        }
        return done.size
    }

    /**
     * 이 여행에 비어 있는 칸을 받아 저장한다. departure는 닫힌 여행만. 이미 arrival이 있으면 아무것도 묻지 않는다
     * (departure = true(방금 닫은 여행)면 비어 있는 departure는 묻는다).
     * 도착 좌표 = 목적지 좌표(없으면 도착 샘플), 도착 시각 = arrivalAtMs(도착 카드) 또는 도착 샘플 시각(tripLegs, 없으면 여행 끝).
     * 출발 = 첫 샘플의 좌표·시각. 하나라도 새로 받았으면 true.
     */
    private suspend fun fetchOutside(t: TripEntity, go: () -> Boolean, arrivalAtMs: Long?, departure: Boolean = false): Boolean {
        hardFailed = false
        val ok = fetchOutsideInner(t, go, arrivalAtMs, departure)
        if (ok) setAttempt(t.id, null) else if (hardFailed) setAttempt(t.id, now())
        return ok
    }

    private suspend fun fetchOutsideInner(t: TripEntity, go: () -> Boolean, arrivalAtMs: Long?, departure: Boolean): Boolean {
        val (oldArrival, oldDeparture) = dao.outsideNow(t.id)?.let { tripOutsideParts(it.json) } ?: (null to null)
        if (oldArrival != null && !(departure && oldDeparture == null)) return false
        val samples = dao.samplesNow(t.id)
        val legs = tripLegsOf(t, samples, dao.momentsNow(t.id), home())
        val arrivedSample = legs?.let { samples[it.arriveIdx] }
        val dest = t.destLat?.let { la -> t.destLon?.let { lo -> la to lo } } ?: arrivedSample?.let { it.lat to it.lon }
        val arriveAt = arrivalAtMs ?: arrivedSample?.timeMs ?: t.endMs
        val newArrival = if (oldArrival == null && dest != null && arriveAt != null) fetchOne(dest.first, dest.second, arriveAt, go) else null
        val first = samples.firstOrNull()
        val newDeparture = if (oldDeparture == null && t.endMs != null && first != null) fetchOne(first.lat, first.lon, first.timeMs, go) else null
        if (newArrival == null && newDeparture == null) return false
        // 받는 사이에 여행을 지웠으면 저장하지 않는다 (외래 키)
        if (dao.tripById(t.id) == null) return false
        dao.upsertOutside(TripOutsideEntity(t.id, now(), tripOutsideJson(newArrival ?: oldArrival, newDeparture ?: oldDeparture)))
        return true
    }

    /** outside 한 번(시연 시계면 날씨만 따로 한 번 더). 읽을 수 없는 응답·실패는 null */
    private suspend fun fetchOne(lat: Double, lon: Double, atMs: Long, go: () -> Boolean): String? {
        val wAt = weatherAt(atMs)
        if (!go()) return null
        if (wAt == atMs) return okOutside(api.outside(lat, lon, atMs))
        val main = okOutside(api.outside(lat, lon, atMs, setOf("sun", "place", "festival", "holiday")))
        if (!go()) return main
        return mergeOutsideWeather(main, okOutside(api.outside(lat, lon, wAt, setOf("weather"))))
    }

    /** 읽을 수 없는 응답과 서버·네트워크 실패는 하드 실패(재시도 간격을 둔다). 오프라인은 아니다 */
    private fun okOutside(r: CloudResult<String>): String? {
        val v = (r as? CloudResult.Ok)?.value?.takeIf { parseOutside(it) != null }
        if (v == null && !(r is CloudResult.Fail && r.code == "offline")) hardFailed = true
        return v
    }

    /** 차에서 뺀 사진의 서버 삭제. 204·404(이미 없음)면 큐(숨김)에서 뺀다. 토큰이 없는 여행(QR을 띄운 적 없음)은 서버에 사진이 없다 */
    private suspend fun drainPhotoDeletes(go: () -> Boolean): Int {
        var n = 0
        for ((tripId, queued) in dao.photoDeletes().groupBy { it.tripId }) {
            if (!go()) break
            val token = withContext(io) { tokens.tokenOrNull(tripId) }
            if (token == null) { dao.deletePhotoDeletes(queued.map { it.id }); continue }
            for (ids in queued.map { it.id }.chunked(PHOTO_DELETE_MAX)) {
                if (!go()) break
                val r = api.deletePhotos(token, ids)
                if (r is CloudResult.Ok || (r is CloudResult.Fail && r.status == 404)) { dao.deletePhotoDeletes(ids); n += ids.size }
            }
        }
        return n
    }

    /** 등록된 QR 여행 중 출발 30일 안인 것의 사진 맞추기. 새로 넣은 사진 수 */
    private suspend fun syncPhotos(go: () -> Boolean): Int {
        var added = 0
        for ((tripId, token) in withContext(io) { tokens.all() }) {
            if (!go()) break
            if (!tokens.isRegistered(tripId)) continue
            // 여행 하나가 실패해도(삭제 뒤 FK 위반 등) 나머지 여행은 계속 맞춘다
            try {
                val trip = dao.tripById(tripId) ?: continue
                if (trip.startMs < now() - PHOTO_WINDOW_MS) continue
                val remote = (api.photos(token) as? CloudResult.Ok)?.value ?: continue   // 목록을 못 받으면 아무것도 지우지 않는다
                added += mergeTripPhotos(trip, remote.filter { it.id.matches(PHOTO_ID) }, go)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "cloud photos trip failed: ${e.javaClass.simpleName}")
            }
        }
        return added
    }

    /**
     * 서버 목록 하나를 차에 맞춘다: 서버에서 사라진 사진 지우기 → 새 사진 넣기(숨김 제외) → 썸네일이 없는 사진 받기 → 표지 사진 원본 받기.
     * 내려받기에 실패한 사진은 파일 없이 두고 다음 sync에 다시 받는다. 받는 사이에 여행·사진이 지워졌으면 받은 파일을 지운다.
     */
    private suspend fun mergeTripPhotos(trip: TripEntity, remote: List<RemotePhoto>, go: () -> Boolean): Int {
        val plan = planPhotoSync(dao.photosNow(trip.id).mapTo(HashSet()) { it.id }, remote, dao.hiddenPhotoIds(trip.id).toSet())
        if (plan.prune.isNotEmpty()) {
            dao.deletePhotosByIds(plan.prune.toList())
            withContext(io) { plan.prune.forEach { files.deletePhoto(trip.id, it) } }
        }
        if (dao.tripById(trip.id) == null) return 0
        dao.upsertPhotos(plan.add.map { TripPhotoEntity(it.id, trip.id, it.takenAtMs, it.takenAtSource, it.uploadedMs, it.w, it.h, thumbFile = null, fullFile = null) })
        val urls = remote.associateBy { it.id }
        for (row in dao.photosNow(trip.id)) {
            if (row.thumbFile != null) continue
            val r = urls[row.id] ?: continue
            if (!go()) break
            fetchFile(trip.id, row.id, r.thumbUrl, files.thumb(trip.id, row.id), thumb = true)
        }
        val rows = dao.photosNow(trip.id)
        val samples = dao.samplesNow(trip.id)
        val moments = dao.momentsNow(trip.id)
        val chapters = buildChapters(trip, samples, moments, home())
        val cover = coverPhoto(chapters, assignPhotos(chapters, rows.map(::albumPhoto)))
        if (cover != null && cover.fullFile == null && go()) urls[cover.id]?.let { fetchFile(trip.id, cover.id, it.url, files.full(trip.id, cover.id), thumb = false) }
        return plan.add.size
    }

    private suspend fun fetchFile(tripId: Long, photoId: String, url: String, dest: java.io.File, thumb: Boolean) {
        if (api.download(url, dest) !is CloudResult.Ok) return
        // 받는 사이에 사진(또는 여행)을 지웠으면 남은 파일을 치운다
        if (dao.photosByIds(listOf(photoId)).isEmpty()) {
            withContext(io) { dest.delete(); dest.parentFile?.delete() }   // 폴더가 비었으면 그것도 치운다(비어 있지 않으면 delete가 실패해 그대로 둔다)
            return
        }
        if (thumb) dao.setPhotoFiles(photoId, dest.path, null) else dao.setPhotoFiles(photoId, null, dest.path)
    }

    private suspend fun drainDeletes(go: () -> Boolean): Int {
        var n = 0
        for (p in dao.pendingDeletes()) {
            if (!go()) break
            val r = api.deleteTrip(p.token)
            if (r is CloudResult.Ok || (r is CloudResult.Fail && r.status == 404)) { dao.deletePending(p.token); n++ }
        }
        return n
    }

    private suspend fun registerShown(go: () -> Boolean): Int {
        var n = 0
        for ((tripId, token) in withContext(io) { tokens.all() }) {
            if (!go()) break
            if (tokens.isRegistered(tripId)) continue
            if (dao.tripById(tripId) == null) { withContext(io) { tokens.forget(tripId) }; continue }   // 여행이 없는 토큰(디버그 도착 카드 등)은 버린다
            if (register(tripId, token, go)) n++
        }
        return n
    }

    private suspend fun register(tripId: Long, token: String, go: () -> Boolean): Boolean {
        val trip = dao.tripById(tripId) ?: return false
        if (!go()) return false
        return when (api.registerTrip(token, trip.startMs, trip.destination)) {
            // 등록하는 사이에 여행을 지웠으면(토큰이 바뀌었으면) 표시하지 않는다. 서버 쪽은 삭제 큐가 지운다
            is CloudResult.Ok -> withContext(io) { tokens.markRegisteredIf(tripId, token) }
            is CloudResult.Fail -> false   // 409(다른 기기의 토큰) 포함: 표시하지 않는다
        }
    }

    private suspend fun stage(name: String, block: suspend () -> Int): Int =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "cloud $name failed: ${e.javaClass.simpleName}"); 0 }

    companion object {
        private const val TAG = "YeoroEngine"
        /** 여행을 닫을 때의 동기화: 기어와 상관없이 보낸다 (전역 규칙 "P단이거나 여행을 닫을 때만") */
        const val REASON_CLOSE = "close"
        /** 닫힌 뒤 공공데이터를 다시 묻는 기간 (기상청 단기예보는 그제 발표까지만 준다) */
        const val OUTSIDE_RETRY_MS = 3L * 24 * 3_600_000
        /** 하드 실패한 여행을 다시 묻기까지의 간격 */
        const val OUTSIDE_BACKOFF_MS = 3_600_000L
        /** 사진을 받는 여행: 출발 30일 안 (서버 보관 기간과 같다) */
        const val PHOTO_WINDOW_MS = 30L * 24 * 3_600_000
        /** photos DELETE 한 번에 보낼 수 있는 id 수 */
        private const val PHOTO_DELETE_MAX = 50
        private val PHOTO_ID = Regex("[A-Za-z0-9-]{1,64}")
        @Volatile private var instance: CloudSync? = null
        /** 앱 전체에서 하나 (서비스·홈·디버그가 같은 줄에 선다) */
        fun get(c: Context): CloudSync = instance ?: synchronized(this) {
            instance ?: create(c.applicationContext).also { instance = it }
        }
        private fun create(app: Context): CloudSync {
            val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val api = HttpCloudApi(CloudConfig, DeviceSecret.get(app),
                online = { cm?.activeNetwork != null },
                log = if (debuggable) { m -> Log.i(TAG, m) } else null)
            val settings = Settings.get(app)
            return CloudSync(api, YeoroDb.get(app).dao(), ShareTokens.get(app), PhotoFiles.of(app),
                consent = { settings.consented }, now = System::currentTimeMillis,
                forgetOpened = { OpenedAlbums.forget(app, it) },
                canNetworkNow = { networkGearOk(EngineBus.gear.value, debuggable) },
                weatherAt = { at -> DebugHooks.weatherAt(at) },
                home = { EngineBus.home.value },
                attempts = app.getSharedPreferences("yeoro_outside_attempts", Context.MODE_PRIVATE))
        }
    }
}
