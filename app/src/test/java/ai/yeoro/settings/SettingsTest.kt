package ai.yeoro.settings

import ai.yeoro.engine.Seat
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 설정 저장소: "yeoro_prefs"의 집 위치(자동/직접), 기록 동의, 좌석별 가족 이름. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTest {
    private lateinit var prefs: SharedPreferences
    private val home = MutableStateFlow<Pair<Double, Double>?>(null)
    private fun fresh() = Settings(prefs, home)

    @Before fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit(); home.value = null
    }

    @Test fun `동의가 꺼져 있거나 정하지 않았으면 집을 자동으로 저장하지 않는다`() {
        val s = fresh()
        assertFalse(s.learnHomeIfConsented(37.5563 to 126.9220)); assertNull(home.value); assertFalse(prefs.contains("home_lat"))
        s.setConsent(false)
        assertFalse(s.learnHomeIfConsented(37.5563 to 126.9220)); assertNull(home.value); assertFalse(prefs.contains("home_lat"))
    }

    @Test fun `동의가 켜져 있으면 첫 유효 위치를 집으로 학습한다`() {
        val s = fresh(); s.setConsent(true)
        assertTrue(s.learnHomeIfConsented(37.5563 to 126.9220))
        assertEquals(37.5563 to 126.9220, home.value); assertTrue(prefs.contains("home_lat"))
    }

    @Test fun `처음에는 동의를 정하지 않았고(null), 정하면 저장된다`() {
        val s = fresh()
        assertNull(s.consent.value)
        assertFalse(s.consented)
        s.setConsent(true)
        assertEquals(true, s.consent.value); assertTrue(s.consented)
        home.value = null
        assertEquals(true, fresh().consent.value)
        fresh().setConsent(false)
        assertEquals(false, fresh().consent.value)
    }

    @Test fun `첫 유효 위치는 자동으로 집이 되고, 집이 있으면 바꾸지 않는다`() {
        val s = fresh()
        assertFalse(s.learnHome(0.0 to 0.0))
        assertNull(s.home.value)
        assertTrue(s.learnHome(37.5563 to 126.9220))
        assertEquals(37.5563 to 126.9220, s.home.value); assertTrue(s.homeAuto.value)
        assertFalse(s.learnHome(37.0 to 127.0))
        assertEquals(37.5563 to 126.9220, s.home.value)
        home.value = null
        val r = fresh()
        assertEquals(37.5563 to 126.9220, r.home.value); assertTrue(r.homeAuto.value)
    }

    @Test fun `지금 위치를 집으로 - 직접 정한 집, 0,0은 거절`() {
        val s = fresh()
        s.learnHome(37.5563 to 126.9220)
        assertFalse(s.setHomeHere(0.0 to 0.0))
        assertEquals(37.5563 to 126.9220, s.home.value)
        assertTrue(s.setHomeHere(37.6 to 127.1))
        assertEquals(37.6 to 127.1, s.home.value); assertFalse(s.homeAuto.value)
        home.value = null
        assertFalse(fresh().homeAuto.value)
        assertFalse(s.setHomeHere(null))
    }

    @Test fun `다시 정하기 - 집을 지워 다음 유효 위치를 자동으로 배운다`() {
        val s = fresh()
        s.setHomeHere(37.6 to 127.1)
        s.resetHome()
        assertNull(s.home.value); assertNull(prefs.getString("home_lat", null))
        assertTrue(s.learnHome(37.5 to 127.0)); assertTrue(s.homeAuto.value)
    }

    @Test fun `저장된 0,0 집은 버리고, 자동 표시가 없는 예전 집은 자동으로 본다`() {
        prefs.edit().putString("home_lat", "0.0").putString("home_lon", "0.0").commit()
        assertNull(fresh().home.value); assertNull(prefs.getString("home_lat", null))
        prefs.edit().putString("home_lat", "37.5").putString("home_lon", "127.0").commit()
        val s = fresh()
        assertEquals(37.5 to 127.0, s.home.value); assertTrue(s.homeAuto.value)
    }

    @Test fun `가족 이름 - 좌석별, 앞뒤 공백 제거, 비우면 지운다`() {
        val s = fresh()
        assertTrue(s.familyNames.value.isEmpty())
        s.setFamilyName(Seat.DRIVER, "  아빠 ")
        s.setFamilyName(Seat.REAR_LEFT, "민지")
        assertEquals(mapOf(Seat.DRIVER to "아빠", Seat.REAR_LEFT to "민지"), s.familyNames.value)
        s.setFamilyName(Seat.REAR_LEFT, "   ")
        assertEquals(mapOf(Seat.DRIVER to "아빠"), s.familyNames.value)
        home.value = null
        assertEquals(mapOf(Seat.DRIVER to "아빠"), fresh().familyNames.value)
    }

    @Test fun `음성 기본값은 도착 인사 켜짐, 말로 답하기 꺼짐이고 바꾸면 남는다`() {
        val s = fresh()
        assertTrue(s.voiceGuide.value); assertFalse(s.voiceAnswer.value)
        s.setVoiceGuide(false); s.setVoiceAnswer(true)
        val again = fresh()
        assertFalse(again.voiceGuide.value); assertTrue(again.voiceAnswer.value)
    }
}
