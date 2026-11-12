package ai.yeoro.voice

/**
 * 말하기(TTS)와 듣기(STT) 한 쌍. 구현은 Pleos 온디바이스(PleosVoiceIo), 디버그 빌드는 PCM 주입(PcmVoiceIo), 테스트는 가짜.
 * 콜백은 메인 스레드에서 부른다. 실패는 조용히(speak → onDone(false), listen → onError) 알리고 예외를 던지지 않는다.
 */
interface VoiceIo {
    /** ok=false: 오류·중지·초기화 실패 */
    fun speak(text: String, onDone: (ok: Boolean) -> Unit)
    /** onFinal은 최종 결과(completed=true)만. 무음에서 멈추면 아무 콜백이 없을 수 있다(부르는 쪽이 시간 초과를 갖는다) */
    fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: () -> Unit)
    /** 말하기·듣기를 멈춘다. 멈춘 뒤에도 듣기의 최종 결과가 늦게 올 수 있다 */
    fun stopAll()
    fun release()
}
