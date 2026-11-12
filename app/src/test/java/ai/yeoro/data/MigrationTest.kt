package ai.yeoro.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MigrationTestHelper는 스키마 JSON을 assets에서 읽는데 JVM(Robolectric)에서는 연결되지 않아,
 * app/schemas/.../1.json의 DDL로 v1 파일을 직접 만든 뒤 Room이 마이그레이션 + 스키마 검증을 하게 한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @Test fun migrate1To2_keepsValidMomentsDropsOrphansAndAddsCascade() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("mig.db").also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER, `retroactive` INTEGER NOT NULL, `destination` TEXT, `state` TEXT NOT NULL)")
            execSQL("CREATE TABLE IF NOT EXISTS `moments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER, `timeMs` INTEGER NOT NULL, `type` TEXT NOT NULL, `detail` TEXT NOT NULL)")
            execSQL("CREATE INDEX IF NOT EXISTS `index_moments_tripId` ON `moments` (`tripId`)")
            execSQL("INSERT INTO trips VALUES (1, 0, NULL, 0, NULL, 'TRIP')")
            execSQL("INSERT INTO moments VALUES (1, 1, 10, 'X', 'a')")
            execSQL("INSERT INTO moments VALUES (2, NULL, 20, 'X', 'buf')")
            execSQL("INSERT INTO moments VALUES (3, 99, 30, 'X', 'orphan')")
            version = 1; close()
        }
        val db = Room.databaseBuilder(ctx, YeoroDb::class.java, "mig.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun ids() = sql.query("SELECT id FROM moments ORDER BY id").use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }
            assertEquals(listOf(1L, 2L), ids())
            sql.execSQL("DELETE FROM trips WHERE id = 1")
            assertEquals(listOf(2L), ids())
        } finally { db.close() }
    }

    @Test fun migrate2To3_keepsRowsAndAddsColumnsAndSamples() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("mig3.db").also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER, `retroactive` INTEGER NOT NULL, `destination` TEXT, `state` TEXT NOT NULL)")
            execSQL("CREATE TABLE IF NOT EXISTS `moments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER, `timeMs` INTEGER NOT NULL, `type` TEXT NOT NULL, `detail` TEXT NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            execSQL("CREATE INDEX IF NOT EXISTS `index_moments_tripId` ON `moments` (`tripId`)")
            execSQL("INSERT INTO trips VALUES (1, 0, 9, 0, '바다', 'ALBUM_READY')")
            execSQL("INSERT INTO moments VALUES (1, 1, 10, 'X', 'a')")
            version = 2; close()
        }
        val db = Room.databaseBuilder(ctx, YeoroDb::class.java, "mig3.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            sql.query("SELECT destination, state, destLat, destLon, title FROM trips WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("바다", c.getString(0)); assertEquals("ALBUM_READY", c.getString(1))
                assertTrue(c.isNull(2)); assertTrue(c.isNull(3)); assertTrue(c.isNull(4))
            }
            sql.query("SELECT detail, lat, lon FROM moments WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("a", c.getString(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2))
            }
            sql.execSQL("INSERT INTO location_samples (tripId, timeMs, lat, lon) VALUES (1, 5, 37.0, 127.0)")
            sql.execSQL("DELETE FROM trips WHERE id = 1")
            sql.query("SELECT COUNT(*) FROM location_samples").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        } finally { db.close() }
    }

    @Test fun migrate3To4_keepsRowsAndAddsCloudTables() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("mig4.db").also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            // app/schemas/.../3.json 의 DDL
            execSQL("CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER, `retroactive` INTEGER NOT NULL, `destination` TEXT, `state` TEXT NOT NULL, `destLat` REAL, `destLon` REAL, `title` TEXT)")
            execSQL("CREATE TABLE IF NOT EXISTS `moments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER, `timeMs` INTEGER NOT NULL, `type` TEXT NOT NULL, `detail` TEXT NOT NULL, `lat` REAL, `lon` REAL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            execSQL("CREATE TABLE IF NOT EXISTS `location_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER, `timeMs` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            execSQL("CREATE INDEX IF NOT EXISTS `index_moments_tripId` ON `moments` (`tripId`)")
            execSQL("CREATE INDEX IF NOT EXISTS `index_location_samples_tripId` ON `location_samples` (`tripId`)")
            execSQL("INSERT INTO trips VALUES (1, 0, 9, 0, '강릉', 'ALBUM_READY', 37.8, 128.9, '바다')")
            execSQL("INSERT INTO moments VALUES (1, 1, 10, 'STOP', '5', 37.1, 127.1)")
            execSQL("INSERT INTO location_samples VALUES (1, 1, 5, 37.0, 127.0)")
            version = 3; close()
        }
        val db = Room.databaseBuilder(ctx, YeoroDb::class.java, "mig4.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun count(t: String) = sql.query("SELECT COUNT(*) FROM $t").use { c -> c.moveToFirst(); c.getInt(0) }
            sql.query("SELECT destination, title, destLat FROM trips WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("강릉", c.getString(0)); assertEquals("바다", c.getString(1)); assertEquals(37.8, c.getDouble(2), 0.0)
            }
            assertEquals(1, count("moments")); assertEquals(1, count("location_samples"))
            // 새 표 네 개
            sql.execSQL("INSERT INTO trip_outside (tripId, fetchedMs, json) VALUES (1, 1, '{}')")
            sql.execSQL("INSERT INTO trip_photos (id, tripId, takenAtMs, takenAtSource, uploadedMs, w, h, thumbFile, fullFile, seen) VALUES ('p', 1, NULL, 'none', 2, 4, 3, NULL, NULL, 0)")
            sql.execSQL("INSERT INTO pending_deletes (token, queuedMs) VALUES ('tok', 3)")
            sql.execSQL("INSERT INTO trip_diary (tripId, factsHash, json, createdMs) VALUES (1, 'h', '{}', 4)")
            sql.execSQL("DELETE FROM trips WHERE id = 1")
            assertEquals(0, count("trip_outside")); assertEquals(0, count("trip_photos")); assertEquals(0, count("trip_diary"))
            assertEquals(1, count("pending_deletes"))   // 삭제 큐는 여행과 묶이지 않는다
        } finally { db.close() }
    }

    @Test fun migrate4To5_keepsPhotosAndAddsPhotoDeletes() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("mig5.db").also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            // app/schemas/.../4.json 의 DDL
            execSQL("CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER, `retroactive` INTEGER NOT NULL, `destination` TEXT, `state` TEXT NOT NULL, `destLat` REAL, `destLon` REAL, `title` TEXT)")
            execSQL("CREATE TABLE IF NOT EXISTS `moments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER, `timeMs` INTEGER NOT NULL, `type` TEXT NOT NULL, `detail` TEXT NOT NULL, `lat` REAL, `lon` REAL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            execSQL("CREATE INDEX IF NOT EXISTS `index_moments_tripId` ON `moments` (`tripId`)")
            execSQL("CREATE TABLE IF NOT EXISTS `location_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER, `timeMs` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            execSQL("CREATE INDEX IF NOT EXISTS `index_location_samples_tripId` ON `location_samples` (`tripId`)")
            execSQL("CREATE TABLE IF NOT EXISTS `trip_outside` (`tripId` INTEGER NOT NULL, `fetchedMs` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`tripId`), FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            execSQL("CREATE TABLE IF NOT EXISTS `trip_photos` (`id` TEXT NOT NULL, `tripId` INTEGER NOT NULL, `takenAtMs` INTEGER, `takenAtSource` TEXT NOT NULL, `uploadedMs` INTEGER NOT NULL, `w` INTEGER NOT NULL, `h` INTEGER NOT NULL, `thumbFile` TEXT, `fullFile` TEXT, `seen` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            execSQL("CREATE INDEX IF NOT EXISTS `index_trip_photos_tripId` ON `trip_photos` (`tripId`)")
            execSQL("CREATE TABLE IF NOT EXISTS `pending_deletes` (`token` TEXT NOT NULL, `queuedMs` INTEGER NOT NULL, PRIMARY KEY(`token`))")
            execSQL("CREATE TABLE IF NOT EXISTS `trip_diary` (`tripId` INTEGER NOT NULL, `factsHash` TEXT NOT NULL, `json` TEXT NOT NULL, `createdMs` INTEGER NOT NULL, PRIMARY KEY(`tripId`), FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            execSQL("INSERT INTO trips VALUES (1, 0, 9, 0, '강릉', 'ALBUM_READY', 37.8, 128.9, '바다')")
            execSQL("INSERT INTO trip_photos VALUES ('p', 1, NULL, 'none', 2, 4, 3, '/t.jpg', NULL, 1)")
            execSQL("INSERT INTO pending_deletes VALUES ('tok', 3)")
            version = 4; close()
        }
        val db = Room.databaseBuilder(ctx, YeoroDb::class.java, "mig5.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun count(t: String) = sql.query("SELECT COUNT(*) FROM $t").use { c -> c.moveToFirst(); c.getInt(0) }
            sql.query("SELECT thumbFile, seen FROM trip_photos WHERE id = 'p'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("/t.jpg", c.getString(0)); assertEquals(1, c.getInt(1))
            }
            assertEquals(1, count("pending_deletes"))
            sql.execSQL("INSERT INTO photo_deletes (id, tripId, queuedMs) VALUES ('q', 1, 4)")
            sql.execSQL("DELETE FROM trips WHERE id = 1")
            assertEquals(0, count("photo_deletes")); assertEquals(0, count("trip_photos"))
        } finally { db.close() }
    }
}
