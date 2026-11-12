package ai.yeoro.data

import ai.yeoro.geo.haversineKm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TripRepository(private val dao: YeoroDao) {
    fun trips(): Flow<List<TripEntity>> = dao.trips()
    suspend fun trip(id: Long): TripEntity? = dao.tripById(id)
    fun moments(id: Long): Flow<List<MomentEntity>> = dao.moments(id)
    fun bufferMoments(): Flow<List<MomentEntity>> = dao.bufferMoments()
    fun openTrip(): Flow<TripEntity?> = dao.openTripFlow()
    /** id가 0이면 가장 최근에 끝난 확인된 여행 */
    fun albumTrip(id: Long): Flow<TripEntity?> = if (id == 0L) dao.latestAlbumTrip() else dao.tripFlow(id)
    fun samples(id: Long): Flow<List<LocationSampleEntity>> = dao.samples(id)

    /** 끝난 여행 중 목적지가 (lat, lon)에서 radiusM 안인 것. 반경 계산은 Kotlin에서 한다. */
    suspend fun tripsNear(lat: Double, lon: Double, radiusM: Int = 500): List<TripEntity> =
        dao.endedTripsWithDest().filter { haversineKm(lat to lon, it.destLat!! to it.destLon!!) * 1000 <= radiusM }

    /** 같은 목적지에 beforeMs 이전에 끝난 여행 수 + 1 */
    suspend fun visitCount(lat: Double, lon: Double, radiusM: Int = 500, beforeMs: Long): Int =
        tripsNear(lat, lon, radiusM).count { it.endMs!! < beforeMs } + 1

    /** 여행 id → 위치 샘플을 시간순으로 이은 거리(km). 거리는 따로 저장하지 않는다. 샘플이 하나뿐이면 0. */
    fun tripKm(): Flow<Map<Long, Double>> = dao.tripSamples().map { all ->
        all.groupBy { it.tripId!! }.mapValues { (_, s) -> s.sortedBy { it.timeMs }.zipWithNext { a, b -> haversineKm(a.lat to a.lon, b.lat to b.lon) }.sum() }
    }
    /** 여행 id → 순간(시간순) */
    fun tripMoments(): Flow<Map<Long, List<MomentEntity>>> = dao.tripMomentsAll().map { all -> all.groupBy { it.tripId!! } }

    /** [남기기]: 확인 안 한 여행을 앨범이 있는 여행으로. 끝 시각·순간·위치는 이미 붙어 있어 앨범을 바로 만들 수 있다. [지우기]는 CloudSync.deleteTrip(차·서버 함께). */
    suspend fun confirmUnconfirmed(id: Long) { dao.tripById(id)?.let { if (it.state == "UNCONFIRMED") dao.updateTrip(it.copy(state = "ALBUM_READY")) } }

    /** 서버에서 받은 사진 목록을 넣는다. 이미 있는 사진은 본 표시(seen)와 내려받은 파일 경로를 그대로 둔다. */
    suspend fun mergePhotos(remote: List<TripPhotoEntity>) {
        if (remote.isEmpty()) return
        val old = dao.photosByIds(remote.map { it.id }).associateBy { it.id }
        dao.upsertPhotos(remote.map { r -> old[r.id]?.let { o -> r.copy(seen = o.seen, thumbFile = r.thumbFile ?: o.thumbFile, fullFile = r.fullFile ?: o.fullFile) } ?: r })
    }
}
