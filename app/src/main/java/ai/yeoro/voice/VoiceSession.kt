package ai.yeoro.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 지금 음성이 하는 일. LISTENING일 때만 질문 카드에 "듣고 있어요"를 보인다 */
enum class VoiceState { IDLE, SPEAKING, LISTENING }

/**
 * 한 번에 하나: 말하기(announce) 또는 묻고 듣기(ask). 새로 시작하면 앞의 것은 멈춘다.
 * 묻기: 말하기 → onDone(ok=true) 뒤에만 듣기(자기 목소리를 듣지 않게) → 최종 결과만 해석 → 답은 한 번.
 * 듣기 끝: VOICE_LISTEN_MS 뒤 stop, VOICE_FINAL_GRACE_MS 동안 최종을 기다린다(무음에서 stop하면 콜백이 없다).
 * 모든 콜백은 세대(gen)로 걸러 취소·끝난 뒤 늦게 온 것은 버린다. 메인 스레드 하나에서만 부른다.
 */
class VoiceSession(private val io: VoiceIo, private val scope: CoroutineScope, private val state: MutableStateFlow<VoiceState>) {
    private var gen = 0
    private var timer: Job? = null

    /** 말하기만 */
    fun announce(text: String) {
        val g = begin()
        speak(g, text) { finish(g) }
    }

    /** 말하기 → 듣기 → 해석. 수락·거절을 알아들었을 때만 onAnswer (모름·오류·시간 초과면 부르지 않는다) */
    fun ask(question: String, onAnswer: (Boolean) -> Unit) {
        val g = begin()
        speak(g, question) { listen(g, onAnswer) }
    }

    /** 기어가 P를 벗어남·카드 닫힘·질문 사라짐. 쉬고 있으면 아무것도 하지 않는다 */
    fun cancel() {
        if (state.value == VoiceState.IDLE) return
        io.stopAll(); finish(gen)
    }

    private fun begin(): Int {
        if (state.value != VoiceState.IDLE) io.stopAll()
        timer?.cancel(); timer = null
        return ++gen
    }

    private fun speak(g: Int, text: String, next: () -> Unit) {
        state.value = VoiceState.SPEAKING
        timer = scope.launch { delay(VOICE_SPEAK_MAX_MS); if (g == gen) { io.stopAll(); finish(g) } }
        io.speak(text) { ok ->
            if (g != gen || state.value != VoiceState.SPEAKING) return@speak
            timer?.cancel(); timer = null
            if (ok) next() else finish(g)
        }
    }

    private fun listen(g: Int, onAnswer: (Boolean) -> Unit) {
        state.value = VoiceState.LISTENING
        timer = scope.launch {
            delay(VOICE_LISTEN_MS)
            if (g != gen) return@launch
            io.stopAll()
            delay(VOICE_FINAL_GRACE_MS)
            if (g == gen) finish(g)
        }
        io.listen(
            onPartial = { },   // 중간 결과는 해석하지 않는다 (최종을 기다린다)
            onFinal = { text -> if (g == gen) { finish(g); parseVoiceAnswer(text)?.let(onAnswer) } },
            onError = { if (g == gen) finish(g) },
        )
    }

    private fun finish(g: Int) {
        if (g != gen) return
        gen++
        timer?.cancel(); timer = null
        state.value = VoiceState.IDLE
    }
}
