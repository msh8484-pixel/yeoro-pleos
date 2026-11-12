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
class YeoroDaoV4Test {
    private lateinit var db: YeoroDb
    private lateinit var dao: YeoroDao
    private lateinit var repo: TripRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), YeoroDb::class.java).allowMainThreadQueries().build()
        dao = db.dao(); repo = TripRepository(dao)
    }
    @After fun tearDown() { db.close() }

    private fun photo(id: String, trip: Long, seen: Boolean = false, thumb: String? = null) =
        TripPhotoEntity(id, trip, takenAtMs = 10, takenAtSource = "exif", uploadedMs = 20, w = 4, h = 3, thumbFile = thumb, fullFile = null, seen = seen)

    @Test fun deletingTrip_cascadesPhotosOutsideDiary() = runBlocking {
        val a = dao.insertTrip(TripEntity(startMs = 0)); val b = dao.insertTrip(TripEntity(startMs = 1))
        dao.upsertPhotos(listOf(photo("a1", a), photo("a2", a), photo("b1", b)))
        dao.upsertOutside(TripOutsideEntity(a, 5, "{}"))
        dao.upsertDiary(TripDiaryEntity(a, "h", "{}", 6))
        dao.deleteTrip(a)
        assertTrue(dao.photos(a).first().isEmpty())
        assertEquals(listOf("b1"), dao.photos(b).first().map { it.id })
        assertNull(dao.outsideNow(a)); assertNull(dao.diary(a))
    }

    @Test fun unseenCounts_andMarkSeen() = runBlocking {
        val a = dao.insertTrip(TripEntity(startMs = 0)); val b = dao.insertTrip(TripEntity(startMs = 1))
        dao.upsertPhotos(listOf(photo("a1", a), photo("a2", a), photo("a3", a, seen = true), photo("b1", b)))
        assertEquals(setOf(TripCount(a, 2), TripCount(b, 1)), dao.unseenPhotoCounts().first().toSet())
        dao.markPhotosSeen(a)
        assertEquals(listOf(TripCount(b, 1)), dao.unseenPhotoCounts().first())
    }

    @Test fun outside_upsertReplaces() = runBlocking {
        val a = dao.insertTrip(TripEntity(startMs = 0))
        assertNull(dao.outside(a).first())
        dao.upsertOutside(TripOutsideEntity(a, 5, """{"arrival":{}}"""))
        dao.upsertOutside(TripOutsideEntity(a, 7, """{"arrival":{},"departure":{}}"""))
        assertEquals(7L, dao.outsideNow(a)!!.fetchedMs)
        assertEquals(7L, dao.outside(a).first()!!.fetchedMs)
    }

    @Test fun pendingDeletes_insertListDelete() = runBlocking {
        dao.insertPendingDelete(PendingDeleteEntity("t1", 1)); dao.insertPendingDelete(PendingDeleteEntity("t2", 2))
        dao.insertPendingDelete(PendingDeleteEntity("t1", 3))   // 같은 토큰은 하나만
        assertEquals(listOf("t1", "t2"), dao.pendingDeletes().map { it.token }.sorted())
        dao.deletePending("t1")
        assertEquals(listOf("t2"), dao.pendingDeletes().map { it.token })
    }

    @Test fun mergePhotos_keepsSeenAndFiles() = runBlocking {
        val a = dao.insertTrip(TripEntity(startMs = 0))
        dao.upsertPhotos(listOf(photo("a1", a, seen = true, thumb = "a1_t.jpg")))
        repo.mergePhotos(listOf(photo("a1", a).copy(w = 40), photo("a2", a)))
        val got = dao.photos(a).first().associateBy { it.id }
        assertTrue(got.getValue("a1").seen); assertEquals("a1_t.jpg", got.getValue("a1").thumbFile); assertEquals(40, got.getValue("a1").w)
        assertFalse(got.getValue("a2").seen)
    }
}
