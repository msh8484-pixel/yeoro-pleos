package ai.yeoro.ui.live

import ai.yeoro.EngineBus
import ai.yeoro.PendingAsk
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.data.TripRepository
import ai.yeoro.data.YeoroDb
import ai.yeoro.engine.TripState
import ai.yeoro.service.Notifier
import ai.yeoro.ui.album.loadVisitSummary
import ai.yeoro.ui.album.seaLike
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = YeoroDb.get(app).dao()
    private val repo = TripRepository(dao)
    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val state: StateFlow<TripState?> = EngineBus.lastScore.map { it?.state }.distinctUntilChanged().state(null)
    val ask: StateFlow<PendingAsk?> = EngineBus.pendingAsk
    val started: StateFlow<PendingAsk?> = EngineBus.departureAccepted
    val arrival: StateFlow<ai.yeoro.ArrivalInfo?> = EngineBus.arrival
    val openTrip: StateFlow<TripEntity?> = repo.openTrip().state(null)
    /** 앨범 준비 장면의 [앨범 보기]가 열 여행: 가장 최근에 닫힌 확인된 여행 (앨범 탭의 id 0과 같은 규칙) */
    val albumTripId: StateFlow<Long?> = repo.albumTrip(0).map { it?.id }.distinctUntilChanged().state(null)

    /**
     * "가족이 사진 N장을 보냈어요" 카드: 아직 보지 않은 사진이 있는 여행 중 가장 최근 것(출발이 늦은 여행). 앨범을 열면 본 것으로 표시돼 내려간다.
     */
    val promoPhotos: StateFlow<Promo.Photos?> = combine(dao.unseenPhotoCounts(), repo.trips()) { counts, trips ->
        val byId = trips.associateBy { it.id }
        counts.filter { it.n > 0 && it.tripId in byId }.maxByOrNull { byId.getValue(it.tripId).startMs }
            ?.let { c -> Promo.Photos(c.tripId, c.n, byId.getValue(c.tripId).destination) }
    }.distinctUntilChanged().state(null)

    private val zone: ZoneId = ZoneId.systemDefault()
    /** 엔진 시계(시연 시계 포함)의 오늘 "yyyy-MM-dd". 날짜가 바뀔 때만 */
    private val today: Flow<String> = EngineBus.lastSnapshot.map { memoryDay(it?.timeMs ?: EngineBus.nowMs(), zone) }.distinctUntilChanged()
    /** 지금 띄우고 있는 회상 카드 (날짜, 여행 id). 처음 보인 날을 적은 뒤에도 [닫기] 전까지는 남는다 */
    private val memoryOnScreen = MutableStateFlow<Pair<String, Long>?>(null)

    /**
     * 1년 전 이번 주 회상 카드 후보 (memoryTrip). 하루 한 번: 오늘 이미 띄웠으면(memory_shown_date) 내지 않는다.
     * 화면에 실제로 보일 때 memoryShown()으로 날짜를 적는다. 어느 장면에 보일지는 livePromo(일상 P단)가 정한다.
     */
    val promoMemory: StateFlow<Promo.Memory?> = combine(repo.trips(), today) { trips, day ->
        day to memoryTrip(trips, LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli(), zone)
    }.distinctUntilChanged().mapLatest { (day, trip) -> day to trip?.let { loadMemory(it) } }
        .combine(combine(MemoryShown.flow(app), memoryOnScreen) { a, b -> a to b }) { (day, m), (shown, onScreen) ->
            m?.takeIf { memoryVisible(shown, day, it.tripId, onScreen) }
        }.distinctUntilChanged().state(null)

    private suspend fun loadMemory(trip: TripEntity): Promo.Memory {
        // 읽기 실패는 기본값으로 갈음하되, 취소(mapLatest가 다음 날짜로 넘어감·화면 떠남)는 삼키지 않고 그대로 올린다
        val photos = try { dao.photosNow(trip.id).size } catch (e: CancellationException) { throw e } catch (e: Exception) { 0 }
        val s = try { loadVisitSummary(dao, trip, EngineBus.home.value) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val m = memoryText(trip, photos, zone)
        // 목적지 이름("강릉 경포해변")이 줄 끝에서 갈라지지 않게
        val dest = trip.destination?.trim()
        return m.copy(title = if (dest.isNullOrEmpty()) m.title else m.title.replace(dest, dest.replace(' ', '\u00A0')), thumb = s?.photoFile, hour = s?.let { Instant.ofEpochMilli(it.arriveMs).atZone(zone).hour } ?: 14,
            rained = s?.rained ?: false, sea = seaLike(trip.destination))
    }

    /** 회상 카드가 화면에 보였다: 처음 보인 날을 적는다(같은 날 다시 띄우지 않는다) */
    fun memoryShown(m: Promo.Memory) {
        val day = memoryDay(EngineBus.lastSnapshot.value?.timeMs ?: EngineBus.nowMs(), zone)
        memoryOnScreen.value = day to m.tripId
        if (MemoryShown.flow(getApplication()).value != day) MemoryShown.set(getApplication(), day)
    }

    /** 회상 카드가 더는 보이지 않는다(P 이탈·홍보 전환·화면 떠남): 오늘 날짜는 이미 적혀 있으니 다시 나오지 않는다 */
    fun memoryGone() { memoryOnScreen.value = null }

    /** 회상 카드 [닫기]: 오늘은 다시 띄우지 않는다(보이는 사이 날짜 표시가 지워졌어도 다시 적는다) */
    fun closeMemory() {
        MemoryShown.set(getApplication(), memoryDay(EngineBus.lastSnapshot.value?.timeMs ?: EngineBus.nowMs(), zone))
        memoryOnScreen.value = null
    }

    /**
     * 일상 화면의 큰 그림: 책장 맨 앞 여행(가장 최근 앨범)의 표지와 올해 기록. 책장이 비면 null(차 그림).
     * 표지 사진은 앨범 표지와 같은 규칙(loadVisitSummary). 사진을 내려받으면 다시 고른다.
     */
    val latest: StateFlow<LatestTrip?> = combine(repo.trips(), repo.tripKm(), repo.tripMoments(), dao.photoFileCounts()) { trips, km, moments, _ ->
        Triple(trips, km, moments)
    }.mapLatest { (trips, km, moments) ->
        val shelf = ai.yeoro.ui.home.shelfTrips(trips)
        val first = shelf.firstOrNull() ?: return@mapLatest null
        val b = ai.yeoro.ui.home.book(first, moments[first.id].orEmpty(), km[first.id] ?: 0.0, emptySet(), zone)
        val photo = try { loadVisitSummary(dao, first, EngineBus.home.value).photoFile } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        LatestTrip(b, photo, ai.yeoro.ui.home.homeStats(shelf, km, java.time.ZonedDateTime.now(zone).year, zone).copy(kmThisYear = shelf.sumOf { km[it.id] ?: 0.0 }.roundToInt()))
    }.distinctUntilChanged().state(null)

    /** 기록 중 목록: 후보 단계는 버퍼 순간, 여행 중에는 열린 여행의 순간. 소급 질문 카드도 버퍼 순간 수를 쓴다. */
    val moments: StateFlow<List<MomentEntity>> = state.flatMapLatest { st ->
        when (st) {
            TripState.CANDIDATE -> repo.bufferMoments()
            TripState.TRIP, TripState.STAYING -> repo.openTrip().map { it?.id }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.moments(id) }
            else -> flowOf(emptyList())
        }
    }.state(emptyList())

    /** 알림 버튼과 같은 경로로 답하고, 떠 있는 알림은 지운다. */
    fun answer(ask: PendingAsk, accepted: Boolean) {
        EngineBus.answer(accepted, ask.kind)
        Notifier.cancelAsk(getApplication())
    }

    /** 도착 카드 닫기 (30초·P 이탈 전에 직접 닫을 때). 떠 있는 도착 알림도 지운다. */
    fun closeArrival() {
        EngineBus.arrival.value = null
        Notifier.cancelArrival(getApplication())
    }
}

