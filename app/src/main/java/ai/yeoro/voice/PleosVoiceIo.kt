package ai.yeoro.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos Playground TTS·STT, Mode.ON_DEVICE만 (앱 등록·키·네트워크 없음, 음성이 차 밖으로 나가지 않는다).
 * 객체는 처음 쓸 때 만든다: 음성 설정이 꺼져 있으면 caas 서비스에 붙지도 않는다. 초기화가 실패하면 그 요청은 곧바로
 * 실패 콜백을 받고, 다음 요청 때 다시 시도한다. speak·듣기 시작·stop이 예외를 던지거나 서비스 쪽 오류(엔진·내부·알 수 없음)가
 * 오면 객체를 버리고 다음 요청 때 새로 만든다(caas가 다시 시작돼도 이어서 쓸 수 있게). SDK 콜백은 어느 스레드에서 오든 메인으로 옮겨 부른다.
 * debug: 인식 문장과 진행 로그를 남길지 (디버그 빌드만. 릴리스는 오류 종류만 남긴다)
 */
open class PleosVoiceIo(context: Context, protected val debug: Boolean) : VoiceIo {
    protected val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // ── TTS ──
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsPending: Pair<(TextToSpeech) -> Unit, () -> Unit>? = null
    private var speakDone: ((Boolean) -> Unit)? = null
    /** 이번 발화의 onReady/onStart를 봤는지. 앞 발화를 멈춘 늦은 onStop이 새 발화를 끝내지 않게 */
    private var speakStarted = false

    private val ttsListener = object : EventListener {
        override fun onReady() = onMain { speakStarted = true }
        override fun onStart() = onMain { speakStarted = true; log("tts start") }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = onMain { log("tts done"); if (speakStarted) endSpeak(true) }
        override fun onStop() = onMain { log("tts stop"); if (speakStarted) endSpeak(false) }
        override fun onError(errorType: TtsError) = onMain {
            Log.w(TAG, "voice tts error $errorType"); endSpeak(false)
            if (errorType in TtsServiceErrors) dropTts()
        }
    }

    override fun speak(text: String, onDone: (ok: Boolean) -> Unit) {
        withTts(fail = { onDone(false) }) { t ->
            speakDone = onDone; speakStarted = false
            try { t.speak(text, true); log("tts speak '$text'") }
            catch (e: Exception) { Log.w(TAG, "voice tts speak failed", e); endSpeak(false); dropTts() }
        }
    }

    private fun endSpeak(ok: Boolean) { val cb = speakDone; speakDone = null; cb?.invoke(ok) }

    private fun withTts(fail: () -> Unit, action: (TextToSpeech) -> Unit) {
        val t = tts
        if (t != null && ttsReady) { action(t); return }
        ttsPending?.second?.invoke()   // 초기화를 기다리던 앞 요청은 새 요청으로 바뀐다
        ttsPending = action to fail
        if (t != null) return   // 초기화 중
        try {
            val n = TextToSpeech(app, TtsMode.ON_DEVICE); tts = n
            n.initialize(object : TtsInit {
                override fun onSuccess() = onMain {
                    if (tts !== n) return@onMain
                    try {
                        n.addEventListener(ttsListener)
                        n.setLocale(Locale.KOREA)
                        // 기본은 MEDIA. USAGE_ANNOUNCEMENT는 일반 앱에서 예외로 죽는다 (스파이크 확인)
                        n.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        ttsReady = true; log("tts ready")
                        ttsPending?.let { (act, _) -> ttsPending = null; act(n) }
                    } catch (e: Exception) { Log.w(TAG, "voice tts setup failed", e); dropTts() }
                }
                override fun onFailure(errorType: TtsError) = onMain { Log.w(TAG, "voice tts init failed $errorType"); if (tts === n) dropTts() }
            })
        } catch (e: Exception) { Log.w(TAG, "voice tts create failed", e); dropTts() }
    }

    private fun dropTts() {
        val t = tts; tts = null; ttsReady = false
        try { t?.removeEventListener(ttsListener); t?.release() } catch (_: Exception) {}
        ttsPending?.let { (_, fail) -> ttsPending = null; fail() }
    }

    // ── STT ──
    private var stt: SpeechToText? = null
    private var sttReady = false
    private var sttPending: Pair<(SpeechToText) -> Unit, () -> Unit>? = null
    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onError: (() -> Unit)? = null

