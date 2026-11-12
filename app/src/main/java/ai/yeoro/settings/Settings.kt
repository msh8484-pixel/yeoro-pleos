package ai.yeoro.settings

import ai.yeoro.EngineBus
import ai.yeoro.engine.Seat
import ai.yeoro.vehicle.isValidLocation
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 설정 저장소 ("yeoro_prefs"): 집 위치(자동/직접), 여로 기록 동의, 좌석별 가족 이름, 음성(도착 인사·말로 답하기).
 * 집의 메모리 값은 EngineBus.home 하나다(서비스 틱·목적지 거리·앨범이 모두 그것을 읽는다). 여기서는 그 값과 prefs를 함께 바꾼다.
 * 모든 쓰기는 메인 스레드에서 한다(화면, 서비스, 디버그 수신기 모두 메인).
 */
class Settings(private val prefs: SharedPreferences, private val homeFlow: MutableStateFlow<Pair<Double, Double>?> = EngineBus.home) {
    val home: StateFlow<Pair<Double, Double>?> = homeFlow.asStateFlow()
    private val _homeAuto = MutableStateFlow(true)
    /** true = 첫 위치로 자동으로 정한 집 (예전 버전이 저장한 집도 자동으로 본다) */
    val homeAuto: StateFlow<Boolean> = _homeAuto.asStateFlow()
    private val _consent = MutableStateFlow(if (prefs.contains(K_CONSENT)) prefs.getBoolean(K_CONSENT, false) else null)
    /** null = 아직 정하지 않음(첫 실행 동의 화면을 보여 준다). 정하지 않은 동안은 기록하지 않는다 */
    val consent: StateFlow<Boolean?> = _consent.asStateFlow()
    val consented: Boolean get() = _consent.value == true
    private val _names = MutableStateFlow(Seat.entries.mapNotNull { s -> prefs.getString(nameKey(s), null)?.takeIf { it.isNotBlank() }?.let { s to it } }.toMap())
    /** 좌석별 가족 이름 (비어 있는 좌석은 없음) */
    val familyNames: StateFlow<Map<Seat, String>> = _names.asStateFlow()
    private val _voiceGuide = MutableStateFlow(prefs.getBoolean(K_VOICE_GUIDE, true))
    /** 도착을 소리로 알려 주기 (기본 켜짐). P단에서 도착 카드가 뜰 때 한 번 말한다 */
    val voiceGuide: StateFlow<Boolean> = _voiceGuide.asStateFlow()
    private val _voiceAnswer = MutableStateFlow(prefs.getBoolean(K_VOICE_ANSWER, false))
    /** 출발 질문에 말로 답하기 (기본 꺼짐: 마이크를 여는 기능). 켜도 RECORD_AUDIO가 없으면 효과 없음 */
    val voiceAnswer: StateFlow<Boolean> = _voiceAnswer.asStateFlow()

    init {
        val la = prefs.getString(K_LAT, null)?.toDoubleOrNull(); val lo = prefs.getString(K_LON, null)?.toDoubleOrNull()
        if (la != null && lo != null && !isValidLocation(la, lo)) removeHome()   // 수정 전 저장된 0,0 집은 버린다
        else if (la != null && lo != null && homeFlow.value == null) homeFlow.value = la to lo
        _homeAuto.value = prefs.getBoolean(K_AUTO, true)
    }

    /** 집이 없을 때만 첫 유효 위치를 집으로 (자동). 정했으면 true */
    fun learnHome(loc: Pair<Double, Double>?): Boolean {
        if (homeFlow.value != null || loc == null || !isValidLocation(loc.first, loc.second)) return false
        saveHome(loc, auto = true); return true
    }

    /** 서비스 틱용: 기록 동의가 켜져 있을 때만 자동 집 학습 (동의 전·나중에·끔이면 위치를 저장하지 않는다) */
    fun learnHomeIfConsented(loc: Pair<Double, Double>?): Boolean = consented && learnHome(loc)

    /** [지금 위치를 집으로]: 유효한 위치일 때만. 정했으면 true */
    fun setHomeHere(loc: Pair<Double, Double>?): Boolean {
        if (loc == null || !isValidLocation(loc.first, loc.second)) return false
        saveHome(loc, auto = false); return true
    }

    /** [다시 정하기]: 집을 지운다. 다음 유효 위치가 다시 자동으로 집이 된다 */
    fun resetHome() { removeHome(); _homeAuto.value = true }

    fun setConsent(on: Boolean) { prefs.edit().putBoolean(K_CONSENT, on).apply(); _consent.value = on }

    fun setVoiceGuide(on: Boolean) { prefs.edit().putBoolean(K_VOICE_GUIDE, on).apply(); _voiceGuide.value = on }
    fun setVoiceAnswer(on: Boolean) { prefs.edit().putBoolean(K_VOICE_ANSWER, on).apply(); _voiceAnswer.value = on }

    fun setFamilyName(seat: Seat, name: String) {
        val n = name.trim()
        if (n.isEmpty()) prefs.edit().remove(nameKey(seat)).apply() else prefs.edit().putString(nameKey(seat), n).apply()
        _names.value = if (n.isEmpty()) _names.value - seat else _names.value + (seat to n)
    }

    private fun saveHome(h: Pair<Double, Double>, auto: Boolean) {
        prefs.edit().putString(K_LAT, h.first.toString()).putString(K_LON, h.second.toString()).putBoolean(K_AUTO, auto).apply()
        _homeAuto.value = auto; homeFlow.value = h
    }
    private fun removeHome() { prefs.edit().remove(K_LAT).remove(K_LON).remove(K_AUTO).apply(); homeFlow.value = null }

    companion object {
        const val PREFS = "yeoro_prefs"
        private const val K_LAT = "home_lat"; private const val K_LON = "home_lon"; private const val K_AUTO = "home_auto"
        private const val K_CONSENT = "consent"
        private const val K_VOICE_GUIDE = "voice_guide"; private const val K_VOICE_ANSWER = "voice_answer"
        private fun nameKey(s: Seat) = "family_${s.name}"
        /** 가족 이름 입력 최대 길이 (카드·좌석 칸에서 잘리지 않게) */
        const val NAME_MAX = 8
        @Volatile private var instance: Settings? = null
        fun get(c: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
