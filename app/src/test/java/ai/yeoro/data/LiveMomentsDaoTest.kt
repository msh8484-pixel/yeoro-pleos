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

/** 지금 화면 "기록 중" 목록: 후보 단계는 버퍼 순간, 여행 중에는 열린 여행의 순간. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveMomentsDaoTest {
    private lateinit var db: YeoroDb
    private lateinit var dao: YeoroDao
    private lateinit var repo: TripRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), YeoroDb::class.java).allowMainThreadQueries().build()
        dao = db.dao(); repo = TripRepository(dao)
    }
    @After fun tearDown() { db.close() }

    private fun m(t: Long, trip: Long? = null) = MomentEntity(tripId = trip, timeMs = t, type = "STOP", detail = "12")

    @Test fun bufferMoments_onlyTripless_inTimeOrder() = runBlocking {
        val trip = dao.insertTrip(TripEntity(startMs = 0))
        dao.insertMoment(m(300)); dao.insertMoment(m(100)); dao.insertMoment(m(200, trip))
        assertEquals(listOf(100L, 300L), repo.bufferMoments().first().map { it.timeMs })
    }

    @Test fun openTrip_flowFollowsOpenTrip() = runBlocking {
        assertNull(repo.openTrip().first())
        val closed = dao.insertTrip(TripEntity(startMs = 0, endMs = 50))
        val open = dao.insertTrip(TripEntity(startMs = 100))
        assertEquals(open, repo.openTrip().first()?.id)
        dao.updateTrip(dao.tripById(open)!!.copy(endMs = 200))
        assertNull(repo.openTrip().first())
        assertNotEquals(closed, open)
    }
}
