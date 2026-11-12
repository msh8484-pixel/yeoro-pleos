package ai.yeoro.debug

import android.content.BroadcastReceiver
import android.content.Context

/** 릴리스: 아무것도 등록하지 않는다. */
object DebugHooks {
    @Suppress("UNUSED_PARAMETER") fun restoreUi(c: Context) {}
    /** 날씨를 물을 시각: 릴리스는 그대로 */
    fun weatherAt(atMs: Long): Long = atMs
    /** 음성 입출력: 릴리스는 Pleos 온디바이스 그대로 (차량 마이크) */
    fun voiceIo(c: Context): ai.yeoro.voice.VoiceIo = ai.yeoro.voice.PleosVoiceIo(c, debug = false)
    @Suppress("UNUSED_PARAMETER")
    fun register(c: Context, onRoute: (String, Double, Double) -> Unit, onResetPolicy: () -> Unit, onHome: (String) -> Unit, onArrive: () -> Unit, onSyncNow: () -> Unit,
                 onVoiceSay: (String) -> Unit): BroadcastReceiver? = null
}
