package ai.yeoro.voice

import ai.yeoro.ArrivalInfo
import ai.yeoro.PendingAsk
import ai.yeoro.service.arrivalTitle

/** 질문 뒤 듣는 시간. 이 뒤 stop()한다 */
const val VOICE_LISTEN_MS = 6_000L
/** stop 뒤 최종 결과를 기다리는 시간 */
const val VOICE_FINAL_GRACE_MS = 800L
/** 말하기 콜백(onDone·onStop·onError)이 끝내 오지 않을 때 포기하는 시간 (서비스 연결이 끊긴 경우 등) */
const val VOICE_SPEAK_MAX_MS = 15_000L

// 수락 낱말(두 글자 이상)은 공백·문장부호를 지운 문장에서 부분 문자열로 찾는다 (STT 변형 "여행2야"도 통과)
private val Accept = listOf("그래", "맞아", "여행", "가자", "좋아", "시작", "기록", "오케이", "ok")
// 한 글자 수락(응·네·예·어)은 낱말 전체일 때만: "네비 켜 줘", "예약했어", "어디"에 걸리지 않게
private val AcceptWords = setOf("응", "응응", "어", "어어", "네", "네네", "넵", "예", "예예")
private val Reject = listOf("아니", "아뇨", "아니요", "그냥", "됐어", "괜찮아", "싫어", "안해", "안가", "출근")
// 부정: 따로 선 "안"/"못" 낱말, 또는 이어 붙인 문장의 "~지 마", "~지 말", "말아", "않", "싫"
private val NegWords = setOf("안", "못")
private val NegParts = listOf("지마", "지말", "말아", "않", "싫")
private val Separators = Regex("[^\\p{L}\\p{N}]+")

/**
 * 음성 답 해석: true 수락, false 거절, null 모름(빈 문장, 둘 다 들어 있음, 해당 낱말 없음). 잘못 수락하느니 못 알아듣는다.
 * 부정("안 좋아", "기록하지 마", "안좋아")과 함께 온 수락 낱말은 수락으로 치지 않는다(모름). 수락 낱말 없이 부정만 있으면("안 갈래") 거절.
 */
fun parseVoiceAnswer(text: String): Boolean? {
    val words = text.lowercase().split(Separators).filter { it.isNotEmpty() }
    val joined = words.joinToString("")
    val yes = words.any { it in AcceptWords } || Accept.any { joined.contains(it) }
    // 확실한 부정: 혼자서도 거절이 된다
    val negated = words.any { it in NegWords } || NegParts.any { joined.contains(it) }
    // 붙여 쓴 "안좋아"·"못가" 같은 낱말: 수락을 모름으로 돌릴 뿐 혼자서는 거절로 치지 않는다 ("안내"·"안전" 같은 낱말이 있어서)
    val maybeNegated = words.any { it.length > 1 && (it.startsWith("안") || it.startsWith("못")) }
    val no = negated || Reject.any { joined.contains(it) }
    return when {
        yes && (no || maybeNegated) -> null
        yes -> true
        no -> false
        else -> null
    }
}

/** 출발 질문만 소리로 묻는다 (소급 질문은 화면 카드만) */
fun askSpeech(p: PendingAsk): String? = if (p.kind == "departure") "가족 여행 가시나요?" else null

/** 도착 인사 = 도착 카드 제목 ("강릉 경포해변, 두 번째 방문이에요") */
fun arrivalSpeech(a: ArrivalInfo): String = arrivalTitle(a.place, a.visit)
