package ai.yeoro.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** 디버그 빌드 구현. 릴리스는 src/release의 no-op. broadcastPermission DUMP: adb shell(shell uid)만 보낼 수 있다. */
object DebugHooks {
    private const val UI_PREFS = "yeoro_debug_ui"   // reset_policy가 지우지 않는 발표자 설정
    /** 앱 시작(MainActivity) 때: 저장된 "판단 엔진" 패널 표시 여부를 되살린다 */
    fun restoreUi(c: Context) { ai.yeoro.EngineBus.debugPanel.value = c.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getBoolean("debug_panel", true) }
    fun setDebugPanel(c: Context, show: Boolean) {
        c.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().putBoolean("debug_panel", show).apply()
        ai.yeoro.EngineBus.debugPanel.value = show
    }

    /** 날씨를 물을 시각: 시연 시계(보정)가 있고 outside_time real이면 실제 지금, 아니면 그대로 */
    fun weatherAt(atMs: Long): Long = DemoDrive.weatherAt(atMs)

    /** 음성 입출력: 디버그는 PCM 주입이 되는 PcmVoiceIo (dbg voice_pcm) */
    fun voiceIo(c: Context): ai.yeoro.voice.VoiceIo = PcmVoiceIo(c)

    fun register(c: Context, onRoute: (String, Double, Double) -> Unit, onResetPolicy: () -> Unit, onHome: (String) -> Unit, onArrive: () -> Unit, onSyncNow: () -> Unit,
                 onVoiceSay: (String) -> Unit): BroadcastReceiver {
        DemoDrive.restore(c)
        val r = DebugReceiver(onRoute, onResetPolicy, onHome, onArrive, onSyncNow, onVoiceSay)
        ContextCompat.registerReceiver(c, r, IntentFilter(DebugReceiver.ACTION), "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED)
        return r
    }
}
