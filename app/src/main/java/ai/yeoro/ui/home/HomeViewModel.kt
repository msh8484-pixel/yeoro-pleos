package ai.yeoro.ui.home

import ai.yeoro.cloud.CloudSync
import ai.yeoro.cloud.parseTripOutside
import ai.yeoro.ui.album.AgainLink
import ai.yeoro.ui.album.againLinks
import ai.yeoro.ui.album.loadVisitSummary
import ai.yeoro.EngineBus
import ai.yeoro.data.OpenedAlbums
import ai.yeoro.data.TripRepository
import ai.yeoro.data.YeoroDb
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

/** 맨 위 카드: 확인 안 한 여행 하나 */
data class UnconfirmedCard(val tripId: Long, val line: String)

/** 지도 점: 같은 목적지 묶음 하나 */
data class MapDot(val name: String, val lat: Double, val lon: Double, val count: Int)

/** 정기 방문 한 묶음. latestTripId: 누르면 열 앨범, cover: 표지 일러스트(가장 최근 여행) */
data class Regular(val name: String, val count: Int, val dates: String, val latestTripId: Long, val cover: Book)

/** 정기 방문 [다시 가기] 대화상자: 가장 최근 여행의 목적지와 받아 둔 바깥(trip_outside)으로 만든 링크 */
data class AgainSheet(val dest: String, val links: List<AgainLink>, val visit: Int)

data class HomeUi(
    val unconfirmed: UnconfirmedCard?,
    val books: List<Book>,
    val regulars: List<Regular>,
    val dots: List<MapDot>,
    val stats: HomeStats,
    /** 단골 단계: 방문 많은 곳 3곳까지 (이름, 방문 횟수) */
    val levels: List<Pair<String, Int>>,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = YeoroDb.get(app).dao()
    private val repo = TripRepository(dao)
    private val zone: ZoneId = ZoneId.systemDefault()

    val state: StateFlow<HomeUi?> = combine(repo.trips(), repo.tripKm(), repo.tripMoments(), OpenedAlbums.flow(app)) { trips, km, moments, opened ->
        val shelf = shelfTrips(trips)
        val books = shelf.map { book(it, moments[it.id].orEmpty(), km[it.id] ?: 0.0, opened, zone) }
        val byId = books.associateBy { it.tripId }
        val groups = placeGroups(shelf)
        HomeUi(
            unconfirmed = latestUnconfirmed(trips)?.let { UnconfirmedCard(it.id, unconfirmedLine(it, km[it.id], zone)) },
            books = books,
            regulars = regularGroups(groups).map { g -> val last = g.trips.last(); Regular(g.name, g.count, groupDates(g, zone), last.id, byId.getValue(last.id)) },
            dots = groups.map { MapDot(it.name, it.lat, it.lon, it.count) },
            stats = homeStats(shelf, km, ZonedDateTime.now(zone).year, zone),
            levels = groups.take(3).map { it.name to it.count },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 책장 표지 사진: 여행 id → 사진 파일. 앨범 표지와 같은 규칙(loadVisitSummary → coverPhoto). 사진이 없거나 못 읽으면 빠지고 일러스트를 쓴다.
     * 책 목록이나 내려받은 사진이 바뀔 때만 다시 고른다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val covers: StateFlow<Map<Long, String>> = combine(state.filterNotNull().map { u -> u.books.map { it.tripId } }.distinctUntilChanged(), dao.photoFileCounts()) { ids, _ -> ids }
        .mapLatest { ids ->
            ids.mapNotNull { id ->
                try { dao.tripById(id)?.let { t -> loadVisitSummary(dao, t, EngineBus.home.value).photoFile?.let { id to it } } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            }.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 열린 [다시 가기] 대화상자 (없으면 null) */
    private val _again = MutableStateFlow<AgainSheet?>(null)
    val again: StateFlow<AgainSheet?> = _again

    /** 정기 방문 묶음의 [다시 가기]: 가장 최근 여행의 trip_outside로 링크를 만든다. 목적지 이름이 없으면 열지 않는다 */
    fun openAgain(r: Regular) {
        viewModelScope.launch {
            val trip = dao.tripById(r.latestTripId)
            val dest = trip?.destination?.takeIf { it.isNotBlank() } ?: r.name
            val links = againLinks(dest, dao.outsideNow(r.latestTripId)?.let { parseTripOutside(it.json) })
            if (links.isNotEmpty() && !restricted.value) _again.value = AgainSheet(dest.trim(), links, r.count)
        }
    }
    private val restricted = MutableStateFlow(false)
    /** 주행 제한 상태(화면이 알려 준다). 제한되면 열린 대화상자를 닫고, 제한 중에 도착한 결과는 버린다 */
    fun setRestricted(r: Boolean) { restricted.value = r; if (r) _again.value = null }
    fun closeAgain() { _again.value = null }

    /** [남기기]: 앨범이 있는 여행으로 바꿔 책장에 꽂는다 */
    fun keep(id: Long) { viewModelScope.launch { repo.confirmUnconfirmed(id) } }
    /** [지우기]: 여행과 그 순간·위치·사진을 지운다. 서버에 등록된 여행이면 서버의 사진과 등록도 (CloudSync 삭제 큐) */
    fun discard(id: Long) { viewModelScope.launch { CloudSync.get(getApplication()).deleteTrip(id) } }
}
