package ai.yeoro.ui.live

import ai.yeoro.engine.Gear
import ai.yeoro.ui.album.scene
import ai.yeoro.ui.common.PhotoImage
import androidx.compose.ui.draw.clip
import ai.yeoro.ui.theme.Dusk
import ai.yeoro.ux.LocalDrivingRestricted
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp

/** 지금 화면의 알림 카드: 가족 사진 도착, 1년 전 이번 주 회상 */
sealed interface Promo {
    data class Photos(val tripId: Long, val count: Int, val place: String?) : Promo
    /** thumb: 표지 사진 파일(없으면 장면 일러스트 hour·rained·sea) */
    data class Memory(val tripId: Long, val title: String, val sub: String, val thumb: String? = null,
                      val hour: Int = 14, val rained: Boolean = false, val sea: Boolean = false) : Promo
}

/**
 * 어느 장면에 카드를 보일까 (정적, 모션 없음). 우선순위: 사진 > 회상.
 * 사진 카드: 일상·앨범 준비에는 늘, 기록 중에는 P단일 때만. 회상 카드: 일상 화면의 P단에서만. 질문·도착·꺼짐·기록 시작·신호 기다림에는 없다.
 */
fun livePromo(mode: LiveMode, gear: Gear, photos: Promo.Photos?, memory: Promo.Memory?): Promo? {
    val photosShown = when (mode) {
        LiveMode.DAILY, LiveMode.ALBUM_READY -> true
        LiveMode.RECORDING -> gear == Gear.P
        else -> false
    }
    val memoryShown = mode == LiveMode.DAILY && gear == Gear.P
    return (if (photosShown) photos else null) ?: (if (memoryShown) memory else null)
}

fun photosTitle(p: Promo.Photos) = "가족이 사진 ${p.count}장을 보냈어요"
fun photosSub(p: Promo.Photos) = (p.place?.let { "$it 앨범의 " } ?: "앨범의 ") + "제자리에 넣었어요"

private val KoPara = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
private val MinTouch = 76.dp

/**
 * 정적인 카드(움직임 없음). [앨범 보기]는 주행 중(UX 제한)에 숨기고 안내 문구로 바꾼다.
 * compact: 기록 중 화면처럼 자리가 좁을 때 한 줄짜리.
 */
@Composable fun PromoCard(p: Promo, onOpen: (Long) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, onClose: (() -> Unit)? = null, showThumb: Boolean = true) {
    val restricted = LocalDrivingRestricted.current
    val (title, sub, tripId) = when (p) {
        is Promo.Photos -> Triple(photosTitle(p), photosSub(p), p.tripId)
        is Promo.Memory -> Triple(p.title, p.sub, p.tripId)
    }
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(2.dp, Dusk.copy(alpha = 0.55f))) {
        Row(Modifier.padding(horizontal = 32.dp, vertical = if (compact) 20.dp else 28.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            if (p is Promo.Memory) { if (showThumb) MemoryThumb(p) else Box(Modifier.size(96.dp).background(Dusk.copy(alpha = 0.16f), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) { Icon(LiveIcons.Clock, null, Modifier.size(56.dp), tint = Dusk) } }
            else Box(Modifier.size(if (compact) 72.dp else 96.dp).background(Dusk.copy(alpha = 0.16f), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                Icon(LiveIcons.Photo, null, Modifier.size(if (compact) 44.dp else 56.dp), tint = Dusk)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall).merge(TextStyle(lineBreak = KoPara)),
                    color = MaterialTheme.colorScheme.onSurface)
                Text(if (restricted) "차를 세우면 앨범에서 보여 드릴게요." else sub, style = MaterialTheme.typography.bodyMedium.merge(TextStyle(lineBreak = KoPara)), color = muted())
            }
            if (!restricted) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (onClose != null) OutlinedButton(onClose, Modifier.heightIn(min = if (compact) MinTouch else 96.dp).widthIn(min = 160.dp), shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, lineColor())) {
                    Text("닫기", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Button({ onOpen(tripId) }, Modifier.heightIn(min = if (compact) MinTouch else 96.dp).widthIn(min = 240.dp), shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Dusk, contentColor = Color(0xFF1B1206))) {
                    Text("앨범 보기", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** 회상 카드 썸네일: 지난 여행 표지 사진, 없으면 그날 도착 시각·비의 장면 일러스트 (정적) */
@Composable private fun MemoryThumb(p: Promo.Memory) {
    val illustration = Modifier.fillMaxSize().scene(p.hour, p.rained, seed = 31, sea = p.sea)
    Box(Modifier.size(width = 180.dp, height = 120.dp).clip(RoundedCornerShape(16.dp))) {
        if (p.thumb != null) PhotoImage(p.thumb, Modifier.fillMaxSize(), maxPx = 400) { Box(illustration) } else Box(illustration)
    }
}
