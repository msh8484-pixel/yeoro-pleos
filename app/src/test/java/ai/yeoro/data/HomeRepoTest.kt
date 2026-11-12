package ai.yeoro.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 홈(책장)이 쓰는 저장소 기능: 여행별 거리, 확인 안 한 여행 남기기/지우기, 열어 본 앨범 표시. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeRepoTest {
    private lateinit var db: YeoroDb
    private lateinit var dao: YeoroDao
    private lateinit var repo: TripRepository
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, YeoroDb::class.java).allowMainThreadQueries().build()
        dao = db.dao(); repo = TripRepository(dao)
    }
    @After fun tearDown() { db.close() }

    @Test fun tripKm_sumsSamplesInTimeOrder_perTrip_ignoresBuffer() = runBlocking {
        val a = dao.insertTrip(TripEntity(startMs = 0, endMs = 10, state = "ALBUM_READY"))
        val b = dao.insertTrip(TripEntity(startMs = 0, endMs = 10, state = "ALBUM_READY"))
        // 위도 0.01도 ≈ 1.112km. 순서를 섞어 넣어도 시간순으로 잇는다.
        dao.insertSample(LocationSampleEntity(tripId = a, timeMs = 3, lat = 37.02, lon = 127.0))
        dao.insertSample(LocationSampleEntity(tripId = a, timeMs = 1, lat = 37.00, lon = 127.0))
        dao.insertSample(LocationSampleEntity(tripId = a, timeMs = 2, lat = 37.01, lon = 127.0))
        dao.insertSample(LocationSampleEntity(tripId = b, timeMs = 1, lat = 37.0, lon = 127.0))
        dao.insertSample(LocationSampleEntity(tripId = null, timeMs = 1, lat = 38.0, lon = 127.0))
        val km = repo.tripKm().first()
        assertEquals(2.224, km.getValue(a), 0.01)
        assertEquals(0.0, km.getValue(b), 1e-9)
        assertEquals(setOf(a, b), km.keys)
    }

    @Test fun tripMoments_groupsByTrip() = runBlocking {
        val a = dao.insertTrip(TripEntity(startMs = 0))
        dao.insertMoment(MomentEntity(tripId = a, timeMs = 1, type = "RAIN_START", detail = ""))
        dao.insertMoment(MomentEntity(tripId = null, timeMs = 1, type = "RAIN_START", detail = ""))
        val m = repo.tripMoments().first()
        assertEquals(setOf(a), m.keys); assertEquals(1, m.getValue(a).size)
    }

    @Test fun keepUnconfirmed_becomesAlbumReady_onlyFromUnconfirmed() = runBlocking {
        val u = dao.insertTrip(TripEntity(startMs = 0, endMs = 5, state = "UNCONFIRMED"))
        val open = dao.insertTrip(TripEntity(startMs = 1, state = "TRIP"))
        repo.confirmUnconfirmed(u); repo.confirmUnconfirmed(open)
        assertEquals("ALBUM_READY", dao.tripById(u)!!.state)
        assertEquals("TRIP", dao.tripById(open)!!.state)
        assertEquals(u, dao.latestAlbumTrip().first()!!.id)
    }

    @Test fun discardUnconfirmed_deletesTripAndCascades() = runBlocking {
        val u = dao.insertTrip(TripEntity(startMs = 0, endMs = 5, state = "UNCONFIRMED"))
        dao.insertSample(LocationSampleEntity(tripId = u, timeMs = 1, lat = 37.0, lon = 127.0))
        dao.insertMoment(MomentEntity(tripId = u, timeMs = 1, type = "STOP", detail = "5"))
        dao.deleteTrip(u)
        assertNull(dao.tripById(u))
        assertTrue(repo.tripKm().first().isEmpty())
        assertTrue(repo.tripMoments().first().isEmpty())
    }

    @Test fun openedAlbums_markAndForget_persist() = runBlocking {
        OpenedAlbums.clear(ctx)
        assertEquals(emptySet<Long>(), OpenedAlbums.flow(ctx).first())
        OpenedAlbums.mark(ctx, 3); OpenedAlbums.mark(ctx, 7); OpenedAlbums.mark(ctx, 3)
        assertEquals(setOf(3L, 7L), OpenedAlbums.flow(ctx).first())
        OpenedAlbums.forget(ctx, 7)
        assertEquals(setOf(3L), OpenedAlbums.flow(ctx).first())
    }
}
