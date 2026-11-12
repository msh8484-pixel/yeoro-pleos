package ai.yeoro.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HolidaysTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = ctx.getSharedPreferences("yeoro_holidays_test", Context.MODE_PRIVATE)
    private val api = FakeCloudApi()
    private var consent = true

    @Before fun clear() { prefs.edit().clear().commit() }

    private fun holidays(a: CloudApi? = api) = Holidays(prefs, a, consent = { consent })

    @Test fun `캐시가 없으면 false`() {
        assertFalse(holidays().isHoliday(LocalDate.of(2026, 10, 9)))
    }

    @Test fun `refresh는 연도별로 저장하고 isHoliday가 읽는다`() = runBlocking {
        val h = holidays(object : CloudApi by api {
            override suspend fun holidays(year: Int) = CloudResult.Ok(listOf(LocalDate.of(year, 1, 1), LocalDate.of(year, 10, 9)))
        })
        assertTrue(h.refresh(2026))
        assertEquals("2026-01-01,2026-10-09", prefs.getString("y_2026", null))
        assertTrue(h.isHoliday(LocalDate.of(2026, 10, 9)))
        assertFalse(h.isHoliday(LocalDate.of(2026, 10, 10)))
        assertFalse(h.isHoliday(LocalDate.of(2027, 10, 9)))   // 2027은 아직 없음
        // 새 인스턴스도 prefs에서 읽는다
        assertTrue(Holidays(prefs, null, { false }).isHoliday(LocalDate.of(2026, 1, 1)))
    }

    @Test fun `실패하면 캐시를 그대로 둔다`() = runBlocking {
        prefs.edit().putString("y_2026", "2026-10-09").commit()
        assertFalse(holidays(object : CloudApi by api { override suspend fun holidays(year: Int) = CloudResult.Fail(null, "network") }).refresh(2026))
        assertTrue(holidays().isHoliday(LocalDate.of(2026, 10, 9)))
    }

    @Test fun `동의 꺼짐이면 refresh가 api를 부르지 않는다`() = runBlocking {
        consent = false
        assertFalse(holidays().refresh(2026))
        assertEquals(emptyList<String>(), api.calls)
        assertFalse(holidays(null).refresh(2026))   // api 없음(키 없음)도 조용히
    }
}
