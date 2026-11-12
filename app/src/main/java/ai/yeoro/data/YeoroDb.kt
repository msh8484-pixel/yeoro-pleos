package ai.yeoro.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trips")
data class TripEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val startMs: Long, val endMs: Long? = null,
                      val retroactive: Boolean = false, val destination: String? = null, val state: String = "TRIP",
                      val destLat: Double? = null, val destLon: Double? = null, val title: String? = null)

@Entity(tableName = "moments", indices = [Index("tripId")],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)])
data class MomentEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val tripId: Long?, val timeMs: Long, val type: String, val detail: String,
                         val lat: Double? = null, val lon: Double? = null)

@Entity(tableName = "location_samples", indices = [Index("tripId")],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)])
data class LocationSampleEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val tripId: Long?, val timeMs: Long, val lat: Double, val lon: Double)

/** 공공데이터 캐시 (원문 JSON {"arrival":{…},"departure":{…}}). 받은 뒤에는 다시 묻지 않는다 */
@Entity(tableName = "trip_outside",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)])
data class TripOutsideEntity(@PrimaryKey val tripId: Long, val fetchedMs: Long, val json: String)

/** 가족이 올린 사진(서버 id). 파일은 filesDir/photos/<tripId>/ (PhotoFiles) */
@Entity(tableName = "trip_photos", indices = [Index("tripId")],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)])
data class TripPhotoEntity(@PrimaryKey val id: String, val tripId: Long, val takenAtMs: Long?, val takenAtSource: String,
                           val uploadedMs: Long, val w: Int, val h: Int, val thumbFile: String?, val fullFile: String?, val seen: Boolean = false)

/** 서버에서 아직 지우지 못한 여행의 공유 토큰 (여행은 이미 차에서 지워졌다) */
@Entity(tableName = "pending_deletes")
data class PendingDeleteEntity(@PrimaryKey val token: String, val queuedMs: Long)

/**
 * 차에서 뺀 사진(서버 삭제 대기). 행이 있는 동안 이 id는 숨김 목록이라 서버 목록에 있어도 다시 받지 않는다.
 * 여행을 통째로 지우면 함께 지워진다(서버 쪽은 여행 삭제 큐가 맡는다).
 */
@Entity(tableName = "photo_deletes", indices = [Index("tripId")],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)])
data class PhotoDeleteEntity(@PrimaryKey val id: String, val tripId: Long, val queuedMs: Long)

/** AI 기록문 캐시 (Task 11이 쓴다) */
@Entity(tableName = "trip_diary",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)])
data class TripDiaryEntity(@PrimaryKey val tripId: Long, val factsHash: String, val json: String, val createdMs: Long)

data class TripCount(val tripId: Long, val n: Int)

@Dao
interface YeoroDao {
    @Insert suspend fun insertTrip(t: TripEntity): Long
    @Update suspend fun updateTrip(t: TripEntity)
    @Insert suspend fun insertMoment(m: MomentEntity)
    @Insert suspend fun insertSample(s: LocationSampleEntity)
    @Query("UPDATE moments SET tripId = :tripId WHERE tripId IS NULL AND timeMs >= :sinceMs") suspend fun attachMoments(tripId: Long, sinceMs: Long)
    @Query("UPDATE location_samples SET tripId = :tripId WHERE tripId IS NULL AND timeMs >= :sinceMs") suspend fun attachSamples(tripId: Long, sinceMs: Long)
    @Query("DELETE FROM moments WHERE tripId IS NULL AND timeMs < :beforeMs") suspend fun purgeMoments(beforeMs: Long)
    @Query("DELETE FROM location_samples WHERE tripId IS NULL AND timeMs < :beforeMs") suspend fun purgeSamples(beforeMs: Long)
    @Query("DELETE FROM moments WHERE tripId IS NULL") suspend fun deleteBufferMoments()
    @Query("DELETE FROM location_samples WHERE tripId IS NULL") suspend fun deleteBufferSamples()
    @Transaction suspend fun attachBuffer(tripId: Long, sinceMs: Long) { attachMoments(tripId, sinceMs); attachSamples(tripId, sinceMs) }
    @Transaction suspend fun purgeBuffer(beforeMs: Long) { purgeMoments(beforeMs); purgeSamples(beforeMs) }
    @Transaction suspend fun deleteBuffer() { deleteBufferMoments(); deleteBufferSamples() }
    @Query("SELECT * FROM location_samples WHERE tripId = :tripId ORDER BY timeMs") fun samples(tripId: Long): Flow<List<LocationSampleEntity>>
    @Query("SELECT * FROM trips WHERE endMs IS NOT NULL AND destLat IS NOT NULL AND destLon IS NOT NULL") suspend fun endedTripsWithDest(): List<TripEntity>
    @Query("SELECT * FROM trips ORDER BY startMs DESC") fun trips(): Flow<List<TripEntity>>
    @Query("SELECT * FROM moments WHERE tripId = :tripId ORDER BY timeMs") fun moments(tripId: Long): Flow<List<MomentEntity>>
    @Query("DELETE FROM trips WHERE id = :id") suspend fun deleteTrip(id: Long)
    @Query("SELECT * FROM trips WHERE id = :id") suspend fun tripById(id: Long): TripEntity?
    @Query("SELECT * FROM trips WHERE endMs IS NULL ORDER BY startMs DESC LIMIT 1") suspend fun openTrip(): TripEntity?
    @Query("SELECT * FROM trips WHERE endMs IS NULL ORDER BY startMs DESC LIMIT 1") fun openTripFlow(): Flow<TripEntity?>
    @Query("SELECT * FROM trips WHERE id = :id") fun tripFlow(id: Long): Flow<TripEntity?>
    /** 앨범 탭 기본값: 가장 최근에 끝난 확인된 여행 */
    @Query("SELECT * FROM trips WHERE endMs IS NOT NULL AND state = 'ALBUM_READY' ORDER BY startMs DESC LIMIT 1") fun latestAlbumTrip(): Flow<TripEntity?>
    /** 여행에 붙은 모든 위치 샘플·순간 (홈 책장의 거리·제목 계산용) */
    @Query("SELECT * FROM location_samples WHERE tripId IS NOT NULL ORDER BY tripId, timeMs") fun tripSamples(): Flow<List<LocationSampleEntity>>
    @Query("SELECT * FROM moments WHERE tripId IS NOT NULL ORDER BY tripId, timeMs") fun tripMomentsAll(): Flow<List<MomentEntity>>
    /** 아직 여행에 붙지 않은 후보 단계의 순간 (지금 화면 "기록 중") */
    @Query("SELECT * FROM moments WHERE tripId IS NULL ORDER BY timeMs") fun bufferMoments(): Flow<List<MomentEntity>>

