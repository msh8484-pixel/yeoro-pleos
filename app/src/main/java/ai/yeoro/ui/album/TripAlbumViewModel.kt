package ai.yeoro.ui.album

import ai.yeoro.EngineBus
import ai.yeoro.cloud.CloudSync
import ai.yeoro.cloud.TripOutside
import ai.yeoro.cloud.parseTripOutside
import ai.yeoro.data.OpenedAlbums
import ai.yeoro.data.TripEntity
import ai.yeoro.data.TripRepository
import ai.yeoro.data.YeoroDb
import ai.yeoro.settings.Settings
import ai.yeoro.ui.live.momentLine
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** 앨범 한 권에 필요한 모든 값. 화면은 이것만 그린다. */
data class AlbumData(
    val tripId: Long,
    val title: String,
    val date: String,              // "2026년 10월 24일 토요일"
    val subtitle: String,          // "집 앞 출발 → 강릉 경포해변 · 첫 번째 방문"
    val destination: String?,
    val chapters: List<Chapter>,
    /** 갈 길: 출발 ~ 목적지를 떠난 지점(왕복이 아니면 전체). 지도에 앰버로 그린다 */
    val route: List<Pair<Double, Double>>,
    /** 왕복이면 목적지를 떠난 지점 ~ 집(흐린 선), 아니면 빈 목록 */
    val returnRoute: List<Pair<Double, Double>>,
    /** 전체 거리(갈 길 + 올 길) */
    val km: Int,
    val momentCount: Int,
    val duration: String,          // "4:28" 출발부터 도착까지(갈 길)
    val seats: Int?,
    val arriveHour: Int,
    val rained: Boolean,
    val zone: ZoneId,
    /** 설정의 좌석별 가족 이름 (기록문의 좌석 순간에 쓴다) */
    val names: Map<ai.yeoro.engine.Seat, String> = emptyMap(),
    /** 그날의 바깥(공공데이터, trip_outside). 없으면 null → 2a "예시" 칩 */
    val outside: TripOutside? = null,
    /** 가족 사진: 장 번호 → 촬영 시각순 사진 (assignPhotos). 모든 장 번호가 열쇠 */
    val photos: Map<Int, List<AlbumPhoto>> = emptyMap(),
    /** 표지 사진 (coverPhoto), 없으면 2a 그라디언트 */
    val cover: AlbumPhoto? = null,
    val photoCount: Int = 0,
    /** 닫힌 여행(앨범이 확정됨). 열린 여행은 통째로 지울 수 없다 */
    val closed: Boolean = false,
    /** 그때 vs 지금 (이 목적지 두 번째 방문부터, 지난 방문이 앨범으로 남아 있을 때). 도착 장 바로 다음 쪽 */
    val thenNow: ThenNow? = null,
    /** 이 목적지 방문 횟수(이 여행까지), 모르면 null. 다시 가기 쪽의 단골 단계 */
    val visit: Int? = null,
    /** 다시 가기 링크 (againLinks). 비어 있으면(목적지 이름 없음) 다시 가기 쪽을 만들지 않는다 */
    val again: List<AgainLink> = emptyList(),
)

sealed interface AlbumState {
    data object Loading : AlbumState
    data object Empty : AlbumState
    data class Ready(val album: AlbumData) : AlbumState
}

