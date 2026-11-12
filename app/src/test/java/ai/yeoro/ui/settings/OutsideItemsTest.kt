package ai.yeoro.ui.settings

import org.junit.Assert.*
import org.junit.Test

class OutsideItemsTest {
    @Test fun `AI 줄은 기능이 들어오기 전까지 숨긴다`() {
        assertFalse(SHOW_AI_DISCLOSURE)
        assertEquals(3, outsideItems().size)
        assertTrue(outsideItems().none { it.first.startsWith("AI") })
        assertEquals(4, outsideItems(showAi = true).size)
    }
    @Test fun `여행 등록 줄은 보내는 것과 보내지 않는 위치 기록을 밝힌다`() {
        val reg = outsideItems().first().second
        listOf("공유 토큰", "출발 시각", "목적지 이름", "기기 확인 값", "위치 기록").forEach { assertTrue(it, it in reg) }
    }
    @Test fun `공공데이터 줄은 도착 시각을, 삭제 안내는 동의 조건을 밝힌다`() {
        assertTrue(outsideItems()[2].second.contains("목적지 좌표와 도착 시각"))
        assertTrue(OUTSIDE_NOTE.contains("동의가 켜져 있을 때 서버의 사진과 등록도 함께 지워요"))
    }
}
