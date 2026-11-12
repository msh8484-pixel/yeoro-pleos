package ai.yeoro.ux

import ai.yeoro.engine.Gear
import ai.yeoro.vehicle.VehicleSource
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 주행 중 UX 제한 여부. CarUxRestrictionsManager를 쓰고, 서비스를 못 쓰면 기어(P가 아니면 제한)로 폴백한다.
 * 상태를 알기 전에는 안전하게 제한(true)으로 둔다.
 */
class DrivingRestriction(private val context: Context) {
    private val tag = "YeoroUx"
    private val _restricted = MutableStateFlow(true)
    val restricted: StateFlow<Boolean> = _restricted
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var vehicle: VehicleSource? = null
    @Volatile private var stopped = false
    private var scope: CoroutineScope? = null
    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { set(it.isRequiresDistractionOptimization, "ux") }

    private fun set(value: Boolean, source: String) {
        if (_restricted.value != value) Log.i(tag, "restricted=$value ($source)")
        _restricted.value = value
    }

    /** 메인 스레드에서 호출해도 막히지 않는다: 비동기 createCar, 서비스 연결/종료는 lifecycle 콜백으로 받는다. */
    fun start() {
        stopped = false
        val c = runCatching {
            Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready -> onCarLifecycle(car, ready) }
        }.onFailure { Log.w(tag, "Car 생성 실패, 기어 폴백", it) }.getOrNull()
        if (c == null) { cleanupCar(); startGearFallback() } else if (car == null) car = c
    }

    private fun onCarLifecycle(c: Car, ready: Boolean) {
        if (stopped) return
        if (!ready) {
            Log.w(tag, "CarService 종료, 제한=true 후 기어 폴백")
            manager = null
            set(true, "car-service-down")
            startGearFallback()
            return
        }
        car = c
        val ok = runCatching {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                ?: return@runCatching false
            m.registerListener(listener)
            manager = m
            set(m.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: true, "ux-initial")
            true
        }.onFailure { Log.w(tag, "UX 제한 서비스를 쓸 수 없음, 기어 폴백", it) }.getOrDefault(false)
        if (!ok) { cleanupCar(); startGearFallback() }
    }

    private fun cleanupCar() {
        runCatching { manager?.unregisterListener() }; manager = null
        runCatching { car?.disconnect() }; car = null
    }

    private fun startGearFallback() {
        if (vehicle != null) return
        val v = VehicleSource(context.applicationContext)
        runCatching { v.start() }.onFailure { Log.w(tag, "기어 폴백 시작 실패", it) }
        vehicle = v
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch { v.state.collect { set(it.gear != Gear.P, "gear=${it.gear}") } }
    }

    fun stop() {
        stopped = true
        cleanupCar()
        scope?.cancel(); scope = null
        runCatching { vehicle?.stop() }; vehicle = null
    }
}