/** 일상 화면의 최근 여행 한 권 */
data class LatestTrip(val book: ai.yeoro.ui.home.Book, val photo: String?, val stats: ai.yeoro.ui.home.HomeStats)

/**
 * 회상 카드를 처음 보인 날 (prefs yeoro_prefs의 memory_shown_date). 디버그 memory_reset이 지울 수 있게 프로세스 하나에 흐름 하나.
 * 흐름은 곧바로 바꾸고, 파일은 IO 스레드에서 commit()으로 적는다(곧 프로세스가 죽어도 남게). 쓰기는 한 줄로 서서 마지막 값을 적는다.
 */
object MemoryShown {
    private const val PREFS = "yeoro_prefs"
    @Volatile private var state: MutableStateFlow<String?>? = null
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun flow(c: Context): StateFlow<String?> = state ?: synchronized(this) {
        state ?: MutableStateFlow(prefs(c).getString(MEMORY_SHOWN_KEY, null)).also { state = it }
    }
    fun set(c: Context, day: String?) {
        val f = flow(c) as MutableStateFlow
        f.value = day
        val app = c.applicationContext
        writer.launch {
            val v = f.value   // 앞뒤로 여러 번 바뀌었으면 마지막 값
            prefs(app).edit().apply { if (v == null) remove(MEMORY_SHOWN_KEY) else putString(MEMORY_SHOWN_KEY, v) }.commit()
        }
    }
}
