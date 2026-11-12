package ai.yeoro.service
import ai.yeoro.ArrivalInfo
import ai.yeoro.EngineBus
import ai.yeoro.ui.MainActivity
import ai.yeoro.PendingAsk
import ai.yeoro.R
import android.graphics.drawable.Icon
import android.app.*
import android.content.Context
import android.content.Intent

object Notifier {
    const val CH_ASK = "yeoro_ask"; const val CH_REC = "yeoro_rec"; const val CH_ARRIVE = "yeoro_arrive"; const val ID_ASK = 11; const val ID_REC = 10; const val ID_ARRIVE = 12
    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "여행 제안", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_REC, "기록 상태", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ARRIVE, "도착 인증", NotificationManager.IMPORTANCE_HIGH))
    }
    fun recording(c: Context, text: String): Notification = Notification.Builder(c, CH_REC)
        .setSmallIcon(R.drawable.ic_yeoro).setContentTitle("여로").setContentText(text).setOngoing(true).build()
    fun ask(c: Context, kind: String, title: String, body: String, yes: String, no: String, timeoutMs: Long = ASK_TTL_MS) {
        fun pi(accepted: Boolean) = PendingIntent.getBroadcast(c, if (accepted) 1 else 2,
            Intent(c, AnswerReceiver::class.java).putExtra("accepted", accepted).putExtra("kind", kind), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CH_ASK).setSmallIcon(R.drawable.ic_yeoro).setLargeIcon(Icon.createWithResource(c, R.drawable.ic_yeoro)).setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setCategory(Notification.CATEGORY_RECOMMENDATION).setAutoCancel(true).setTimeoutAfter(timeoutMs)
            .addAction(Notification.Action.Builder(null, no, pi(false)).build())
            .addAction(Notification.Action.Builder(null, yes, pi(true)).build()).build()
        c.getSystemService(NotificationManager::class.java).notify(ID_ASK, n)
    }

    /** 대기 중인 질문을 알림으로. 앱 안 카드와 같은 문구를 쓴다. */
    fun ask(c: Context, p: PendingAsk, timeoutMs: Long = askRemainingMs(p.askedMs, EngineBus.nowMs())) {   // askedMs는 엔진 시계(시연 보정 포함)
        val t = askText(p)
        ask(c, p.kind, t.title, t.body, t.yes, t.no, timeoutMs)
    }
    fun cancelAsk(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(ID_ASK)

    /** 지금 화면이 안 보일 때의 도착 알림(자체 채널 "도착 인증"). 누르면 앱을 지금 탭으로 연다. 카드와 같이 30초 뒤 사라진다. */
    fun arrival(c: Context, a: ArrivalInfo) {
        val open = PendingIntent.getActivity(c, 3, MainActivity.openLiveIntent(c), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CH_ARRIVE).setSmallIcon(R.drawable.ic_yeoro).setLargeIcon(Icon.createWithResource(c, R.drawable.ic_yeoro))
            .setContentTitle(arrivalTitle(a.place, a.visit)).setContentText("차가 도착을 확인했어요. 스탬프와 혜택을 확인하세요.")
            .setCategory(Notification.CATEGORY_STATUS).setAutoCancel(true).setTimeoutAfter(ARRIVAL_SHOW_MS).setContentIntent(open).build()
        c.getSystemService(NotificationManager::class.java).notify(ID_ARRIVE, n)
    }
    fun cancelArrival(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(ID_ARRIVE)
}

data class AskText(val title: String, val body: String, val yes: String, val no: String)

/** 질문 문구 (알림·앱 카드 공용) */
fun askText(p: PendingAsk): AskText = if (p.kind == "retro") {
    val since = p.sinceMs?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime() }
    val from = since?.let { "%02d:%02d 출발부터".format(it.hour, it.minute) } ?: "출발할 때부터"
    AskText("오늘, 여행이었나요?", "$from 기록해 뒀어요. 앨범으로 남길까요?", "앨범으로 남기기", "아니요, 지워 주세요")
} else {
    val where = askWhere(p)
    AskText("가족 여행 가시나요?", (if (where.isNotEmpty()) "$where. " else "") + "출발하면 조용히 기록할게요.", "여행 기록 시작", "오늘은 그냥")
}
