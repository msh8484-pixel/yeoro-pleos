package ai.yeoro.ui.live

import ai.yeoro.EngineBus
import ai.yeoro.PendingAsk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class PendingAskTest {
    private fun ask(kind: String) = PendingAsk(kind = kind, score = 100, destination = "강릉 경포해변", distanceKm = 238.0, seats = emptySet(), askedMs = 1L)
    @After fun clear() { EngineBus.pendingAsk.value = null; EngineBus.departureAccepted.value = null }

    @Test fun acceptingDeparture_clearsAskAndMarksStarted() {
        val a = ask("departure"); EngineBus.pendingAsk.value = a
        EngineBus.answer(true, "departure")
        assertNull(EngineBus.pendingAsk.value)
        assertEquals(a, EngineBus.departureAccepted.value)
    }
    @Test fun decliningDeparture_clearsAskWithoutStart() {
        EngineBus.pendingAsk.value = ask("departure")
        EngineBus.answer(false, "departure")
        assertNull(EngineBus.pendingAsk.value); assertNull(EngineBus.departureAccepted.value)
    }
    @Test fun lateAnswerForOtherKind_keepsAsk() {
        val a = ask("retro"); EngineBus.pendingAsk.value = a
        EngineBus.answer(true, "departure")
        assertEquals(a, EngineBus.pendingAsk.value); assertNull(EngineBus.departureAccepted.value)
    }
    @Test fun acceptingRetro_doesNotMarkDepartureStart() {
        EngineBus.pendingAsk.value = ask("retro")
        EngineBus.answer(true, "retro")
        assertNull(EngineBus.pendingAsk.value); assertNull(EngineBus.departureAccepted.value)
    }

    /** block 동안 EngineBus.answers로 나간 답을 모은다 */
    private fun emitted(block: () -> Unit): List<Pair<Boolean, String?>> = runBlocking {
        val got = mutableListOf<Pair<Boolean, String?>>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { EngineBus.answers.collect { got += it } }
        block(); repeat(3) { kotlinx.coroutines.yield() }; job.cancel(); got
    }

    @Test fun answerReachesEngineOnlyOnce_strayTapOnFadingCardIsDropped() {
        EngineBus.pendingAsk.value = ask("departure")
        val got = emitted {
            assertTrue(EngineBus.answer(true, "departure"))
            assertFalse(EngineBus.answer(true, "departure"))    // 사라지는 중인 카드를 한 번 더
            assertFalse(EngineBus.answer(false, "departure"))
        }
        assertEquals(listOf(true to "departure"), got)
    }
    @Test fun noPendingAsk_answerDoesNotReachEngine() {
        assertEquals(emptyList<Pair<Boolean, String?>>(), emitted { assertFalse(EngineBus.answer(true, "departure")) })
    }
    @Test fun lateNotificationAnswerForOtherKind_doesNotReachEngine() {
        EngineBus.pendingAsk.value = ask("retro")
        assertEquals(emptyList<Pair<Boolean, String?>>(), emitted { assertFalse(EngineBus.answer(true, "departure")) })
    }
    @Test fun answerAfterTimeoutCleared_isDropped() {
        val a = ask("retro"); EngineBus.pendingAsk.value = a
        EngineBus.pendingAsk.compareAndSet(a, null)   // 시간 초과로 거둠 (RecorderService.raiseAsk)
        assertEquals(emptyList<Pair<Boolean, String?>>(), emitted { assertFalse(EngineBus.answer(true, "retro")) })
    }
    @Test fun notificationReceiverGoesThroughSameCas() {
        EngineBus.pendingAsk.value = ask("retro")
        val r = ai.yeoro.service.AnswerReceiver()
        val i = android.content.Intent().putExtra("accepted", true).putExtra("kind", "retro")
        val got = emitted {
            r.onReceive(androidx.test.core.app.ApplicationProvider.getApplicationContext(), i)
            r.onReceive(androidx.test.core.app.ApplicationProvider.getApplicationContext(), i)   // 두 번 눌러도 한 번만
        }
        assertEquals(listOf(true to "retro"), got); assertNull(EngineBus.pendingAsk.value)
    }
}
