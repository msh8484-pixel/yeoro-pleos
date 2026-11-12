package ai.yeoro.service
import ai.yeoro.EngineBus
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val kind = i.getStringExtra("kind")
        val accepted = i.getBooleanExtra("accepted", false)
        // 앱 안 카드와 같은 경로(EngineBus.answer의 compareAndSet): 이미 답했거나 사라진 질문의 알림 답은 엔진에 가지 않는다
        val taken = EngineBus.answer(accepted, kind)
        android.util.Log.i("YeoroEngine", "answer kind=$kind accepted=$accepted taken=$taken")
        Notifier.cancelAsk(c)
    }
}
