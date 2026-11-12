package ai.yeoro.engine

enum class Gear { P, R, N, D, UNKNOWN }
enum class Seat { DRIVER, PASSENGER, REAR_LEFT, REAR_CENTER, REAR_RIGHT;
    val rear: Boolean get() = this == REAR_LEFT || this == REAR_CENTER || this == REAR_RIGHT }
enum class PlaceKind { TOURIST, LODGING, CAMPING, WORK, OTHER }

data class Destination(
    val name: String, val distanceKm: Double, val kind: PlaceKind,
    val frequent: Boolean = false, val regular: Boolean = false, val visits: Int = 0,
)

data class CalendarInfo(val weekend: Boolean, val holiday: Boolean, val hour: Int) {
    val commuteHour: Boolean get() = !weekend && !holiday && (hour in 6..9 || hour in 17..19)
}

data class Snapshot(
    val timeMs: Long, val gear: Gear, val speedKmh: Float, val seats: Set<Seat>,
    val trunkOpen: Boolean, val wiperOn: Boolean, val openWindows: Int, val doorOpen: Boolean,
    val homeDistanceKm: Double?,   // null = 위치 모름
)
