package ai.yeoro.debug

import ai.yeoro.EngineBus
import ai.yeoro.cloud.PhotoFiles
import ai.yeoro.data.LocationSampleEntity
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.data.TripPhotoEntity
import ai.yeoro.data.YeoroDb
import ai.yeoro.data.companionDetail
import ai.yeoro.engine.Seat
import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 디버그 전용 "지난 방문" (그때 vs 지금): 강릉 경포해변을 시연 토요일 − 364일(같은 요일, 1년 전 이번 주)에 다녀온 여행.
 * 10:05 마포 출발 → 14:41 경포해변 도착, 위치 4점, 비 11:50~13:10, 좌석 3명, 사진 1장(assets/demo_prev.jpg, 14:50 촬영).
 * 날씨(trip_outside)는 넣지 않는다(가짜 기상청 값 금지). 시연 시계(demo_clock)가 없으면 실제 오늘 기준.
 * 다시 보내면 지난 seed_prev 여행을 지우고 바꿔 넣는다. 지난 여행은 고정 표지(디버그 prefs에 적어 둔 여행 id + 목적지·좌표가 seed 값 그대로인지)로
 * 찾는다. 사진을 앨범에서 빼도 찾을 수 있고, 같은 id가 다른 여행에 다시 쓰였으면 지우지 않는다. 공유 토큰이 없어 서버에는 올라가지 않는다.
 */
object SeedPrev {
    private const val DEST = "강릉 경포해변"
    const val PHOTO_ID = "seed-prev-1"

    private const val PREFS = "yeoro_debug_seed"   // reset_policy가 지우지 않는다
    private const val KEY_TRIP = "seed_prev_trip"
    private const val DEST_LAT = 37.8055
    private const val DEST_LON = 128.9087

    /** 지난 seed_prev 여행의 고정 표지: 적어 둔 id이고, 목적지·좌표·상태가 seed 값 그대로 */
    private fun isSeeded(t: TripEntity?) = t != null && t.destination == DEST && t.destLat == DEST_LAT && t.destLon == DEST_LON && t.state == "ALBUM_READY"

    private data class Wp(val h: Int, val m: Int, val lat: Double, val lon: Double)
    private val ROUTE = listOf(
        Wp(10, 5, 37.5563, 126.9220),    // 마포
        Wp(11, 40, 37.4900, 127.9800),   // 횡성
        Wp(13, 30, 37.6800, 128.7200),   // 대관령
        Wp(14, 41, 37.8055, 128.9087),   // 경포해변
    )

    /** 넣은 여행 id */
    suspend fun seed(c: Context, db: YeoroDb): Long {
        val zone = ZoneId.systemDefault()
        val day: LocalDate = Instant.ofEpochMilli(EngineBus.nowMs()).atZone(zone).toLocalDate().minusDays(364)
        fun at(h: Int, m: Int) = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
        val dao = db.dao()
        val files = PhotoFiles.of(c)
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val marked = prefs.getLong(KEY_TRIP, 0L).takeIf { it > 0 && isSeeded(dao.tripById(it)) }
        // 표지로 찾은 여행 + (표지가 없던 예전 seed) 사진 seed-prev-1이 붙은 여행
        (listOfNotNull(marked) + dao.photosByIds(listOf(PHOTO_ID)).map { it.tripId }).distinct().forEach { old -> dao.deleteTrip(old); files.deleteTrip(old) }
        val id = db.withTransaction {
            val id = dao.insertTrip(TripEntity(startMs = at(10, 5), endMs = at(14, 41), destination = DEST, state = "ALBUM_READY", destLat = DEST_LAT, destLon = DEST_LON))
            ROUTE.forEach { dao.insertSample(LocationSampleEntity(tripId = id, timeMs = at(it.h, it.m), lat = it.lat, lon = it.lon)) }
            listOf(
                MomentEntity(tripId = id, timeMs = at(10, 5), type = "COMPANION_JOINED", detail = companionDetail("1→3", setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT))),
                MomentEntity(tripId = id, timeMs = at(11, 50), type = "RAIN_START", detail = ""),
                MomentEntity(tripId = id, timeMs = at(13, 10), type = "RAIN_STOP", detail = ""),
            ).forEach { dao.insertMoment(it) }
            id
        }
        // 사진: 원본·썸네일 자리에 같은 파일을 둔다 (PhotoFiles 경로 규칙 그대로)
        val full = files.full(id, PHOTO_ID); val thumb = files.thumb(id, PHOTO_ID)
        full.parentFile?.mkdirs()
        c.assets.open("demo_prev.jpg").use { input -> full.outputStream().use { input.copyTo(it) } }
        full.copyTo(thumb, overwrite = true)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(full.path, o)
        dao.upsertPhotos(listOf(TripPhotoEntity(PHOTO_ID, id, takenAtMs = at(14, 50), takenAtSource = "exif", uploadedMs = at(15, 30),
            w = o.outWidth, h = o.outHeight, thumbFile = thumb.path, fullFile = full.path, seen = true)))
        prefs.edit().putLong(KEY_TRIP, id).apply()
        ai.yeoro.data.OpenedAlbums.mark(c, id)   // 지난 방문은 이미 본 앨범 (책장에 "새 앨범" 배지 없음)
        return id
    }
}
