package ai.yeoro.ui.live

import ai.yeoro.engine.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromoTest {
    private val photos = Promo.Photos(7, 4, "강릉 경포해변")
    private val memory = Promo.Memory(3, "1년 전 이번 주", "강릉 경포해변")

    @Test fun `모드×기어 표 - 일상·앨범 준비는 늘, 기록 중은 P단만, 나머지는 없음`() {
        for (mode in LiveMode.entries) for (gear in Gear.entries) {
            val shown = when (mode) {
                LiveMode.DAILY, LiveMode.ALBUM_READY -> true
                LiveMode.RECORDING -> gear == Gear.P
                else -> false
            }
            assertEquals("mode=$mode gear=$gear", if (shown) photos else null, livePromo(mode, gear, photos, memory))
        }
    }

    @Test fun `회상 카드는 일상 화면의 P단에서만`() {
        for (mode in LiveMode.entries) for (gear in Gear.entries) {
            val shown = mode == LiveMode.DAILY && gear == Gear.P
            assertEquals("mode=$mode gear=$gear", if (shown) memory else null, livePromo(mode, gear, null, memory))
        }
    }

    @Test fun `사진 카드가 회상 카드보다 먼저, 사진이 없으면 회상`() {
        assertEquals(photos, livePromo(LiveMode.DAILY, Gear.P, photos, memory))
        assertEquals(memory, livePromo(LiveMode.DAILY, Gear.P, null, memory))
        assertNull(livePromo(LiveMode.RECORDING, Gear.P, null, memory))
        assertNull(livePromo(LiveMode.ALBUM_READY, Gear.P, null, memory))
        assertNull(livePromo(LiveMode.DAILY, Gear.P, null, null))
        assertNull(livePromo(LiveMode.ARRIVAL, Gear.P, photos, memory))
    }

    @Test fun `카드 문구`() {
        assertEquals("가족이 사진 4장을 보냈어요", photosTitle(photos))
        assertEquals("강릉 경포해변 앨범의 제자리에 넣었어요", photosSub(photos))
        assertEquals("앨범의 제자리에 넣었어요", photosSub(photos.copy(place = null)))
    }
}
