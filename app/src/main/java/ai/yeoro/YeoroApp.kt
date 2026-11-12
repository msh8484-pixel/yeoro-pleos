package ai.yeoro
import ai.yeoro.engine.Action
import ai.yeoro.engine.Seat
import ai.yeoro.engine.Snapshot
import ai.yeoro.engine.TripEngine
import ai.yeoro.vehicle.NaviDestination
import android.app.Application
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 대기 중인 질문 (출발 전 "departure" 또는 소급 "retro"). 지금 화면 카드와 알림이 같은 내용을 쓴다.
 * signals: 점수에 들어간 신호 이름(왜 묻는지 보여 주는 줄). sinceMs: 소급 질문의 기록 시작 시각.
 */
data class PendingAsk(
    val kind: String, val score: Int, val destination: String?, val distanceKm: Double?,
    val seats: Set<Seat>, val askedMs: Long, val sinceMs: Long? = null, val signals: List<String> = emptyList(),
)

/**
 * 도착 인증 카드 내용. tripId 0은 디버그로 강제한 열린 여행 없는 도착(저장 안 함).
 * visit: 이번 방문이 몇 번째인지(이번 포함). seats: 도착 때 탑승 인원.
 * then: 두 번째 방문부터 지난 방문(그때 vs 지금의 그때 쪽). 지난 방문이 앨범으로 남아 있지 않으면 null.
 */
data class ArrivalInfo(val tripId: Long, val place: String?, val visit: Int, val arrivedMs: Long, val seats: Int, val uploadUrl: String,
                       val then: ai.yeoro.ui.album.VisitSide? = null)

object EngineBus {
    @Volatile var engine = TripEngine(); private set
    /** 디버그 전용: 정책 상태(하루 한 번 제안 등)를 지운 새 엔진으로 교체 */
    fun resetEngine() { engine = TripEngine(); pendingAsk.value = null; departureAccepted.value = null; arrival.value = null }
    /** 디버그 전용 재정의. null이면 실제 값을 쓴다 */
    val debugWeekend = MutableStateFlow<Boolean?>(null)
    val debugDestination = MutableStateFlow<NaviDestination?>(null)
    /** 디버그 전용(시연 시간 압축): 엔진·기록 시계에 더하는 값. 릴리스에서는 nowMs가 읽지 않아 항상 0 취급 */
    @Volatile var clockOffsetMs = 0L
    /** 엔진·기록이 쓰는 지금 시각 (실제 시각 + 디버그 시계 보정) */
    fun nowMs(): Long = System.currentTimeMillis() + if (BuildConfig.DEBUG) clockOffsetMs else 0L
    /** 디버그 전용: 내비 위치 대신 쓸 위치 (시연 경로 재생). null이면 내비 위치 */
    val debugLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    /** 디버그 전용: 집까지 거리 재정의(km). close_trip이 위치를 옮기지 않고 "집에 서 있음"을 엔진에 알릴 때 쓴다 */
    val debugHomeKm = MutableStateFlow<Double?>(null)
    /** 디버그 전용: 지금 화면의 "판단 엔진" 패널을 보일지 (dbg debug_panel true|false, 저장됨). 릴리스에는 패널이 없다 */
    val debugPanel = MutableStateFlow(true)
    /** 디버그 전용: 좌석 재정의 (true = 앉음, false = 빔). 에뮬레이터가 조작할 수 없는 뒷좌석 가운데를 비울 때 쓴다 (vc.sh seat rear_center off) */
    val debugSeats = MutableStateFlow<Map<Seat, Boolean>>(emptyMap())
    /** 차량 좌석에 디버그 재정의를 덮는다. 릴리스에서는 그대로 */
    fun seatsWithOverride(raw: Set<Seat>): Set<Seat> {
        if (!BuildConfig.DEBUG) return raw
        val o = debugSeats.value
        return if (o.isEmpty()) raw else raw - o.filterValues { !it }.keys + o.filterValues { it }.keys
    }
    val lastScore = MutableStateFlow<Action.ScoreUpdated?>(null)
    /** 마지막 차량 스냅샷 (좌석 등). RecorderService가 틱마다 갱신 */
    val lastSnapshot = MutableStateFlow<Snapshot?>(null)
    /** 현재 기어 (차량 신호가 바뀌는 즉시). 지금 화면이 P를 벗어난 도착 카드를 틱을 기다리지 않고 숨기는 데 쓴다 */
    val gear = MutableStateFlow(ai.yeoro.engine.Gear.UNKNOWN)
    /** 집 위치. 저장은 Settings가 맡는다(설정 화면, 첫 위치 자동, 디버그 home) */
    val home = MutableStateFlow<Pair<Double, Double>?>(null)
    /** 마지막으로 받은 유효한 내비 위치 (설정의 [지금 위치를 집으로]). RecorderService가 틱마다 갱신 */
    val location = MutableStateFlow<Pair<Double, Double>?>(null)
    /** 답을 기다리는 질문. RecorderService가 질문할 때 넣고, 답·시간 초과·KeepUnconfirmed·P 이탈 때 지운다 */
    val pendingAsk = MutableStateFlow<PendingAsk?>(null)
    /** 출발 전 질문에 "여행 기록 시작"으로 답한 뒤 아직 P에 있는 동안의 질문 (지금 화면의 기록 시작 장면) */
    val departureAccepted = MutableStateFlow<PendingAsk?>(null)
    /** 떠 있는 도착 인증 카드. RecorderService가 도착을 판정하면 넣고, 30초·P 이탈·닫기 때 지운다 */
    val arrival = MutableStateFlow<ArrivalInfo?>(null)
    /** 음성 상태 (RecorderService의 VoiceSession이 바꾼다). LISTENING이면 질문 카드에 "듣고 있어요"를 보인다 */
    val voice = MutableStateFlow(ai.yeoro.voice.VoiceState.IDLE)
    /** 지금 화면(LiveScreen)이 실제로 보이는지. 보이면 앱 안 카드로, 아니면 알림으로 질문을 전한다 */
    val liveVisible = MutableStateFlow(false)
    /** 알림 답: (수락 여부, 질문 종류 "departure"|"retro"|null) */
    val answers = MutableSharedFlow<Pair<Boolean, String?>>(extraBufferCapacity = 4)
    /** 화면에서 내비 길안내를 요청한다 (이름, 위도, 경도). RecorderService가 NaviHelper로 넘긴다 */
    val routeRequests = MutableSharedFlow<Triple<String, Double, Double>>(extraBufferCapacity = 2)
    /**
     * 알림 버튼과 앱 안 카드가 함께 쓰는 유일한 답 경로. 지금 대기 중인 질문을 이 답이 실제로 거둘 때(compareAndSet 성공)만
     * 엔진에 답을 보낸다: 사라지는 중인 카드를 한 번 더 누르거나, 이미 답한·시간이 지난·다른 종류의 질문에 대한 늦은 알림 답은 버린다.
     * 거뒀으면 true.
     */
    fun answer(accepted: Boolean, kind: String?): Boolean {
        val p = pendingAsk.value ?: return false
        if (kind != null && kind != p.kind) return false
        if (!pendingAsk.compareAndSet(p, null)) return false
        if (accepted && p.kind == "departure") departureAccepted.value = p
        answers.tryEmit(accepted to p.kind)
        return true
    }
}
class YeoroApp : Application()