    private val sttListener = object : ResultListener {
        override fun onReady() = onMain { log("stt ready") }
        override fun onStartedRecognition() = Unit
        // 최종은 completed=true로만 판단한다 (onEndedRecognition이 최종보다 먼저 온다)
        override fun onUpdated(stt: String, completed: Boolean) = onMain {
            if (completed) { log("stt final '$stt'"); val cb = onFinal; clearStt(); cb?.invoke(stt) } else onPartial?.invoke(stt)
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() = onMain { log("stt ended") }
        override fun onError(errorType: SttError) = onMain {
            Log.w(TAG, "voice stt error $errorType"); val cb = onError; clearStt()
            if (errorType in SttServiceErrors) dropStt()
            cb?.invoke()
        }
    }

    override fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: () -> Unit) {
        withStt(fail = onError) { s ->
            this.onPartial = onPartial; this.onFinal = onFinal; this.onError = onError
            try { startRecognition(s); log("stt listening") }
            catch (e: Exception) { Log.w(TAG, "voice stt start failed", e); val cb = this.onError; clearStt(); dropStt(); cb?.invoke() }
        }
    }

    /** 듣기 시작. 기본은 차량 마이크(caas가 연다). 디버그 빌드는 PCM 파일을 넣을 수 있다 */
    protected open fun startRecognition(s: SpeechToText) { s.request() }

    private fun clearStt() { onPartial = null; onFinal = null; onError = null }

    private fun withStt(fail: () -> Unit, action: (SpeechToText) -> Unit) {
        val s = stt
        if (s != null && sttReady) { action(s); return }
        sttPending?.second?.invoke()
        sttPending = action to fail
        if (s != null) return
        try {
            val n = SpeechToText(app, SttMode.ON_DEVICE); stt = n
            n.initialize(object : SttInit {
                override fun onSuccess() = onMain {
                    if (stt !== n) return@onMain
                    try {
                        n.addListener(sttListener)   // request()/sendAudio() 전에
                        n.setLocale(Locale.KOREA)
                        sttReady = true; log("stt init")
                        sttPending?.let { (act, _) -> sttPending = null; act(n) }
                    } catch (e: Exception) { Log.w(TAG, "voice stt setup failed", e); dropStt() }
                }
                override fun onFailure(errorType: SttError) = onMain { Log.w(TAG, "voice stt init failed $errorType"); if (stt === n) dropStt() }
            })
        } catch (e: Exception) { Log.w(TAG, "voice stt create failed", e); dropStt() }
    }

    private fun dropStt() {
        val s = stt; stt = null; sttReady = false
        try { s?.removeListener(sttListener); s?.release() } catch (_: Exception) {}
        sttPending?.let { (_, fail) -> sttPending = null; fail() }
    }

    // ── 공통 ──
    /** 말하기·듣기를 멈춘다. 듣기 콜백은 남겨 둔다(멈춘 뒤 늦게 오는 최종을 받을 수 있게). 만든 적 없는 쪽은 건드리지 않는다 */
    override fun stopAll() {
        ttsPending?.let { (_, fail) -> ttsPending = null; fail() }
        sttPending?.let { (_, fail) -> sttPending = null; fail() }
        try { if (ttsReady) tts?.stop() } catch (e: Exception) { Log.w(TAG, "voice tts stop failed", e); dropTts() }
        try { if (sttReady) stt?.stop() } catch (e: Exception) { Log.w(TAG, "voice stt stop failed", e); dropStt() }
        log("voice stop")
    }

    override fun release() {
        speakDone = null; clearStt(); ttsPending = null; sttPending = null
        dropTts(); dropStt()
    }

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
    protected fun log(msg: String) { if (debug) Log.i(TAG, msg) }
    protected companion object {
        const val TAG = "YeoroEngine"
        /** 서비스 쪽 고장으로 보는 오류: 객체를 버리고 다음에 새로 붙는다 (마이크·권한·언어 오류는 다시 붙어도 같으니 그대로) */
        val TtsServiceErrors = setOf(TtsError.SERVER_INTERNAL_ERROR, TtsError.ON_DEVICE_ENGINE_ERROR, TtsError.UNKNOWN)
        val SttServiceErrors = setOf(SttError.SERVER_INTERNAL_ERROR, SttError.ON_DEVICE_ENGINE_ERROR, SttError.UNKNOWN)
    }
}
