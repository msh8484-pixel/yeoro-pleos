package ai.yeoro.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class YeoroDaoV5Test {
    private lateinit var db: YeoroDb
    private lateinit var dao: YeoroDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), YeoroDb::class.java).allowMainThreadQueries().build()
        dao = db.dao()
    }
    @After fun tearDown() { db.close() }

    private fun photo(id: String, trip: Long) = TripPhotoEntity(id, trip, 10, "exif", 20, 4, 3, "t", null)

    @Test fun removePhotoQueued_deletesRowAndQueuesInOneStep() = runBlocking {
        val a = dao.insertTrip(TripEntity(startMs = 0))
        dao.upsertPhotos(listOf(photo("p1", a), photo("p2", a)))
        dao.removePhotoQueued(a, "p2", 5)
        assertEquals(listOf("p1"), dao.photosNow(a).map { it.id })
        assertEquals(listOf(PhotoDeleteEntity("p2", a, 5)), dao.photoDeletes())
        dao.removePhotoQueued(a, "p2", 6)          // 두 번 눌러도 큐는 하나
        assertEquals(1, dao.photoDeletes().size)
        dao.deletePhotoDeletes(listOf("p2"))
        assertTrue(dao.photoDeletes().isEmpty())
    }

    @Test fun deletingTrip_dropsQueuedPhotoDeletes() = runBlocking {
        // 여행을 통째로 지우면 서버 쪽은 여행 삭제 큐가 맡는다
        val a = dao.insertTrip(TripEntity(startMs = 0)); val b = dao.insertTrip(TripEntity(startMs = 1))
        dao.upsertPhotos(listOf(photo("p1", a), photo("q1", b)))
        dao.removePhotoQueued(a, "p1", 1); dao.removePhotoQueued(b, "q1", 2)
        dao.deleteTrip(a)
        assertEquals(listOf("q1"), dao.photoDeletes().map { it.id })
        assertEquals(listOf("q1"), dao.hiddenPhotoIds(b))
    }

    @Test fun setPhotoFiles_keepsSeen() = runBlocking {
        val a = dao.insertTrip(TripEntity(startMs = 0))
        dao.upsertPhotos(listOf(photo("p1", a).copy(thumbFile = null, seen = true)))
        dao.setPhotoFiles("p1", "/t.jpg", "/f.jpg")
        val p = dao.photosNow(a).single()
        assertEquals("/t.jpg", p.thumbFile); assertEquals("/f.jpg", p.fullFile); assertTrue(p.seen)
    }
}
