package ai.yeoro.cloud

import ai.yeoro.engine.PlaceKind
import android.content.SharedPreferences
import java.math.BigDecimal
import java.math.RoundingMode

/** 목적지 좌표 → 장소 종류(TourAPI) 캐시. prefs `yeoro_places`, 키는 소수 셋째 자리로 반올림한 좌표, 30일 */
class PlaceKindCache(private val prefs: SharedPreferences, private val now: () -> Long) {
    fun get(lat: Double, lon: Double): PlaceKind? {
        val v = prefs.getString(placeKey(lat, lon), null) ?: return null
        val (kind, at) = v.split("|").takeIf { it.size == 2 } ?: return null
        val t = at.toLongOrNull() ?: return null
        if (now() - t >= TTL_MS) return null
        return PlaceKind.entries.firstOrNull { it.name == kind }
    }

    fun put(lat: Double, lon: Double, kind: PlaceKind) { prefs.edit().putString(placeKey(lat, lon), "${kind.name}|${now()}").apply() }

    private companion object { const val TTL_MS = 30L * 24 * 3_600_000 }
}

/** 십진 표기 그대로 반올림한다 (37.8055 → 37.806, 이진 오차로 37.805가 되지 않게) */
fun placeKey(lat: Double, lon: Double): String = "${round3(lat)},${round3(lon)}"
private fun round3(v: Double) = BigDecimal(v.toString()).setScale(3, RoundingMode.HALF_UP).toPlainString()

/**
 * P단이 되었을 때 목적지 종류를 다시 물어야 하는가: 목적지가 있고, 캐시에 없고, 이미 도는 조회가 없을 때.
 * (주행 중에 정한 목적지는 그때 조회하지 못했으므로 주차하면 한 번 더 시도한다)
 */
fun shouldLookupPlaceOnPark(gear: ai.yeoro.engine.Gear, hasDest: Boolean, cached: PlaceKind?, jobActive: Boolean): Boolean =
    gear == ai.yeoro.engine.Gear.P && hasDest && cached == null && !jobActive
