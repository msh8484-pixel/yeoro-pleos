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
class YeoroDaoTest {
    private lateinit var db: YeoroDb
    private lateinit var dao: YeoroDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), YeoroDb::class.java).allowMainThreadQueries().build()
        dao = db.dao()
    }
    @After fun tearDown() { db.close() }

    private fun m(t: Long, trip: Long? = null) = MomentEntity(tripId = trip, timeMs = t, type = "X", detail = "d$t")
    private fun bufferTimes() = runBlocking { db.openHelper.readableDatabase.query("SELECT timeMs FROM moments WHERE tripId IS NULL ORDER BY timeMs").use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() } }

    @Test fun attachBuffer_movesOnlyBufferMomentsAtOrAfterSince() = runBlocking {
        val other = dao.insertTrip(TripEntity(startMs = 0))
        dao.insertMoment(m(50)); dao.insertMoment(m(100)); dao.insertMoment(m(200)); dao.insertMoment(m(150, other))
        val id = dao.insertTrip(TripEntity(startMs = 100))
        dao.attachBuffer(id, 100)
        assertEquals(listOf(100L, 200L), dao.moments(id).first().map { it.timeMs })
        assertEquals(listOf(150L), dao.moments(other).first().map { it.timeMs })
        assertEquals(listOf(50L), bufferTimes())
    }
    @Test fun purgeBuffer_deletesOnlyOldBufferMoments() = runBlocking {
        val t = dao.insertTrip(TripEntity(startMs = 0))
        dao.insertMoment(m(10)); dao.insertMoment(m(500)); dao.insertMoment(m(10, t))
        dao.purgeBuffer(100)
        assertEquals(listOf(500L), bufferTimes())
        assertEquals(1, dao.moments(t).first().size)
    }
    @Test fun deleteBuffer_leavesTripMoments() = runBlocking {
        val t = dao.insertTrip(TripEntity(startMs = 0))
        dao.insertMoment(m(10)); dao.insertMoment(m(20, t))
        dao.deleteBuffer()
        assertEquals(emptyList<Long>(), bufferTimes())
        assertEquals(1, dao.moments(t).first().size)
    }
    @Test fun openTrip_returnsLatestOpen() = runBlocking {
        dao.insertTrip(TripEntity(startMs = 1, endMs = 5))
        dao.insertTrip(TripEntity(startMs = 10))
        val latest = dao.insertTrip(TripEntity(startMs = 20))
        dao.insertTrip(TripEntity(startMs = 30, endMs = 40))
        assertEquals(latest, dao.openTrip()?.id)
    }
    @Test fun deletingTrip_cascadesItsMoments() = runBlocking {
        val a = dao.insertTrip(TripEntity(startMs = 0)); val b = dao.insertTrip(TripEntity(startMs = 1))
        dao.insertMoment(m(1, a)); dao.insertMoment(m(2, b)); dao.insertMoment(m(3))
        dao.deleteTrip(a)
        assertEquals(0, dao.moments(a).first().size)
        assertEquals(1, dao.moments(b).first().size)
        assertEquals(listOf(3L), bufferTimes())
    }
    @Test fun keepUnconfirmed_insertAndAttach_movesBufferIntoUnconfirmedTrip() = runBlocking {
        dao.insertMoment(m(50)); dao.insertMoment(m(100)); dao.insertMoment(m(300))
        // RecorderService의 Action.KeepUnconfirmed 처리와 같은 순서
        val id = dao.insertTrip(TripEntity(startMs = 100, endMs = 400, state = "UNCONFIRMED", destination = null))
        dao.attachBuffer(id, 100)
        val t = dao.tripById(id)!!
        assertEquals("UNCONFIRMED", t.state); assertEquals(400L, t.endMs); assertNull(t.destination)
        assertEquals(listOf(100L, 300L), dao.moments(id).first().map { it.timeMs })
        assertEquals(listOf(50L), bufferTimes())
        assertNull(dao.openTrip())   // 닫힌 여행이라 복원 대상이 아니다
    }
}
