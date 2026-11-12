package ai.yeoro.debug

import ai.pleos.playground.stt.SpeechToText
import ai.yeoro.voice.PleosVoiceIo
import android.content.Context
import android.util.Log

/**
 * 디버그 빌드의 음성: 말하기는 그대로, 듣기는 feed가 정해져 있으면 마이크 대신 assets/voice/<feed>.pcm
 * (16kHz·16bit·mono)을 3200B(100ms)씩 sendAudio한 뒤 completeAudioSend (ON_DEVICE 전용 API).
 * 에뮬레이터 마이크로는 소리가 들어오지 않아 시연·시나리오가 이 길을 쓴다. feed = null이면 실제 마이크.
 */
class PcmVoiceIo(c: Context) : PleosVoiceIo(c, debug = true) {
    companion object {
        /** dbg voice_pcm yes|no|maybe|off. 바꿀 때까지 매 듣기에 쓴다 (reset_policy가 지운다) */
        @Volatile var feed: String? = null
        val Feeds = setOf("yes", "no", "maybe")
    }
    @Volatile private var feedGen = 0

    override fun startRecognition(s: SpeechToText) {
        val name = feed ?: return super.startRecognition(s)
        val pcm = app.assets.open("voice/$name.pcm").use { it.readBytes() }
        val my = ++feedGen
        log("stt pcm feed=$name bytes=${pcm.size}")
        Thread {
            try {
                var i = 0
                while (i < pcm.size) {
                    if (my != feedGen) return@Thread   // 멈춤·취소
                    s.sendAudio(pcm.copyOfRange(i, minOf(i + 3200, pcm.size))); i += 3200
                    Thread.sleep(100)
                }
                if (my == feedGen) s.completeAudioSend()
            } catch (e: Exception) { Log.w(TAG, "voice pcm feed failed", e) }
        }.apply { setName("yeoro-pcm"); isDaemon = true }.start()
    }

    override fun stopAll() { feedGen++; super.stopAll() }
    override fun release() { feedGen++; super.release() }
}
