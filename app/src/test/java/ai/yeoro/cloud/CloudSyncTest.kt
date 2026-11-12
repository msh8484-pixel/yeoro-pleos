package ai.yeoro.cloud

import ai.yeoro.data.*
import ai.yeoro.engine.Gear
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** 가짜 서버: 부른 순서를 남기고 정해 둔 결과를 돌려준다 */
class FakeCloudApi : CloudApi {
    val calls = mutableListOf<String>()
    var register: CloudResult<Unit> = CloudResult.Ok(Unit)
    var delete: CloudResult<Unit> = CloudResult.Ok(Unit)
    val registered = mutableListOf<Triple<String, Long, String?>>()
    override suspend fun registerTrip(token: String, startedAtMs: Long, destName: String?): CloudResult<Unit> {
        calls += "register"; registered += Triple(token, startedAtMs, destName); return register
    }
    override suspend fun deleteTrip(token: String): CloudResult<Unit> { calls += "delete:$token"; return delete }
    /** 토큰 → 서버의 사진 목록 (없으면 빈 목록) */
    val photoLists = mutableMapOf<String, CloudResult<List<RemotePhoto>>>()
    /** 이 토큰의 photos 호출은 예외를 던진다 */
    val photosThrow = mutableSetOf<String>()
    override suspend fun photos(token: String): CloudResult<List<RemotePhoto>> { calls += "photos"; if (token in photosThrow) throw IllegalStateException("boom"); return photoLists[token] ?: CloudResult.Ok(emptyList()) }
    /** 받은 주소 (실패한 것 포함) */
    val downloads = mutableListOf<String>()
    var downloadFails: (String) -> Boolean = { false }
    override suspend fun download(url: String, dest: File): CloudResult<Unit> {
        calls += "download"; downloads += url
        if (downloadFails(url)) return CloudResult.Fail(null, "network")
        dest.parentFile?.mkdirs(); dest.writeText(url); return CloudResult.Ok(Unit)
    }
    var deletePhotosResult: CloudResult<Unit> = CloudResult.Ok(Unit)
    val deletedPhotos = mutableListOf<Pair<String, List<String>>>()
    override suspend fun deletePhotos(token: String, ids: List<String>): CloudResult<Unit> {
        calls += "deletePhotos"; deletedPhotos += token to ids; return deletePhotosResult
    }
    data class OutsideCall(val lat: Double, val lon: Double, val atMs: Long, val parts: Set<String>)
    val outsideCalls = mutableListOf<OutsideCall>()
    /** 기본: 그 시각의 일몰만 있는 정상 응답 */
    var outsideAnswer: (OutsideCall) -> CloudResult<String> = { c ->
        CloudResult.Ok("""{"at":"${java.time.Instant.ofEpochMilli(c.atMs).atOffset(java.time.ZoneOffset.ofHours(9))}","sun":{"sunrise":"06:41","sunset":"17:36"},"errors":[]}""")
    }
    override suspend fun outside(lat: Double, lon: Double, atMs: Long, parts: Set<String>): CloudResult<String> {
        calls += "outside"; return OutsideCall(lat, lon, atMs, parts).also { outsideCalls += it }.let(outsideAnswer)
    }
    override suspend fun holidays(year: Int): CloudResult<List<LocalDate>> { calls += "holidays"; return CloudResult.Ok(emptyList()) }
    override suspend fun diary(requestJson: String): CloudResult<String> { calls += "diary"; return CloudResult.Ok("{}") }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudSyncTest {
    private lateinit var db: YeoroDb
    private lateinit var dao: YeoroDao
    private lateinit var tokens: ShareTokens
    private lateinit var files: PhotoFiles
    private lateinit var root: File
    private val api = FakeCloudApi()
    private var consent = true
    private var network = true
    private var nowMs = 1000L
    private val forgotten = mutableListOf<Long>()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, YeoroDb::class.java).allowMainThreadQueries().build()
        dao = db.dao()
        tokens = ShareTokens(ctx.getSharedPreferences("cloud_sync_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        root = File(ctx.cacheDir, "photos_test").also { it.deleteRecursively() }
        files = PhotoFiles(root)
    }
    @After fun tearDown() { db.close(); root.deleteRecursively() }

    private val attemptPrefs by lazy { ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("outside_attempts_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() } }
    private fun sync(enabled: Boolean = true, a: CloudApi = api, weatherAt: (Long) -> Long = { it }) =
        CloudSync(a, dao, tokens, files, consent = { consent }, now = { nowMs }, enabled = enabled, forgetOpened = { forgotten += it }, canNetworkNow = { network },
            weatherAt = weatherAt, attempts = attemptPrefs)
    private fun trip(dest: String? = "강릉 경포해변") = runBlocking { dao.insertTrip(TripEntity(startMs = 500L, destination = dest)) }
    /** QR을 띄운 여행(토큰 있음) */
    private fun shownTrip(): Pair<Long, String> { val id = trip(); return id to tokens.token(id) }

    @Test fun `동의 꺼짐이면 등록·삭제·sync 모두 api 0회`() = runBlocking {
        consent = false
        val (a, _) = shownTrip()
        val (b, _) = shownTrip(); tokens.markRegistered(b)
        val s = sync()
        assertFalse(s.ensureRegistered(a))
        assertEquals(SyncReport(), s.syncOnce("test"))
        s.deleteTrip(b)
        assertEquals(emptyList<String>(), api.calls)
        assertNull(dao.tripById(b))   // 차 안에서는 지운다. 서버 삭제는 큐에 남아 동의를 켜면 보낸다
        assertEquals(1, dao.pendingDeletes().size)
    }

    @Test fun `enabled false(키 없음)도 api 0회`() = runBlocking {
        val (a, _) = shownTrip()
        val (b, _) = shownTrip(); tokens.markRegistered(b)
        val s = sync(enabled = false)
        assertFalse(s.ensureRegistered(a))
        assertEquals(SyncReport(), s.syncOnce("test"))
        s.deleteTrip(b)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test fun `등록 201이면 표시하고 true(새로 등록), 다시 부르면 호출 없이 false`() = runBlocking {
        val (id, token) = shownTrip()
        val s = sync()
        assertTrue(s.ensureRegistered(id))
        assertTrue(tokens.isRegistered(id))
        assertEquals(listOf(Triple(token, 500L, "강릉 경포해변")), api.registered)
        assertFalse(s.ensureRegistered(id))
        assertEquals(1, api.calls.size)
    }

    @Test fun `등록 409면 표시하지 않음`() = runBlocking {
        api.register = CloudResult.Fail(409, "token_taken")
        val (id, _) = shownTrip()
        assertFalse(sync().ensureRegistered(id))
        assertFalse(tokens.isRegistered(id))
    }

    @Test fun `syncOnce는 QR을 띄웠지만 미등록인 여행을 등록하고, 없는 여행의 토큰은 버린다`() = runBlocking {
        val (a, _) = shownTrip()
        val (b, _) = shownTrip(); tokens.markRegistered(b)
        trip()                         // QR을 띄우지 않은 여행: 등록하지 않는다
        tokens.token(999)              // 여행이 없는 토큰
        val r = sync().syncOnce("test")
        assertEquals(1, r.registered)
        assertTrue(tokens.isRegistered(a))
        assertEquals(1, api.registered.size)
        assertNull(tokens.tokenOrNull(999))
    }

    @Test fun `오프라인 삭제는 큐에 남고 다음 sync에 204로 비워진다`() = runBlocking {
        val (id, token) = shownTrip(); tokens.markRegistered(id)
        File(files.dir(id), "x.jpg").apply { parentFile!!.mkdirs(); writeText("jpg") }
        dao.upsertPhotos(listOf(TripPhotoEntity("p1", id, null, "none", 1, 10, 10, null, null)))
        api.delete = CloudResult.Fail(null, "network")
        val s = sync()
        s.deleteTrip(id)
        // 차 안: Room(사진 cascade)·파일·토큰·열어 본 표시 없음
        assertNull(dao.tripById(id))
        assertEquals(0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM trip_photos").use { it.moveToFirst(); it.getInt(0) })
        assertFalse(files.dir(id).exists())
        assertNull(tokens.tokenOrNull(id)); assertFalse(tokens.isRegistered(id))
        assertEquals(listOf(id), forgotten)
        assertEquals(listOf(token), dao.pendingDeletes().map { it.token })
        // 다음 sync: 204
        api.delete = CloudResult.Ok(Unit)
        assertEquals(1, s.syncOnce("test").deleted)
        assertTrue(dao.pendingDeletes().isEmpty())
        assertEquals(listOf("delete:$token", "delete:$token"), api.calls)
    }

    @Test fun `삭제 큐 - 404도 비우고, 다른 실패는 남긴다`() = runBlocking {
        dao.insertPendingDelete(PendingDeleteEntity("gone", 1))
        dao.insertPendingDelete(PendingDeleteEntity("busy", 2))
        val s = CloudSync(object : CloudApi by api {
            override suspend fun deleteTrip(token: String) = if (token == "gone") CloudResult.Fail(404, "unknown_token") else CloudResult.Fail(500, "server_error")
        }, dao, tokens, files, { true }, { 0L }, enabled = true)
        assertEquals(1, s.syncOnce("test").deleted)
        assertEquals(listOf("busy"), dao.pendingDeletes().map { it.token })
    }

    @Test fun `QR을 띄운 적 없는(토큰 없는) 여행 삭제는 서버를 부르지 않는다`() = runBlocking {
        val id = trip()
        sync().deleteTrip(id)
        assertNull(dao.tripById(id))
        assertTrue(dao.pendingDeletes().isEmpty())
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test fun `토큰이 있으면 등록 표시가 없어도 서버 삭제를 보낸다(404는 비움)`() = runBlocking {
        val (id, token) = shownTrip()
        api.delete = CloudResult.Fail(404, "unknown_token")
        sync().deleteTrip(id)
        assertNull(dao.tripById(id))
        assertEquals(listOf("delete:$token"), api.calls)
        assertTrue(dao.pendingDeletes().isEmpty())
    }

    @Test fun `한 단계가 실패해도 다음 단계를 한다`() = runBlocking {
        dao.insertPendingDelete(PendingDeleteEntity("boom", 1))
        val (id, _) = shownTrip()
        val s = CloudSync(object : CloudApi by api {
            override suspend fun deleteTrip(token: String): CloudResult<Unit> = throw IllegalStateException("boom")
        }, dao, tokens, files, { true }, { 0L }, enabled = true)
        assertEquals(1, s.syncOnce("test").registered)
        assertTrue(tokens.isRegistered(id))
    }

    /** registerTrip 안에서 멈춰 서는 가짜 서버 (mutex를 쥔 채 오래 걸리는 동기화) */
    private inner class StuckApi : CloudApi by api {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun registerTrip(token: String, startedAtMs: Long, destName: String?): CloudResult<Unit> {
            entered.complete(Unit); release.await(); return api.registerTrip(token, startedAtMs, destName)
        }
    }

    @Test fun `동기화가 서버에서 멈춰 있어도 차 안 삭제는 바로 끝난다`() = runBlocking {
        val stuck = StuckApi()
        val s = sync(a = stuck)
        shownTrip()                                    // 미등록 QR 여행: syncOnce가 registerTrip에서 멈춘다
        val (id, token) = shownTrip(); tokens.markRegistered(id)
        File(files.dir(id), "x.jpg").apply { parentFile!!.mkdirs(); writeText("jpg") }
        val syncJob = launch(Dispatchers.Default) { s.syncOnce("test") }
        withTimeout(5_000) { stuck.entered.await() }
        val del = launch(Dispatchers.Default) { s.deleteTrip(id) }
        // 차 안 부분은 mutex를 기다리지 않는다
        withTimeout(5_000) { while (dao.tripById(id) != null || tokens.tokenOrNull(id) != null || files.dir(id).exists()) delay(10) }
        assertEquals(listOf(token), dao.pendingDeletes().map { it.token })
        assertEquals(listOf(id), forgotten)
        // 큐 비우기는 mutex 뒤에서 기다리는 중. 취소해도 큐에 남아 다음에 보낸다
        delay(50); assertTrue(del.isActive)
        del.cancel(); del.join()
        stuck.release.complete(Unit); syncJob.join()
        assertEquals(listOf(token), dao.pendingDeletes().map { it.token })
        assertEquals(1, s.syncOnce("test").deleted)
    }

    @Test fun `삭제를 시작하자마자 취소해도 차 안 상태는 한꺼번에 지워진다`() = runBlocking {
        val (id, token) = shownTrip(); tokens.markRegistered(id)
        File(files.dir(id), "x.jpg").apply { parentFile!!.mkdirs(); writeText("jpg") }
        network = false                                // 큐만 남기게
        val job = launch(start = CoroutineStart.UNDISPATCHED) { sync().deleteTrip(id) }
        job.cancel(); job.join()
        assertNull(dao.tripById(id))
        assertEquals(listOf(token), dao.pendingDeletes().map { it.token })
        assertNull(tokens.tokenOrNull(id)); assertFalse(files.dir(id).exists()); assertEquals(listOf(id), forgotten)
    }

    @Test fun `큐를 비우는 도중 동의가 꺼지면 더 부르지 않는다`() = runBlocking {
        listOf("a", "b", "c").forEachIndexed { i, t -> dao.insertPendingDelete(PendingDeleteEntity(t, i.toLong())) }
        shownTrip()
        val s = sync(a = object : CloudApi by api {
            override suspend fun deleteTrip(token: String): CloudResult<Unit> { consent = false; return api.deleteTrip(token) }
        })
        val r = s.syncOnce("test")
        assertEquals(listOf("delete:a"), api.calls)   // 등록 단계도 부르지 않는다
        assertEquals(1, r.deleted)
        assertEquals(listOf("b", "c"), dao.pendingDeletes().map { it.token })
    }

    @Test fun `도중에 D로 바뀌면 더 부르지 않고, 여행을 닫을 때(close)는 기어와 상관없이 보낸다`() = runBlocking {
        listOf("a", "b").forEachIndexed { i, t -> dao.insertPendingDelete(PendingDeleteEntity(t, i.toLong())) }
        val (id, _) = shownTrip()
        val s = sync(a = object : CloudApi by api {
            override suspend fun deleteTrip(token: String): CloudResult<Unit> { network = false; return api.deleteTrip(token) }
        })
        s.syncOnce("parked")
        assertEquals(listOf("delete:a"), api.calls)
        assertFalse(s.ensureRegistered(id))            // 주행 중: 도착 등록도 시작하지 않는다
        assertEquals(listOf("delete:a"), api.calls)
        val r = s.syncOnce("close")
        assertEquals(1, r.deleted); assertEquals(1, r.registered)
    }

    // ── 그날의 바깥 (syncOutside) ──
    private val day = 24 * 3_600_000L
    private val gyeongpo = 37.8055 to 128.9087
    /** 경포로 간 여행: 출발 샘플(마포) → 도착 샘플(경포 반경 안, 3분 머묾). endMs가 null이면 열린 여행 */
    private fun destTrip(startMs: Long, endMs: Long?, coords: Boolean = true): Long = runBlocking {
        val id = dao.insertTrip(TripEntity(startMs = startMs, endMs = endMs, destination = "강릉 경포해변", state = if (endMs == null) "TRIP" else "ALBUM_READY",
            destLat = if (coords) gyeongpo.first else null, destLon = if (coords) gyeongpo.second else null))
        dao.insertSample(LocationSampleEntity(tripId = id, timeMs = startMs + 60_000, lat = 37.5563, lon = 126.9220))
        dao.insertSample(LocationSampleEntity(tripId = id, timeMs = startMs + 4 * 3_600_000, lat = 37.8056, lon = 128.9088))
        dao.insertSample(LocationSampleEntity(tripId = id, timeMs = startMs + 4 * 3_600_000 + 180_000, lat = 37.8056, lon = 128.9088))
        id
    }
    private fun stored(id: Long) = runBlocking { dao.outsideNow(id)?.let { parseTripOutside(it.json) } }

    @Test fun `도착 직후 - 목적지 좌표와 도착 시각으로 arrival만 받는다`() = runBlocking {
        nowMs = 100 * day
        val id = destTrip(nowMs - 5 * 3_600_000, endMs = null)
        val r = sync().syncOnce("arrival", OutsideRequest(id, nowMs))
        assertEquals(1, r.outside)
        assertEquals(listOf(FakeCloudApi.OutsideCall(gyeongpo.first, gyeongpo.second, nowMs, emptySet())), api.outsideCalls)
        val o = stored(id)!!
        assertEquals(nowMs, o.arrival!!.atMs)
        assertNull(o.departure)
    }

    @Test fun `여행을 닫을 때 - 첫 샘플로 departure, 비어 있는 arrival도 받는다`() = runBlocking {
        nowMs = 100 * day
        val start = nowMs - 8 * 3_600_000
        val id = destTrip(start, endMs = nowMs - 60_000)
        val r = sync().syncOnce(CloudSync.REASON_CLOSE, closedTrip = id)
        assertEquals(1, r.outside)
        assertEquals(setOf(
            FakeCloudApi.OutsideCall(37.5563, 126.9220, start + 60_000, emptySet()),                       // 출발: 첫 샘플
            FakeCloudApi.OutsideCall(gyeongpo.first, gyeongpo.second, start + 4 * 3_600_000, emptySet()),  // 도착: 목적지 좌표, 머문 샘플 시각
        ), api.outsideCalls.toSet())
        val o = stored(id)!!
        assertEquals(start + 60_000, o.departure!!.atMs)
        assertEquals(start + 4 * 3_600_000, o.arrival!!.atMs)
        // 다 받았으면 다시 묻지 않는다
        api.outsideCalls.clear()
        assertEquals(0, sync().syncOnce("parked").outside)
        assertEquals(emptyList<FakeCloudApi.OutsideCall>(), api.outsideCalls)
    }

    @Test fun `도착 때 arrival을 받은 여행도 닫을 때 departure를 받는다`() = runBlocking {
        nowMs = 100 * day
        val start = nowMs - 8 * 3_600_000
        val id = destTrip(start, endMs = null)
        sync().syncOnce("arrival", OutsideRequest(id, start + 4 * 3_600_000))
        dao.updateTrip(dao.tripById(id)!!.copy(endMs = nowMs - 60_000, state = "ALBUM_READY"))
        api.outsideCalls.clear()
        assertEquals(0, sync().syncOnce("parked").outside)    // arrival이 있으면 평소 sync는 다시 묻지 않는다
        assertEquals(1, sync().syncOnce(CloudSync.REASON_CLOSE, closedTrip = id).outside)
        assertEquals(listOf(FakeCloudApi.OutsideCall(37.5563, 126.9220, start + 60_000, emptySet())), api.outsideCalls)
        val o = stored(id)!!
        assertEquals(start + 4 * 3_600_000, o.arrival!!.atMs)
        assertEquals(start + 60_000, o.departure!!.atMs)
    }

    @Test fun `닫힌 지 3일 안이고 arrival이 비었으면 다시 묻고, 3일이 지나면 묻지 않는다`() = runBlocking {
        nowMs = 100 * day
        val inside = destTrip(nowMs - 3 * day, endMs = nowMs - 3 * day + 3_600_000)          // 닫힌 지 3일 안
        val outside = destTrip(nowMs - 4 * day, endMs = nowMs - 3 * day - 60_000)            // 3일 넘음
        val noArrival = destTrip(nowMs - 2 * day, endMs = nowMs - 2 * day + 3_600_000)       // departure만 있음
        dao.upsertOutside(TripOutsideEntity(noArrival, 1, tripOutsideJson(null, """{"at":"2026-10-24T08:52:00+09:00","sun":{"sunrise":"06:41","sunset":"17:36"}}""")))
        val done = destTrip(nowMs - day, endMs = nowMs - day + 3_600_000)                     // arrival 있음
        dao.upsertOutside(TripOutsideEntity(done, 1, tripOutsideJson("""{"at":"2026-10-24T13:20:00+09:00","sun":null}""", null)))
        api.outsideAnswer = { CloudResult.Fail(null, "network") }
        assertEquals(0, sync().syncOnce("parked").outside)   // 실패: 아무것도 저장하지 않는다
        assertNull(stored(inside))
        val tried = api.outsideCalls.map { it.atMs }.toSet()
        assertTrue(tried.any { it in (nowMs - 3 * day)..(nowMs - 3 * day + 4 * 3_600_000 + 180_000) })
        assertTrue(tried.none { it in (nowMs - 4 * day)..(nowMs - 3 * day - 60_000) })
        assertEquals(1, api.outsideCalls.count { it.atMs in (nowMs - 2 * day)..(nowMs - day - 1) })   // departure가 있으니 arrival만
        assertTrue(api.outsideCalls.none { it.atMs >= nowMs - day })
        // 한 시간 뒤 sync에서 성공하면 저장한다 (departure는 그대로 둔다)
        nowMs += CloudSync.OUTSIDE_BACKOFF_MS
        api.outsideAnswer = FakeCloudApi().outsideAnswer
        api.outsideCalls.clear()
        assertEquals(2, sync().syncOnce("parked").outside)
        assertNotNull(stored(inside)!!.arrival)
        assertNotNull(stored(noArrival)!!.arrival)
        assertEquals(parseOutside("""{"at":"2026-10-24T08:52:00+09:00","sun":{"sunrise":"06:41","sunset":"17:36"}}"""), stored(noArrival)!!.departure)
    }

    @Test fun `하드 실패한 여행은 한 시간에 한 번만 다시 묻는다 (오프라인은 제외)`() = runBlocking {
        nowMs = 100 * day
        val id = destTrip(nowMs - day, endMs = nowMs - day + 3_600_000)
        api.outsideAnswer = { CloudResult.Fail(500, "http_500") }
        val s = sync()
        s.syncOnce("parked"); val first = api.outsideCalls.size
        assertTrue(first > 0)
        nowMs += 3_600_000 - 1
        s.syncOnce("parked"); assertEquals(first, api.outsideCalls.size)           // 한 시간 안: 묻지 않는다
        nowMs += 1
        s.syncOnce("parked"); assertEquals(first * 2, api.outsideCalls.size)       // 한 시간 지남: 한 번 더
        // 새 인스턴스(프로세스 재시작)도 prefs로 기억한다
        nowMs += 10
        sync().syncOnce("parked"); assertEquals(first * 2, api.outsideCalls.size)
        // 성공하면 기록을 지운다
        nowMs += 3_600_000
        api.outsideAnswer = FakeCloudApi().outsideAnswer
        assertEquals(1, sync().syncOnce("parked").outside)
        assertNotNull(stored(id)!!.arrival)
        // 오프라인은 하드 실패가 아니다: 바로 다음 sync에서 다시 묻는다
        val id2 = destTrip(nowMs - day, endMs = nowMs - day + 3_600_000)
        api.outsideAnswer = { CloudResult.Fail(null, "offline") }
        api.outsideCalls.clear()
        sync().syncOnce("parked"); val n = api.outsideCalls.size
        sync().syncOnce("parked"); assertEquals(n * 2, api.outsideCalls.size)
        assertNull(stored(id2))
    }

    @Test fun `close에서도 기어 예외는 방금 닫은 여행에만, 다른 여행의 재시도는 P단이 아니면 하지 않는다`() = runBlocking {
        nowMs = 100 * day
        val closed = destTrip(nowMs - 8 * 3_600_000, endMs = nowMs - 60_000)
        val older = destTrip(nowMs - day, endMs = nowMs - day + 3_600_000)
        network = false   // 주행 중
        val r = sync().syncOnce(CloudSync.REASON_CLOSE, closedTrip = closed)
        assertEquals(1, r.outside)
        assertNotNull(stored(closed)); assertNull(stored(older))
        assertTrue(api.outsideCalls.none { it.atMs in (nowMs - day)..(nowMs - day + 5 * 3_600_000) })
        network = true
        assertEquals(1, sync().syncOnce("parked").outside)
        assertNotNull(stored(older))
    }

    @Test fun `좌표를 모르면 건너뛴다`() = runBlocking {
        nowMs = 100 * day
        val id = runBlocking { dao.insertTrip(TripEntity(startMs = nowMs - day, endMs = nowMs - 60_000, destination = "어딘가", state = "ALBUM_READY")) }
        val r = sync().syncOnce(CloudSync.REASON_CLOSE, OutsideRequest(id, nowMs - 3_600_000))
        assertEquals(0, r.outside)
        assertEquals(emptyList<FakeCloudApi.OutsideCall>(), api.outsideCalls)
    }

    @Test fun `동의 꺼짐·주행 중이면 바깥 정보도 묻지 않는다`() = runBlocking {
        nowMs = 100 * day
        val id = destTrip(nowMs - 5 * 3_600_000, endMs = null)
        consent = false
        assertEquals(SyncReport(), sync().syncOnce("arrival", OutsideRequest(id, nowMs)))
        consent = true; network = false
        assertEquals(SyncReport(), sync().syncOnce("arrival", OutsideRequest(id, nowMs)))
        assertEquals(emptyList<String>(), api.calls)
        assertNull(sync().placeKind(gyeongpo.first, gyeongpo.second, nowMs))
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test fun `시연 시계 - 날씨만 실제 지금으로 따로 묻고 합친다`() = runBlocking {
        nowMs = 100 * day
        val real = 90 * day
        val id = destTrip(nowMs - 5 * 3_600_000, endMs = null)
        api.outsideAnswer = { c ->
            if (c.parts == setOf("weather")) CloudResult.Ok("""{"at":"2026-10-28T13:00:00+09:00","weather":{"tempC":11.1,"sky":"맑음","precip":null,"kind":"observed"}}""")
            else CloudResult.Ok("""{"at":"2026-10-31T13:20:00+09:00","sun":{"sunrise":"06:41","sunset":"17:36"}}""")
        }
        sync(weatherAt = { real }).syncOnce("arrival", OutsideRequest(id, nowMs))
        assertEquals(setOf(
            FakeCloudApi.OutsideCall(gyeongpo.first, gyeongpo.second, nowMs, setOf("sun", "place", "festival", "holiday")),
            FakeCloudApi.OutsideCall(gyeongpo.first, gyeongpo.second, real, setOf("weather")),
        ), api.outsideCalls.toSet())
        val o = stored(id)!!.arrival!!
        assertEquals(Weather(11.1, "맑음", null, true), o.weather)
        assertEquals("17:36", o.sun!!.sunset)
    }

    @Test fun `목적지 종류 - place만 묻고, 실패면 null`() = runBlocking {
        api.outsideAnswer = { CloudResult.Ok("""{"at":"2026-10-28T13:00:00+09:00","place":{"kind":"TOURIST","name":"경포호수광장"}}""") }
        assertEquals(ai.yeoro.engine.PlaceKind.TOURIST, sync().placeKind(gyeongpo.first, gyeongpo.second, 5L))
        assertEquals(setOf("place"), api.outsideCalls.single().parts)
        api.outsideAnswer = { CloudResult.Fail(null, "offline") }
        assertNull(sync().placeKind(gyeongpo.first, gyeongpo.second, 5L))
        api.outsideAnswer = { CloudResult.Ok("""{"at":"2026-10-28T13:00:00+09:00","place":null}""") }
        assertNull(sync().placeKind(gyeongpo.first, gyeongpo.second, 5L))
    }

    @Test fun `networkGearOk - P만, UNKNOWN은 디버그에서만`() {
        for (g in Gear.entries) {
            assertEquals("$g release", g == Gear.P, networkGearOk(g, debuggable = false))
            assertEquals("$g debug", g == Gear.P || g == Gear.UNKNOWN, networkGearOk(g, debuggable = true))
        }
    }

    @Test fun `shouldSync 표`() {
        for (c in listOf(true, false)) for (e in listOf(true, false)) for (g in Gear.entries)
            assertEquals("$c $e $g", c && e && g == Gear.P, shouldSync(c, g, e))
    }

    // ── 가족 사진 (syncPhotos, 사진 빼기) ──
    private fun remote(id: String, takenAt: Long? = 600L, w: Int = 2048, h: Int = 1536) =
        RemotePhoto(id, takenAt, "exif", 900L, w, h, "https://x/$id/t", "https://x/$id/full")
    /** 등록된 QR 여행 */
    private fun registeredTrip(): Pair<Long, String> { val (id, token) = shownTrip(); tokens.markRegistered(id); return id to token }
    private fun photosOf(id: Long) = runBlocking { dao.photosNow(id) }

    @Test fun `새 사진만 넣고 썸네일을 받는다, 표지 사진만 원본도`() = runBlocking {
        val (id, token) = registeredTrip()
        api.photoLists[token] = CloudResult.Ok(listOf(remote("p1", w = 1536, h = 2048), remote("p2")))
        val s = sync()
        assertEquals(2, s.syncOnce("test").newPhotos)
        val rows = photosOf(id).associateBy { it.id }
        assertEquals(setOf("p1", "p2"), rows.keys)
        assertEquals(files.thumb(id, "p1").path, rows.getValue("p1").thumbFile)
        assertTrue(files.thumb(id, "p2").exists())
        assertEquals(files.full(id, "p2").path, rows.getValue("p2").fullFile)   // 가로 사진 = 표지
        assertNull(rows.getValue("p1").fullFile)
        assertEquals(setOf("https://x/p1/t", "https://x/p2/t", "https://x/p2/full"), api.downloads.toSet())
        // 한 장 더: 새 사진만 넣고 받은 것은 다시 받지 않는다
        api.downloads.clear()
        api.photoLists[token] = CloudResult.Ok(listOf(remote("p1", w = 1536, h = 2048), remote("p2"), remote("p3", w = 1000, h = 750)))
        assertEquals(1, s.syncOnce("test").newPhotos)
        assertEquals(listOf("https://x/p3/t"), api.downloads)
    }

    @Test fun `한 여행의 사진 맞추기가 던져도 나머지 여행은 계속한다`() = runBlocking {
        val (_, bad) = registeredTrip()
        val (good, goodToken) = registeredTrip()
        api.photosThrow += bad
        api.photoLists[goodToken] = CloudResult.Ok(listOf(remote("g1")))
        assertEquals(1, sync().syncOnce("test").newPhotos)
        assertEquals(listOf("g1"), photosOf(good).map { it.id })
    }

    @Test fun `이미 본 사진의 본 표시는 다시 받아도 유지된다`() = runBlocking {
        val (id, token) = registeredTrip()
        api.photoLists[token] = CloudResult.Ok(listOf(remote("p1")))
        val s = sync()
        s.syncOnce("test")
        dao.markPhotosSeen(id)
        api.photoLists[token] = CloudResult.Ok(listOf(remote("p1"), remote("p2")))
        assertEquals(1, s.syncOnce("test").newPhotos)
        assertEquals(mapOf("p1" to true, "p2" to false), photosOf(id).associate { it.id to it.seen })
    }

    @Test fun `다운로드에 실패한 사진은 썸네일 없이 두고 다음 sync에 다시 받는다`() = runBlocking {
        val (id, token) = registeredTrip()
        api.photoLists[token] = CloudResult.Ok(listOf(remote("p1")))
        api.downloadFails = { true }
        val s = sync()
        assertEquals(1, s.syncOnce("test").newPhotos)
        assertNull(photosOf(id).single().thumbFile)
        api.downloadFails = { false }
        assertEquals(0, s.syncOnce("test").newPhotos)
        assertEquals(files.thumb(id, "p1").path, photosOf(id).single().thumbFile)
        assertEquals(files.full(id, "p1").path, photosOf(id).single().fullFile)
    }

    @Test fun `올린 사람이 지운 사진(서버 목록에 없음)은 Room 행과 파일을 지운다`() = runBlocking {
        val (id, token) = registeredTrip()
        api.photoLists[token] = CloudResult.Ok(listOf(remote("p1"), remote("p2")))
        val s = sync()
        s.syncOnce("test")
        assertTrue(files.thumb(id, "p2").exists())
        api.photoLists[token] = CloudResult.Ok(listOf(remote("p1")))
        s.syncOnce("test")
        assertEquals(listOf("p1"), photosOf(id).map { it.id })
        assertFalse(files.thumb(id, "p2").exists()); assertFalse(files.full(id, "p2").exists())
        // 목록을 받지 못하면(실패) 아무것도 지우지 않는다
        api.photoLists[token] = CloudResult.Fail(null, "network")
        s.syncOnce("test")
        assertEquals(listOf("p1"), photosOf(id).map { it.id })
    }

    @Test fun `사진 단계는 등록된 30일 안의 여행만, 동의 꺼짐·주행 중·close여도 P단이 아니면 부르지 않는다`() = runBlocking {
        val (id, token) = registeredTrip()
        shownTrip().let { (_, t) -> api.photoLists[t] = CloudResult.Ok(listOf(remote("x9"))) }   // 미등록 → 등록 단계가 등록한 뒤 사진도 본다
        api.photoLists[token] = CloudResult.Ok(listOf(remote("p1")))
        network = false
        sync().syncOnce(CloudSync.REASON_CLOSE)
        assertFalse("photos" in api.calls)
        assertTrue(photosOf(id).isEmpty())
        network = true; consent = false
        sync().syncOnce("test")
        assertFalse("photos" in api.calls)
        consent = true
        nowMs = 500L + 31L * 24 * 3_600_000          // 출발 31일 뒤
        sync().syncOnce("test")
        assertFalse("photos" in api.calls)
        nowMs = 1000L
        sync().syncOnce("test")
        assertEquals(2, api.calls.count { it == "photos" })
    }

    @Test fun `차에서 사진 빼기 - 서버 204면 행·파일 지우고 큐 없음`() = runBlocking {
        val (id, token) = registeredTrip()
        api.photoLists[token] = CloudResult.Ok(listOf(remote("p1"), remote("p2")))
        val s = sync()
        s.syncOnce("test")
        assertTrue(s.removePhoto(id, "p2"))
        assertEquals(listOf(token to listOf("p2")), api.deletedPhotos)
        assertEquals(listOf("p1"), photosOf(id).map { it.id })
        assertFalse(files.thumb(id, "p2").exists()); assertFalse(files.full(id, "p2").exists())
        assertTrue(dao.photoDeletes().isEmpty())
    }

    @Test fun `사진 빼기 - 네트워크 실패면 숨기고 큐에 넣어, 다시 받지 않고 다음 sync에 지운다`() = runBlocking {
        val (id, token) = registeredTrip()
        api.photoLists[token] = CloudResult.Ok(listOf(remote("p1"), remote("p2")))
        val s = sync()
        s.syncOnce("test")
        api.deletePhotosResult = CloudResult.Fail(null, "network")
        assertFalse(s.removePhoto(id, "p2"))
        assertEquals(listOf("p1"), photosOf(id).map { it.id })
        assertEquals(listOf("p2"), dao.photoDeletes().map { it.id })
        // 서버 목록에는 아직 있지만(지우지 못함) 다시 넣지 않는다. 큐는 이번에도 실패하면 남는다
        assertEquals(0, s.syncOnce("test").newPhotos)
        assertEquals(listOf("p1"), photosOf(id).map { it.id })
        assertEquals(listOf("p2"), dao.photoDeletes().map { it.id })
        // 404(이미 지워짐)도 끝난 것으로 본다
        api.deletePhotosResult = CloudResult.Fail(404, "unknown_photo")
        s.syncOnce("test")
        assertTrue(dao.photoDeletes().isEmpty())
    }

    @Test fun `동의 꺼짐에서 사진 빼기는 차 안에서만, 서버는 부르지 않고 큐에 남긴다`() = runBlocking {
        val (id, _) = registeredTrip()
        dao.upsertPhotos(listOf(TripPhotoEntity("p1", id, null, "none", 1, 10, 10, null, null)))
        consent = false
        assertFalse(sync().removePhoto(id, "p1"))
        assertTrue(photosOf(id).isEmpty())
        assertEquals(listOf("p1"), dao.photoDeletes().map { it.id })
        assertFalse("deletePhotos" in api.calls)
    }
}
