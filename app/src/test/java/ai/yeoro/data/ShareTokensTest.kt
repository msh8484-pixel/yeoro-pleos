package ai.yeoro.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareTokensTest {
    private lateinit var prefs: SharedPreferences
    @Before fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(ShareTokens.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test fun `같은 여행은 늘 같은 토큰, 다시 만들어도(재시작) 그대로`() {
        val t = ShareTokens(prefs).token(7)
        assertEquals(t, ShareTokens(prefs).token(7))
    }

    @Test fun `여행마다 다른 128비트 base64url 토큰`() {
        val s = ShareTokens(prefs)
        val all = (1L..50L).map { s.token(it) }
        assertEquals(50, all.toSet().size)
        all.forEach { assertTrue(it, it.matches(Regex("[A-Za-z0-9_-]{22}"))); assertEquals(16, java.util.Base64.getUrlDecoder().decode(it).size) }
    }

    @Test fun `지운 여행의 토큰은 버리고 다음에는 새로 만든다`() {
        val s = ShareTokens(prefs); val t = s.token(3)
        s.forget(3)
        assertNotEquals(t, s.token(3))
    }

    @Test fun `등록 표시·tokenOrNull·all, forget은 등록 표시도 지운다`() {
        val s = ShareTokens(prefs)
        assertNull(s.tokenOrNull(1)); assertFalse(s.isRegistered(1))
        val t1 = s.token(1); val t2 = s.token(2)
        assertEquals(t1, s.tokenOrNull(1))
        s.markRegistered(1)
        assertTrue(s.isRegistered(1)); assertFalse(s.isRegistered(2))
        assertEquals(mapOf(1L to t1, 2L to t2), s.all())
        s.forget(1)
        assertFalse(s.isRegistered(1)); assertNull(s.tokenOrNull(1))
        assertEquals(mapOf(2L to t2), s.all())
    }
}
