package ai.yeoro.vehicle

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 0,0 근처(위치 없음)와 NaN은 유효하지 않은 위치 */
fun isValidLocation(lat: Double, lon: Double): Boolean = !lat.isNaN() && !lon.isNaN() && !(kotlin.math.abs(lat) < 1e-6 && kotlin.math.abs(lon) < 1e-6)

data class NaviDestination(val name: String, val lat: Double, val lon: Double, val remainMeters: Int)

class NaviSource(private val context: Context) {
    private val _destination = MutableStateFlow<NaviDestination?>(null)
    val destination: StateFlow<NaviDestination?> = _destination
    private val _location = MutableStateFlow<Pair<Double, Double>?>(null)
    val location: StateFlow<Pair<Double, Double>?> = _location
    private val _arrivedMs = MutableStateFlow<Long?>(null)
    /** 내비가 마지막으로 목적지 도착을 알린 시각 (도착 인증 판정용) */
    val arrivedMs: StateFlow<Long?> = _arrivedMs
    @Volatile private var navi: NaviHelper? = null
    @Volatile private var stopped = true
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable { override fun run() { if (stopped) return; runCatching { navi?.getCurrentLocationInfo() }; handler.postDelayed(this, 30_000) } }

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            // 목적지가 없으면 SDK가 이름 빈 문자열, 좌표 0,0인 정보를 돌려준다
            _destination.value = if (info.name.isBlank() && info.latitude == 0.0 && info.longitude == 0.0) null
            else NaviDestination(info.name, info.latitude, info.longitude, info.remainDistance)
        }
        override fun onDestinationChanged() { navi?.getDestinationInfo() }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            // 위치를 못 잡으면 SDK가 0,0을 돌려준다: 집으로 저장되거나 거리 계산에 쓰이지 않게 버린다
            if (!isValidLocation(info.latitude, info.longitude)) return
            _location.value = info.latitude to info.longitude
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) { _arrivedMs.value = System.currentTimeMillis(); _destination.value = null }
        override fun onRouteCancelled() { _destination.value = null }
    }

    /** 디버그 전용: 내비에 경로 요청 (에뮬레이터 내비가 목적지를 내보내는지 확인용) */
    fun requestRoute(name: String, lat: Double, lon: Double) {
        runCatching { navi?.requestRoute(RouteInfo(latitude = lat, longitude = lon, poiName = name, poiId = "", address = "", poiSubId = "", routeOption = RouteOption.RECOMMENDED)) }.onFailure { Log.w("YeoroNavi", "requestRoute 실패", it) }
    }

    @Synchronized fun start() {
        if (!stopped) return
        stopped = false
        val n = NaviHelper(context.applicationContext); navi = n
        n.initialize({
            if (stopped) return@initialize
            n.addListener(listener); n.getDestinationInfo(); handler.post(poll)
        }, { Log.w("YeoroNavi", "NaviHelper 초기화 실패", it) })
    }
    @Synchronized fun stop() {
        stopped = true; handler.removeCallbacks(poll)
        val n = navi; navi = null
        runCatching { n?.removeListener(listener) }; runCatching { n?.release() }
        _destination.value = null; _location.value = null
    }
}