    // ── v4: 클라우드 ──
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertOutside(o: TripOutsideEntity)
    @Query("SELECT * FROM trip_outside WHERE tripId = :tripId") fun outside(tripId: Long): Flow<TripOutsideEntity?>
    @Query("SELECT * FROM trip_outside WHERE tripId = :tripId") suspend fun outsideNow(tripId: Long): TripOutsideEntity?
    /** 공공데이터를 다시 물어볼 수 있는 여행: sinceMs 뒤에 닫힌 여행 (확인 안 한 여행은 빼고) */
    @Query("SELECT * FROM trips WHERE endMs IS NOT NULL AND endMs >= :sinceMs AND state != 'UNCONFIRMED' ORDER BY endMs DESC") suspend fun tripsClosedSince(sinceMs: Long): List<TripEntity>
    @Query("SELECT * FROM location_samples WHERE tripId = :tripId ORDER BY timeMs") suspend fun samplesNow(tripId: Long): List<LocationSampleEntity>
    @Query("SELECT * FROM moments WHERE tripId = :tripId ORDER BY timeMs") suspend fun momentsNow(tripId: Long): List<MomentEntity>
    /** 그대로 바꿔 쓴다. seen·파일 경로를 지키려면 TripRepository.mergePhotos를 쓴다 */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPhotos(list: List<TripPhotoEntity>)
    @Query("SELECT * FROM trip_photos WHERE tripId = :tripId ORDER BY uploadedMs, id") fun photos(tripId: Long): Flow<List<TripPhotoEntity>>
    /** 사진이 바뀌면 책장 표지를 다시 고르기 위한 신호 (여행별 장수와 파일 경로 합) */
    @Query("SELECT tripId, COUNT(*) AS n FROM trip_photos WHERE fullFile IS NOT NULL OR thumbFile IS NOT NULL GROUP BY tripId") fun photoFileCounts(): Flow<List<TripCount>>
    @Query("SELECT * FROM trip_photos WHERE id IN (:ids)") suspend fun photosByIds(ids: List<String>): List<TripPhotoEntity>
    @Query("SELECT tripId, COUNT(*) AS n FROM trip_photos WHERE seen = 0 GROUP BY tripId") fun unseenPhotoCounts(): Flow<List<TripCount>>
    @Query("UPDATE trip_photos SET seen = 1 WHERE tripId = :tripId") suspend fun markPhotosSeen(tripId: Long)
    @Query("SELECT * FROM pending_deletes ORDER BY queuedMs") suspend fun pendingDeletes(): List<PendingDeleteEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPendingDelete(p: PendingDeleteEntity)
    @Query("DELETE FROM pending_deletes WHERE token = :token") suspend fun deletePending(token: String)
    /** 여행 지우기의 차 안 부분: 서버 삭제 예약과 여행 삭제(cascade)를 한 트랜잭션으로 */
    @Transaction suspend fun deleteTripQueued(tripId: Long, pending: PendingDeleteEntity?) { pending?.let { insertPendingDelete(it) }; deleteTrip(tripId) }
    @Query("SELECT * FROM trip_photos WHERE tripId = :tripId ORDER BY uploadedMs, id") suspend fun photosNow(tripId: Long): List<TripPhotoEntity>
    /** 내려받은 파일 경로만 바꾼다(null이면 그대로). 본 표시는 건드리지 않는다 */
    @Query("UPDATE trip_photos SET thumbFile = COALESCE(:thumb, thumbFile), fullFile = COALESCE(:full, fullFile) WHERE id = :id") suspend fun setPhotoFiles(id: String, thumb: String?, full: String?)
    @Query("DELETE FROM trip_photos WHERE id IN (:ids)") suspend fun deletePhotosByIds(ids: List<String>)

