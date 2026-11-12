package ai.yeoro.service

import ai.yeoro.PendingAsk
import org.junit.Assert.*
import org.junit.Test

class AskPolicyTest {
    private fun ask(d: String? = "강릉", km: Double? = 238.4) = PendingAsk("departure", 100, d, km, emptySet(), askedMs = 1_000L)

    @Test fun `notifies when live screen hidden and ask not yet posted`() = assertTrue(shouldNotifyAsk(false, ask(), null))
    @Test fun `no notification while live screen visible`() = assertFalse(shouldNotifyAsk(true, ask(), null))
    @Test fun `no notification without ask`() = assertFalse(shouldNotifyAsk(false, null, null))
    @Test fun `no repost of same ask`() { val a = ask(); assertFalse(shouldNotifyAsk(false, a, a)) }
    @Test fun `voice ask only while live screen visible, departure only, once`() {
        val a = ask()
        assertTrue(shouldVoiceAsk(true, a, null))
        assertFalse(shouldVoiceAsk(false, a, null))
        assertFalse(shouldVoiceAsk(true, null, null))
        assertFalse(shouldVoiceAsk(true, a, a))
        assertTrue(shouldVoiceAsk(true, a.copy(askedMs = 2_000L), a))
        assertFalse(shouldVoiceAsk(true, a.copy(kind = "retro"), null))
    }
    @Test fun `remaining time counts down from raise and never negative`() {
        assertEquals(600_000L - 5_000L, askRemainingMs(1_000L, 6_000L))
        assertEquals(0L, askRemainingMs(1_000L, 9_999_999L))
    }
    @Test fun `where text joins destination and rounded km`() {
        assertEquals("강릉 · 238km", askWhere(ask()))
        assertEquals("강릉", askWhere(ask(km = null)))
        assertEquals("", askWhere(ask(d = null, km = null)))
    }
}
