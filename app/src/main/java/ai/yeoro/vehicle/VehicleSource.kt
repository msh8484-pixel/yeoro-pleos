package ai.yeoro.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.*
import ai.yeoro.engine.Gear
import ai.yeoro.engine.Seat
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class RawVehicle(
    val gear: Gear = Gear.UNKNOWN, val speedKmh: Float = 0f, val seats: Set<Seat> = emptySet(),
    val trunkOpen: Boolean = false, val wiperOn: Boolean = false, val openWindows: Int = 0, val doorOpen: Boolean = false,
    // 열린 도어/창문 영역. 여러 SDK 콜백 스레드에서 오므로 불변 상태 안에 두고 update로 원자 갱신한다
    internal val openDoorAreas: Set<DoorValue> = emptySet(), internal val openWindowAreas: Set<WindowValue> = emptySet(),
)

class VehicleSource(private val context: Context) {
    private val tag = "YeoroVehicle"
    private val _state = MutableStateFlow(RawVehicle())
    val state: StateFlow<RawVehicle> = _state
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    // 등록/해제는 start()/stop() 호출 스레드에서만 하지만 안전하게 동기화한다
    private val cleanups = java.util.Collections.synchronizedList(mutableListOf<() -> Unit>())

    private inline fun register(what: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(tag, "$what 등록 실패", it) }
    }

    fun start() {
        val vh = Vehicle(context.applicationContext).also { it.initialize() }
        vehicle = vh
        val gearL = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) { _state.update { it.copy(gear = gear.toGear()) } }
            override fun onFailed(e: Exception) { Log.w(tag, "기어 실패", e) }
        }
        register("기어") { vh.getGear().registerGearSelection(gearL); cleanups += { vh.getGear().unregisterGearSelection(gearL) } }

        val seatMap = mapOf(SeatValue.ROW_1_LEFT to Seat.DRIVER, SeatValue.ROW_1_RIGHT to Seat.PASSENGER,
            SeatValue.ROW_2_LEFT to Seat.REAR_LEFT, SeatValue.ROW_2_CENTER to Seat.REAR_CENTER, SeatValue.ROW_2_RIGHT to Seat.REAR_RIGHT)
        seatMap.forEach { (v, seat) ->
            val area = SeatArea(v)
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(value: Boolean?) { _state.update { if (value == true) it.copy(seats = it.seats + seat) else it.copy(seats = it.seats - seat) } }
                override fun onFailed(e: Exception) { Log.w(tag, "좌석 $v 실패", e) }
            }
            register("좌석 $v") { vh.getSeat().registerSeatOccupancy(area, l); cleanups += { vh.getSeat().unregisterSeatOccupancy(area, l) } }
        }

        listOf(DoorValue.REAR, DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT).forEach { v ->
            val area = DoorArea(v)
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(value: Int?) {
                    val open = (value ?: 0) > 0
                    if (v == DoorValue.REAR) _state.update { it.copy(trunkOpen = open) }
                    else _state.update {
                        val areas = if (open) it.openDoorAreas + v else it.openDoorAreas - v
                        it.copy(openDoorAreas = areas, doorOpen = areas.isNotEmpty())
                    }
                }
                override fun onFailed(e: Exception) { Log.w(tag, "도어 $v 실패", e) }
            }
            register("도어 $v") { vh.getDoor().registerDoorPosition(area, l); cleanups += { vh.getDoor().unregisterDoorPosition(area, l) } }
        }

        val wArea = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiperL = object : WiperStateListener {
            override fun onWiperStateUpdated(value: WiperState?) { _state.update { it.copy(wiperOn = value == WiperState.ON) } }
            override fun onFailed(e: Exception) { Log.w(tag, "와이퍼 실패", e) }
        }
        register("와이퍼") { vh.getWiper().registerWiperState(wArea, wiperL); cleanups += { vh.getWiper().unregisterWiperState(wArea, wiperL) } }

        listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT).forEach { v ->
            val area = WindowArea(v)
            val l = object : WindowPositionListener {
                override fun onWindowPositionUpdated(value: Int?) {
                    val open = (value ?: 0) > 0
                    _state.update {
                        val areas = if (open) it.openWindowAreas + v else it.openWindowAreas - v
                        it.copy(openWindowAreas = areas, openWindows = areas.size)
                    }
                }
                override fun onFailed(e: Exception) { Log.w(tag, "창문 $v 실패", e) }
            }
            register("창문 $v") { vh.getWindow().registerWindowPosition(area, l); cleanups += { vh.getWindow().unregisterWindowPosition(area, l) } }
        }

        register("속도") {
            car = Car.createCar(context)
            val pm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            val speedCb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(v: CarPropertyValue<*>) { (v.value as? Float)?.let { mps -> _state.update { it.copy(speedKmh = mps * 3.6f) } } }
                override fun onErrorEvent(propertyId: Int, zone: Int) { Log.w(tag, "속도 오류 $propertyId") }
            }
            if (pm?.registerCallback(speedCb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE) != true)
                Log.w(tag, "속도 구독 거부됨(CAR_SPEED를 현재 사용자에게 grant했는지 확인)")
            cleanups += { pm?.unregisterCallback(speedCb) }
        }
    }

    fun stop() {
        val snapshot = synchronized(cleanups) { cleanups.toList().also { cleanups.clear() } }
        snapshot.forEach { runCatching { it() } }
        runCatching { vehicle?.release() }; vehicle = null
        runCatching { car?.disconnect() }; car = null
    }
}

private fun VehicleGear?.toGear(): Gear = when (this) {
    VehicleGear.GEAR_PARK -> Gear.P; VehicleGear.GEAR_REVERSE -> Gear.R; VehicleGear.GEAR_NEUTRAL -> Gear.N
    null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> Gear.D
}
