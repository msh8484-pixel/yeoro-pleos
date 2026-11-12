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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YeoroDaoV3Test {
    private lateinit var db: YeoroDb
    private lateinit var dao: YeoroDao
    private lateinit var repo: TripRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), YeoroDb::class.java).allowMainThreadQueries().build()
        dao = db.dao(); repo = TripRepository(dao)
    }
    @After fun tearDown() { db.close() }

    private fun s(t: Long, trip: Long? = null) = LocationSampleEntity(tripId = trip, timeMs = t, lat = 37.0, lon = 127.0)
    private fun bufferSamples() = db.openHelper.readableDatabase.query("SELECT timeMs FROM location_samples WHERE tripId IS NULL ORDER BY timeMs").use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }

    @Test fun attachBuffer_movesSamplesAtOrAfterSince() = runBlocking {
        val other = dao.insertTrip(TripEntity(startMs = 0))
        dao.insertSample(s(50)); dao.insertSample(s(100)); dao.insertSample(s(200)); dao.insertSample(s(150, other))
        val id = dao.insertTrip(TripEntity(startMs = 100))
        dao.attachBuffer(id, 100)
        assertEquals(listOf(100L, 200L), dao.samples(id).first().map { it.timeMs })
        assertEquals(listOf(150L), dao.samples(other).first().map { it.timeMs })
        assertEquals(listOf(50L), bufferSamples())
    }
    @Test fun purgeBuffer_deletesOnlyOldBufferSamples() = runBlocking {
        val t = dao.insertTrip(TripEntity(startMs = 0))
        dao.insertSample(s(10)); dao.insertSample(s(500)); dao.insertSample(s(10, t))
        dao.purgeBuffer(100)
        assertEquals(listOf(500L), bufferSamples())
        assertEquals(1, dao.samples(t).first().size)
    }
    @Test fun deleteBuffer_clearsBufferSamplesKeepsTripSamples() = runBlocking {
        val t = dao.insertTrip(TripEntity(startMs = 0))
        dao.insertSample(s(10)); dao.insertSample(s(20, t))
        dao.deleteBuffer()
        assertEquals(emptyList<Long>(), bufferSamples())
        assertEquals(1, dao.samples(t).first().size)
    }
    @Test fun deletingTrip_cascadesSamples() = runBlocking {
        val a = dao.insertTrip(TripEntity(startMs = 0)); val b = dao.insertTrip(TripEntity(startMs = 1))
        dao.insertSample(s(1, a)); dao.insertSample(s(2, b)); dao.insertSample(s(3))
        dao.deleteTrip(a)
        assertEquals(0, dao.samples(a).first().size)
        assertEquals(1, dao.samples(b).first().size)
        assertEquals(listOf(3L), bufferSamples())
    }
    @Test fun momentCoordsAndTripDestRoundTrip() = runBlocking {
        val id = dao.insertTrip(TripEntity(startMs = 0, destLat = 37.5, destLon = 127.1, title = "t"))
        dao.insertMoment(MomentEntity(tripId = id, timeMs = 1, type = "X", detail = "d", lat = 37.4, lon = 127.0))
        val t = dao.tripById(id)!!
        assertEquals(37.5, t.destLat!!, 0.0); assertEquals("t", t.title)
        assertEquals(127.0, repo.moments(id).first().single().lon!!, 0.0)
    }

    // 위도 1도 = 약 111.2km, 0.0036도 = 약 400m, 0.0054도 = 약 600m
    private fun ended(lat: Double, end: Long) = runBlocking { dao.insertTrip(TripEntity(startMs = 0, endMs = end, destLat = lat, destLon = 127.0, state = "ALBUM_READY")) }
    @Test fun visitCount_within400m_isTwo() = runBlocking {
        ended(37.0036, 1000)
        assertEquals(2, repo.visitCount(37.0, 127.0, beforeMs = 2000))
    }
    @Test fun visitCount_beyond600m_isOne() = runBlocking {
        ended(37.0054, 1000)
        assertEquals(1, repo.visitCount(37.0, 127.0, beforeMs = 2000))
    }
    @Test fun visitCount_ignoresOpenAndLaterTrips() = runBlocking {
        dao.insertTrip(TripEntity(startMs = 0, endMs = null, destLat = 37.0, destLon = 127.0))
        ended(37.0, 3000)
        assertEquals(1, repo.visitCount(37.0, 127.0, beforeMs = 2000))
    }
    // 도착 시점: 지금 여행은 아직 열려 있어(endMs null) 세지 않고, visitCount의 +1이 이번 방문이다
    @Test fun arrivalVisit_countsThisVisitOnceWithOpenTrip() = runBlocking {
        ended(37.0036, 1000)
        dao.insertTrip(TripEntity(startMs = 1500, endMs = null, destLat = 37.0, destLon = 127.0))
        assertEquals(2, ai.yeoro.service.arrivalVisit(repo, 37.0, 127.0, nowMs = 2000))
    }
    @Test fun arrivalVisit_firstVisitAndUnknownCoordsAreOne() = runBlocking {
        dao.insertTrip(TripEntity(startMs = 1500, endMs = null, destLat = 37.0, destLon = 127.0))
        assertEquals(1, ai.yeoro.service.arrivalVisit(repo, 37.0, 127.0, nowMs = 2000))
        ended(37.0, 1000)
        assertEquals(1, ai.yeoro.service.arrivalVisit(repo, null, null, nowMs = 2000))
    }
    @Test fun confirmUnconfirmed_setsAlbumReady_andDeleteTrip() = runBlocking {
        val id = dao.insertTrip(TripEntity(startMs = 0, endMs = 5, state = "UNCONFIRMED"))
        repo.confirmUnconfirmed(id)
        assertEquals("ALBUM_READY", repo.trip(id)!!.state)
        dao.deleteTrip(id)
        assertNull(repo.trip(id))
    }
}