/** nav 인자 id(0 = 가장 최근에 끝난 확인된 여행)의 여행·순간·위치 샘플을 묶어 AlbumData로 만든다. */
@OptIn(ExperimentalCoroutinesApi::class)
class TripAlbumViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    private val dao = YeoroDb.get(app).dao()
    private val repo = TripRepository(dao)
    private val requested: Long = saved.get<Long>("id") ?: 0L
    private val zone: ZoneId = ZoneId.systemDefault()

    private val cloud = CloudSync.get(app)

    // 앨범을 연 순간 책장의 "새 앨범" 배지를 떼고, 이 여행의 새 사진을 본 것으로 표시한다(지금 화면의 "사진 N장" 카드가 내려간다)
    val state: StateFlow<AlbumState> = repo.albumTrip(requested).onEach { t -> t?.let { OpenedAlbums.mark(app, it.id) } }.flatMapLatest { trip ->
        if (trip == null) flowOf(AlbumState.Empty)
        else combine(repo.samples(trip.id), repo.moments(trip.id), EngineBus.home, Settings.get(app).familyNames,
            combine(outsideOf(trip.id), openPhotos(trip.id)) { o, p -> o to p }) { s, m, home, names, (outside, photos) ->
            val visit = trip.destLat?.let { la -> trip.destLon?.let { lo -> trip.endMs?.let { end -> repo.visitCount(la, lo, beforeMs = end) } } }
            val a = album(trip, s, m, home, visit)
            val byChapter = assignPhotos(a.chapters, photos.map(::albumPhoto))
            val cover = coverPhoto(a.chapters, byChapter)
            val withPhotos = a.copy(names = names, outside = outside, photos = byChapter, cover = cover, photoCount = photos.size, closed = trip.endMs != null,
                visit = visit, again = againLinks(trip.destination, outside))
            AlbumState.Ready(if ((visit ?: 1) >= 2) withThenNow(trip, withPhotos, m, home) else withPhotos) as AlbumState
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumState.Loading)

    /** 콜라주에서 사진 한 장 빼기 (차 안은 바로, 서버는 보낼 수 있을 때. CloudSync.removePhoto) */
    fun removePhoto(tripId: Long, photoId: String) { viewModelScope.launch { cloud.removePhoto(tripId, photoId) } }

    /**
     * 이 여행 통째로 지우기 (차·서버, CloudSync.deleteTrip). 차 안 부분은 화면을 떠나 취소돼도 끝까지 하고(NonCancellable),
     * 서버 삭제는 큐에 남아 다음 동기화에서라도 보낸다. done: 홈으로 돌아가기(바로 부른다)
     */
    fun deleteTrip(tripId: Long, done: () -> Unit) { viewModelScope.launch { cloud.deleteTrip(tripId) }; done() }

    /**
     * 지난 방문 읽기 캐시: (이 여행 id, 집 좌표) → 그때 쪽. 앨범은 사진·바깥·설정이 바뀔 때마다 다시 만들어지지만
     * 지난 여행은 그동안 바뀌지 않으므로 여행마다 한 번만 DB에서 읽는다. state 흐름 하나에서만 쓴다.
     */
    private var thenCache: Pair<Pair<Long, Pair<Double, Double>?>, Pair<VisitSummary, VisitSide>?>? = null

    /** 지난 방문(previousVisit)이 있으면 그때 vs 지금 쪽을 붙인다. 지금 쪽은 이 앨범과 같은 값(도착 장·표지·받아 둔 날씨) */
    private suspend fun withThenNow(trip: TripEntity, a: AlbumData, moments: List<ai.yeoro.data.MomentEntity>, home: Pair<Double, Double>?): AlbumData {
        val key = trip.id to home
        val then = thenCache?.takeIf { it.first == key }?.second ?: loadThenSide(dao, trip, home, zone).also { thenCache = key to it }
        val (thenSum, thenSide) = then ?: return a
        val nowSum = summaryOf(trip.id, a.chapters, moments, a.outside, a.cover, trip.endMs ?: trip.startMs)
        return a.copy(thenNow = ThenNow(thenSide, visitSide(nowSum, zone), thenNowHeading(thenSum.arriveMs, nowSum.arriveMs, zone), comparisons(thenSum, nowSum, zone)))
    }

    /** 열린 앨범의 사진: 보는 동안 동기화로 들어온 새 사진도 본 것으로 표시한다(지금 화면의 "사진 N장" 카드가 내려간다) */
    private fun openPhotos(tripId: Long) = dao.photos(tripId).onEach { l -> if (l.any { !it.seen }) dao.markPhotosSeen(tripId) }

    /** 받아 둔 공공데이터 (동기화가 나중에 채우면 앨범이 다시 그려진다) */
    private fun outsideOf(tripId: Long): Flow<TripOutside?> =
        dao.outside(tripId).map { e -> e?.let { parseTripOutside(it.json) } }.distinctUntilChanged()

    private fun album(
        trip: TripEntity, samples: List<ai.yeoro.data.LocationSampleEntity>, moments: List<ai.yeoro.data.MomentEntity>,
        home: Pair<Double, Double>?, visit: Int?,
    ): AlbumData {
        val ordered = samples.sortedBy { it.timeMs }
        val legs = tripLegsOf(trip, ordered, moments, home)   // 장 나누기와 경로 나누기가 같은 값을 쓴다
        val chapters = buildChapters(trip, ordered, moments, home, legs)
        val leave = legs?.leaveIdx
        val going = if (leave != null) ordered.subList(0, leave + 1) else ordered
        val coming = if (leave != null) ordered.subList(leave, ordered.size) else emptyList()
        val km = routeKm(ordered)
        val start = chapters.first().startMs
        val arrive = chapters.last { it.kind == ChapterKind.ARRIVAL }.startMs
        val dateFmt = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)
        val sub = buildString {
            append(chapters.first().name)
            trip.destination?.let { append(" → ").append(it) }
            visit?.let { append(" · ").append(koOrdinal(it)).append(" 방문") }
        }
        return AlbumData(
            tripId = trip.id,
            title = trip.title ?: albumTitle(trip.destination, moments, routeKm(going), zone),   // 제목의 거리는 갈 길만
            date = Instant.ofEpochMilli(start).atZone(zone).format(dateFmt),
            subtitle = sub,
            destination = trip.destination,
            chapters = chapters,
            route = going.map { it.lat to it.lon },
            returnRoute = coming.map { it.lat to it.lon },
            km = km.roundToInt(),
            momentCount = moments.count { momentLine(it.type, it.detail) != null },
            duration = durationText(arrive - start),
            seats = seatCount(moments),
            arriveHour = Instant.ofEpochMilli(arrive).atZone(zone).hour,
            rained = moments.any { it.type == "RAIN_START" },
            zone = zone,
        )
    }
}
