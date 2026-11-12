package ai.yeoro.voice

import ai.yeoro.ArrivalInfo
import ai.yeoro.PendingAsk
import org.junit.Assert.*
import org.junit.Test

/** 음성 답 해석(보수적: 수락·거절이 함께 있거나 둘 다 없으면 모름)과 말할 문장 */
class VoiceAnswerTest {
    @Test fun `수락 낱말만 있으면 true`() {
        listOf("응 여행이야", "응, 여행이야.", "네", "그래 가자", "여행2야", "어", "어어", "OK", "오케이 시작해").forEach { assertEquals(it, true, parseVoiceAnswer(it)) }
    }
    @Test fun `거절 낱말만 있으면 false`() {
        listOf("아니 그냥 가는 거야", "아뇨", "오늘은 됐어", "아니요!", "싫어", "안 가", "출근하는 거야").forEach { assertEquals(it, false, parseVoiceAnswer(it)) }
    }
    @Test fun `둘 다 있거나 둘 다 없으면 null`() {
        listOf("여행 아니야", "", "   ", "음 글쎄", "어디 가", "...").forEach { assertNull(it, parseVoiceAnswer(it)) }
    }
    @Test fun `어는 낱말 전체일 때만 수락`() {
        assertNull(parseVoiceAnswer("어디"))
        assertEquals(true, parseVoiceAnswer("어, 맞아"))
    }
    @Test fun `짧은 수락 낱말은 낱말 전체일 때만 (네비·예약에 걸리지 않게)`() {
        listOf("네비 켜 줘", "예약했어", "응급실", "어디야", "네모난 거").forEach { assertNull(it, parseVoiceAnswer(it)) }
        listOf("네", "네네", "넵", "예", "응응", "네, 가자").forEach { assertEquals(it, true, parseVoiceAnswer(it)) }
    }
    @Test fun `부정과 함께 온 수락 낱말은 수락이 아니다`() {
        listOf("기록하지 마", "기록하지마", "안 좋아", "안좋아", "시작하지 말아 줘", "여행 안 해", "기록 못 해", "좋지 않아", "기록하기 싫어").forEach { assertNotEquals(it, true, parseVoiceAnswer(it)) }
    }
    @Test fun `부정만 있으면 거절`() {
        listOf("안 갈래", "안 해", "못 가").forEach { assertEquals(it, false, parseVoiceAnswer(it)) }
    }
    @Test fun `질문 문장은 출발 질문만`() {
        val p = PendingAsk("departure", 90, "강릉 경포해변", 238.0, emptySet(), 1L)
        assertEquals("가족 여행 가시나요?", askSpeech(p))
        assertNull(askSpeech(p.copy(kind = "retro")))
    }
    @Test fun `도착 인사는 도착 카드 제목과 같다`() {
        assertEquals("강릉 경포해변, 두 번째 방문이에요", arrivalSpeech(ArrivalInfo(1, "강릉 경포해변", 2, 0L, 3, "")))
        assertEquals("이곳, 첫 번째 방문이에요", arrivalSpeech(ArrivalInfo(1, null, 1, 0L, 3, "")))
    }
}
