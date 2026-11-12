package ai.yeoro.ui.live

import ai.yeoro.engine.TripState
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveModeTest {
    private fun mode(consent: Boolean? = true, state: TripState? = TripState.DAILY, ask: Boolean = false, arrival: Boolean = false, leftP: Boolean = false, started: Boolean = false) =
        liveMode(consent, state, ask, arrival, leftP, started)

    @Test fun `동의가 꺼져 있으면 엔진의 마지막 상태가 무엇이든 꺼짐`() {
        TripState.entries.forEach { st -> assertEquals("state=$st", LiveMode.OFF, mode(consent = false, state = st)) }
        assertEquals(LiveMode.OFF, mode(consent = false, state = null))
    }

    @Test fun `나중에(아직 안 정함)도 꺼짐`() {
        assertEquals(LiveMode.OFF, mode(consent = null, state = null))
        assertEquals(LiveMode.OFF, mode(consent = null, state = TripState.TRIP))
    }

    @Test fun `여행 중에 동의를 끄면 기록 중이 아니라 꺼짐`() {
        assertEquals(LiveMode.RECORDING, mode(state = TripState.TRIP))
        assertEquals(LiveMode.OFF, mode(consent = false, state = TripState.TRIP, ask = true, arrival = true, started = true))
    }

    @Test fun `동의가 켜져 있으면 기존 우선순위`() {
        assertEquals(LiveMode.ASK, mode(ask = true, arrival = true, state = TripState.TRIP))
        assertEquals(LiveMode.ARRIVAL, mode(arrival = true, state = TripState.STAYING))
        assertEquals(LiveMode.RECORDING, mode(arrival = true, leftP = true, state = TripState.STAYING))
        assertEquals(LiveMode.RECORDING, mode(state = TripState.CANDIDATE, started = true))
        assertEquals(LiveMode.START, mode(state = TripState.DAILY, started = true))
        assertEquals(LiveMode.ALBUM_READY, mode(state = TripState.ALBUM_READY))
        assertEquals(LiveMode.WAITING, mode(state = null))
        assertEquals(LiveMode.DAILY, mode(state = TripState.DAILY))
        assertEquals(LiveMode.OFF, mode(state = TripState.OFF))
    }
}
