package ai.yeoro.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 말하기 → (onDone ok) → 듣기 → 최종만 해석. 시간 초과·취소·늦은 콜백 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionTest {
    /** 콜백을 들고 있다가 테스트가 직접 부르는 가짜 */
    private class FakeIo : VoiceIo {
        val spoken = mutableListOf<String>()
        var speakDone: ((Boolean) -> Unit)? = null
        var listens = 0
        var partial: ((String) -> Unit)? = null
        var final: ((String) -> Unit)? = null
        var error: (() -> Unit)? = null
        var stops = 0
        var released = false
        override fun speak(text: String, onDone: (ok: Boolean) -> Unit) { spoken += text; speakDone = onDone }
        override fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: () -> Unit) { listens++; partial = onPartial; final = onFinal; error = onError }
        override fun stopAll() { stops++ }
        override fun release() { released = true }
    }

    private class Rig(scope: TestScope) {
        val io = FakeIo()
        val state = MutableStateFlow(VoiceState.IDLE)
        val seen = mutableListOf(VoiceState.IDLE)
        val answers = mutableListOf<Boolean>()
        val session = VoiceSession(io, scope.backgroundScope, state)
        fun ask() = session.ask("가족 여행 가시나요?") { answers += it }
        fun record() { if (seen.last() != state.value) seen += state.value }
    }

    @Test fun `말이 끝나기 전에는 듣지 않고, 끝나면 듣는다`() = runTest {
        val r = Rig(this); r.ask()
        assertEquals(listOf("가족 여행 가시나요?"), r.io.spoken)
        assertEquals(VoiceState.SPEAKING, r.state.value)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(0, r.io.listens)
        r.io.speakDone!!(true)
        assertEquals(1, r.io.listens); assertEquals(VoiceState.LISTENING, r.state.value)
    }

    @Test fun `말하기가 실패하거나 중지되면 듣지 않는다`() = runTest {
        val r = Rig(this); r.ask()
        r.io.speakDone!!(false)
        assertEquals(0, r.io.listens); assertEquals(VoiceState.IDLE, r.state.value); assertTrue(r.answers.isEmpty())
    }

    @Test fun `최종 응 이면 한 번 수락하고 상태는 IDLE-SPEAKING-LISTENING-IDLE`() = runTest {
        val r = Rig(this); r.ask(); r.record()
        r.io.speakDone!!(true); r.record()
        r.io.final!!("응 여행이야"); r.record()
        r.io.final!!("응 여행이야")   // 같은 듣기의 두 번째 최종은 무시
        assertEquals(listOf(true), r.answers)
        assertEquals(listOf(VoiceState.IDLE, VoiceState.SPEAKING, VoiceState.LISTENING, VoiceState.IDLE), r.seen)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(true), r.answers); assertEquals(VoiceState.IDLE, r.state.value)
    }

    @Test fun `거절 최종은 false, 모르는 최종은 답 없음`() = runTest {
        val r = Rig(this); r.ask(); r.io.speakDone!!(true); r.io.final!!("아니 그냥 가는 거야")
        assertEquals(listOf(false), r.answers)
        r.ask(); r.io.speakDone!!(true); r.io.final!!("음 글쎄")
        assertEquals(listOf(false), r.answers); assertEquals(VoiceState.IDLE, r.state.value)
    }

    @Test fun `중간 결과만으로는 답하지 않는다`() = runTest {
        val r = Rig(this); r.ask(); r.io.speakDone!!(true)
        r.io.partial!!("응")
        assertTrue(r.answers.isEmpty()); assertEquals(VoiceState.LISTENING, r.state.value)
    }

    @Test fun `6초 동안 답이 없으면 멈추고 grace 뒤 IDLE, 답 없음`() = runTest {
        val r = Rig(this); r.ask(); r.io.speakDone!!(true)
        advanceTimeBy(VOICE_LISTEN_MS - 1); runCurrent()
        assertEquals(0, r.io.stops); assertEquals(VoiceState.LISTENING, r.state.value)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, r.io.stops)
        advanceTimeBy(VOICE_FINAL_GRACE_MS); runCurrent()
        assertEquals(VoiceState.IDLE, r.state.value); assertTrue(r.answers.isEmpty())
        r.io.final!!("응")   // grace 뒤 늦은 최종은 버린다
        assertTrue(r.answers.isEmpty())
    }

    @Test fun `멈춘 뒤 grace 안에 온 최종은 반영한다`() = runTest {
        val r = Rig(this); r.ask(); r.io.speakDone!!(true)
        r.io.partial!!("응 여행")
        advanceTimeBy(VOICE_LISTEN_MS); runCurrent()
        assertEquals(1, r.io.stops)
        advanceTimeBy(VOICE_FINAL_GRACE_MS / 2); runCurrent()
        r.io.final!!("응 여행이야")
        assertEquals(listOf(true), r.answers); assertEquals(VoiceState.IDLE, r.state.value)
    }

    @Test fun `듣기 오류는 답 없이 IDLE`() = runTest {
        val r = Rig(this); r.ask(); r.io.speakDone!!(true)
        r.io.error!!()
        assertEquals(VoiceState.IDLE, r.state.value); assertTrue(r.answers.isEmpty())
    }

    @Test fun `말하는 중 취소하면 멈추고 IDLE, 늦은 onDone은 듣기를 열지 않는다`() = runTest {
        val r = Rig(this); r.ask()
        r.session.cancel()
        assertEquals(1, r.io.stops); assertEquals(VoiceState.IDLE, r.state.value)
        r.io.speakDone!!(true)
        assertEquals(0, r.io.listens); assertEquals(VoiceState.IDLE, r.state.value)
    }

    @Test fun `듣는 중 취소하면 멈추고 IDLE, 늦은 최종은 무시`() = runTest {
        val r = Rig(this); r.ask(); r.io.speakDone!!(true)
        r.session.cancel()
        assertEquals(1, r.io.stops); assertEquals(VoiceState.IDLE, r.state.value)
        r.io.final!!("응")
        assertTrue(r.answers.isEmpty())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, r.io.stops)   // 취소된 시간 초과가 다시 멈추지 않는다
    }

    @Test fun `쉴 때 취소는 아무것도 멈추지 않는다`() = runTest {
        val r = Rig(this); r.session.cancel()
        assertEquals(0, r.io.stops)
    }

    @Test fun `알리기는 말하기만 하고 끝나면 IDLE`() = runTest {
        val r = Rig(this); r.session.announce("강릉 경포해변, 두 번째 방문이에요")
        assertEquals(VoiceState.SPEAKING, r.state.value)
        r.io.speakDone!!(true)
        assertEquals(VoiceState.IDLE, r.state.value); assertEquals(0, r.io.listens)
    }

    @Test fun `말하기 콜백이 끝내 오지 않으면 IDLE로 돌아온다`() = runTest {
        val r = Rig(this); r.ask()
        advanceTimeBy(VOICE_SPEAK_MAX_MS + 1); runCurrent()
        assertEquals(VoiceState.IDLE, r.state.value); assertEquals(1, r.io.stops)
        r.io.speakDone!!(true)
        assertEquals(0, r.io.listens)
    }

    @Test fun `말하는 중 새 질문이 오면 앞의 것을 멈추고 새로 말한다`() = runTest {
        val r = Rig(this); r.session.announce("도착")
        val first = r.io.speakDone!!
        r.ask()
        assertEquals(1, r.io.stops); assertEquals(listOf("도착", "가족 여행 가시나요?"), r.io.spoken)
        first(false)   // 앞 말하기의 늦은 중지 콜백은 새 질문에 영향이 없다
        assertEquals(VoiceState.SPEAKING, r.state.value)
        r.io.speakDone!!(true)
        assertEquals(VoiceState.LISTENING, r.state.value)
    }
}