    // ── v5: 차에서 사진 빼기 ──
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPhotoDelete(p: PhotoDeleteEntity)
    @Query("SELECT * FROM photo_deletes ORDER BY queuedMs, id") suspend fun photoDeletes(): List<PhotoDeleteEntity>
    @Query("SELECT id FROM photo_deletes WHERE tripId = :tripId") suspend fun hiddenPhotoIds(tripId: Long): List<String>
    @Query("DELETE FROM photo_deletes WHERE id IN (:ids)") suspend fun deletePhotoDeletes(ids: List<String>)
    /** 사진 빼기의 차 안 부분: 행 삭제와 서버 삭제 예약(숨김)을 한 트랜잭션으로 */
    @Transaction suspend fun removePhotoQueued(tripId: Long, id: String, nowMs: Long) {
        deletePhotosByIds(listOf(id))
        if (tripById(tripId) != null) insertPhotoDelete(PhotoDeleteEntity(id, tripId, nowMs))
    }

    @Query("SELECT * FROM trip_diary WHERE tripId = :tripId") suspend fun diary(tripId: Long): TripDiaryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDiary(d: TripDiaryEntity)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `moments_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER, `timeMs` INTEGER NOT NULL, `type` TEXT NOT NULL, `detail` TEXT NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        // 존재하지 않는 여행을 가리키는 고아 순간은 버퍼(null)로 되돌리지 않고 버린다
        db.execSQL("INSERT INTO moments_new (id, tripId, timeMs, type, detail) SELECT id, tripId, timeMs, type, detail FROM moments WHERE tripId IS NULL OR tripId IN (SELECT id FROM trips)")
        db.execSQL("DROP TABLE moments")
        db.execSQL("ALTER TABLE moments_new RENAME TO moments")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_moments_tripId` ON `moments` (`tripId`)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN destLat REAL")
        db.execSQL("ALTER TABLE trips ADD COLUMN destLon REAL")
        db.execSQL("ALTER TABLE trips ADD COLUMN title TEXT")
        db.execSQL("ALTER TABLE moments ADD COLUMN lat REAL")
        db.execSQL("ALTER TABLE moments ADD COLUMN lon REAL")
        db.execSQL("CREATE TABLE IF NOT EXISTS `location_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER, `timeMs` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_samples_tripId` ON `location_samples` (`tripId`)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // app/schemas/ai.yeoro.data.YeoroDb/4.json 의 DDL 그대로
        db.execSQL("CREATE TABLE IF NOT EXISTS `trip_outside` (`tripId` INTEGER NOT NULL, `fetchedMs` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`tripId`), FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trip_photos` (`id` TEXT NOT NULL, `tripId` INTEGER NOT NULL, `takenAtMs` INTEGER, `takenAtSource` TEXT NOT NULL, `uploadedMs` INTEGER NOT NULL, `w` INTEGER NOT NULL, `h` INTEGER NOT NULL, `thumbFile` TEXT, `fullFile` TEXT, `seen` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_photos_tripId` ON `trip_photos` (`tripId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `pending_deletes` (`token` TEXT NOT NULL, `queuedMs` INTEGER NOT NULL, PRIMARY KEY(`token`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trip_diary` (`tripId` INTEGER NOT NULL, `factsHash` TEXT NOT NULL, `json` TEXT NOT NULL, `createdMs` INTEGER NOT NULL, PRIMARY KEY(`tripId`), FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // app/schemas/ai.yeoro.data.YeoroDb/5.json 의 DDL 그대로
        db.execSQL("CREATE TABLE IF NOT EXISTS `photo_deletes` (`id` TEXT NOT NULL, `tripId` INTEGER NOT NULL, `queuedMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_deletes_tripId` ON `photo_deletes` (`tripId`)")
    }
}

@Database(entities = [TripEntity::class, MomentEntity::class, LocationSampleEntity::class,
    TripOutsideEntity::class, TripPhotoEntity::class, PendingDeleteEntity::class, TripDiaryEntity::class, PhotoDeleteEntity::class], version = 5, exportSchema = true)
abstract class YeoroDb : RoomDatabase() {
    abstract fun dao(): YeoroDao
    companion object {
        @Volatile private var inst: YeoroDb? = null
        fun get(c: Context): YeoroDb = inst ?: synchronized(this) { inst ?: Room.databaseBuilder(c.applicationContext, YeoroDb::class.java, "yeoro.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { inst = it } }
    }
}
