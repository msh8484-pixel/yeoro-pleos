package ai.yeoro.cloud

import ai.yeoro.engine.PlaceKind
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceKindsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `장소 종류 캐시 - 소수 셋째 자리 키, 30일`() {
        var now = 1_000_000L
        val p = ctx.getSharedPreferences("yeoro_places_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val c = PlaceKindCache(p, now = { now })
        assertNull(c.get(37.80551, 128.90872))
        c.put(37.80551, 128.90872, PlaceKind.TOURIST)
        assertEquals("37.806,128.909", placeKey(37.80551, 128.90872))
        assertEquals(PlaceKind.TOURIST, c.get(37.8055, 128.9087))   // 같은 키
        assertNull(c.get(37.8075, 128.9087))
        now += 30L * 24 * 3_600_000 - 1
        assertEquals(PlaceKind.TOURIST, c.get(37.8055, 128.9087))
        now += 2
        assertNull(c.get(37.8055, 128.9087))
    }

    @Test fun `P단이 되면 목적지가 있고 캐시도 조회도 없을 때만 다시 묻는다`() {
        val g = ai.yeoro.engine.Gear.P
        assertTrue(shouldLookupPlaceOnPark(g, true, null, false))
        assertFalse(shouldLookupPlaceOnPark(g, false, null, false))                 // 목적지 없음
        assertFalse(shouldLookupPlaceOnPark(g, true, PlaceKind.TOURIST, false))     // 캐시 있음
        assertFalse(shouldLookupPlaceOnPark(g, true, null, true))                   // 이미 조회 중
        assertFalse(shouldLookupPlaceOnPark(ai.yeoro.engine.Gear.D, true, null, false))
    }
}
