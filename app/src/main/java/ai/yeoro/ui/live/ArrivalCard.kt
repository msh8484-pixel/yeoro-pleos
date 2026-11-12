package ai.yeoro.ui.live

import ai.yeoro.ArrivalInfo
import ai.yeoro.ui.album.VisitSide
import ai.yeoro.ui.album.scene
import ai.yeoro.ui.album.seaLike
import ai.yeoro.ui.common.PhotoImage
import androidx.compose.ui.draw.clip
import ai.yeoro.service.arrivalTitle
import ai.yeoro.service.regularStage
import ai.yeoro.service.regularStageText
import ai.yeoro.service.stageLegend
import ai.yeoro.service.stampPlace
import ai.yeoro.ui.theme.Pretendard
import ai.yeoro.ui.theme.Dusk
import ai.yeoro.ui.theme.duskInk
import ai.yeoro.ux.rememberMotionProgress
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId

/** 2a 혜택은 고정 예시 데이터 ("예시" 태그). 실제 제휴처 이름은 쓰지 않는다. */
private data class Benefit(val icon: ImageVector, val title: String, val sub: String)
private val ExampleBenefits = listOf(
    Benefit(LiveIcons.Ticket, "가족 관광쿠폰 1만 원", "관광지·전통시장"),
    Benefit(LiveIcons.Gift, "지역 카페 음료 10%", "오늘까지"),
)
private val Ok = Color(0xFF3FB27F)

/**
 * 도착 인증 카드 (시안 장면 5). 차가 확인한 방문 → 스탬프, 단골 단계, 받은 혜택, 사진 QR.
 * 움직임(카드 등장, 스탬프 스프링, 단계 막대)은 모두 rememberMotionProgress를 거쳐 모션이 허용될 때만 돈다. 아니면 처음부터 최종 모습.
 */
@Composable fun ArrivalCard(a: ArrivalInfo, wide: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val enter by rememberMotionProgress("arrive-${a.arrivedMs}", 400)
    // ModulateAlpha: 오프스크린 레이어를 만들지 않아 스탬프가 부풀 때 카드 경계에서 잘리지 않는다
    val m = modifier.graphicsLayer { alpha = enter; translationY = (1f - enter) * 32.dp.toPx(); compositingStrategy = CompositingStrategy.ModulateAlpha }
    if (wide) Row(m, horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        ArrivalInfoColumn(a, Modifier.weight(1f))
        QrCard(a, onClose, vertical = true, Modifier.width(520.dp))
    } else Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(40.dp)) {
        ArrivalInfoColumn(a, Modifier.fillMaxWidth())
        QrCard(a, onClose, vertical = false, Modifier.fillMaxWidth())
    }
}

@Composable private fun ArrivalInfoColumn(a: ArrivalInfo, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(modifier, verticalArrangement = Arrangement.spacedBy(40.dp)) {
        // 주인공: 큰 스탬프 + 한 줄 제목
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Box(Modifier.size(340.dp).background(Dusk.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) { Stamp(a, 284.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val t = Instant.ofEpochMilli(a.arrivedMs).atZone(ZoneId.systemDefault()).toLocalTime()
                val proof = listOfNotNull("%02d:%02d 주차".format(t.hour, t.minute), a.seats.takeIf { it > 0 }?.let { "${it}명" }, "차가 확인한 방문").joinToString(" · ")
                Text(proof, style = MaterialTheme.typography.labelLarge, color = duskInk())
                Text(arrivalTitle(a.place, a.visit), style = MaterialTheme.typography.headlineLarge, color = ink)
            }
        }
        // 단골 단계 + 그때
        Column(Modifier.fillMaxWidth().background(ink.copy(alpha = 0.04f), RoundedCornerShape(28.dp)).padding(horizontal = 32.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(regularStageText(a.visit), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = ink)
                Text(stageLegend(a.visit, hasThen = a.then != null), style = MaterialTheme.typography.labelMedium, color = muted())
            }
            StageBars(a)
            a.then?.let { ThenLine(it, a.place) }
        }
        // 혜택: 아이콘 칸 두 개 (예시)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { ExampleBenefits.forEach { b -> BenefitTile(b, Modifier.weight(1f)) { Nearby.open(a.place, a.seats) } } }
    }
}

/** 원형 테두리 + 숫자. 등장: scale 2.2→1, 회전 −20°→−8° 스프링 (모션 허용 시). */
@Composable private fun Stamp(a: ArrivalInfo, size: Dp = 220.dp) {
    val p by rememberMotionProgress("stamp-${a.arrivedMs}", spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), delayMs = 250)
    val c = duskInk()
    val year = Instant.ofEpochMilli(a.arrivedMs).atZone(ZoneId.systemDefault()).year
    Box(Modifier.size(size).graphicsLayer {
        val s = 2.2f + (1f - 2.2f) * p
        scaleX = s; scaleY = s; rotationZ = -20f + 12f * p; alpha = p.coerceIn(0f, 1f)
    }.border(7.dp, c, CircleShape).padding(14.dp).border(2.dp, c.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${a.visit}", style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp, lineHeight = (size.value * 0.46f).sp, fontFeatureSettings = "tnum"), color = c)
            Text("${stampPlace(a.place)} · $year", style = MaterialTheme.typography.labelMedium, color = c, maxLines = 1)
        }
    }
}

/** 두 번째 방문부터: 지난 방문 사진(없으면 일러스트) 썸네일과 "그때 2025.11.01 · 오후 2시 41분 도착". 정적 */
@Composable private fun ThenLine(t: VisitSide, place: String?) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        val thumb = Modifier.size(width = 104.dp, height = 76.dp).clip(RoundedCornerShape(12.dp))
        val illustration = Modifier.fillMaxSize().scene(t.hour, t.rained, seed = 31, sea = seaLike(place))
        Box(thumb) { if (t.photoFile != null) PhotoImage(t.photoFile, Modifier.fillMaxSize(), maxPx = 300) { Box(illustration) } else Box(illustration) }
        // 카드에는 날짜와 도착 시각만
        Text("그때 ${t.dateText} · ${t.arriveText.replace(" ", "\u00A0")}", Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 단골 단계 막대 3칸. 지난 단계는 바로 켜져 있고, 이번 방문 칸은 스탬프 뒤에 채워진다. */
@Composable private fun StageBars(a: ArrivalInfo) {
    val stage = regularStage(a.visit)
    val fill by rememberMotionProgress("stage-${a.arrivedMs}", 500, delayMs = 700)
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        (1..3).forEach { i ->
            val on = when { i < stage -> 1f; i == stage -> fill; else -> 0f }
            Box(Modifier.weight(1f).height(16.dp).background(lerp(off, Dusk, on), RoundedCornerShape(8.dp)))
        }
    }
}

@Composable private fun BenefitTile(b: Benefit, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick).border(1.dp, lineColor(), RoundedCornerShape(24.dp)).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(64.dp).background(Dusk.copy(alpha = 0.16f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(b.icon, null, Modifier.size(36.dp), tint = duskInk())
        }
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(b.sub, style = MaterialTheme.typography.labelMedium, color = muted(), maxLines = 1)
                Text("예시", Modifier.border(1.dp, muted().copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 0.dp),
                    style = MaterialTheme.typography.labelMedium, color = muted())
            }
        }
        Icon(LiveIcons.Check, "받음", Modifier.size(40.dp), tint = Ok)
    }
}

/** 사진 QR 카드. vertical: 넓은 화면의 오른쪽 세로 카드, 아니면(좁은 창) QR과 글을 가로로. */
@Composable private fun QrCard(a: ArrivalInfo, onClose: () -> Unit, vertical: Boolean, modifier: Modifier) {
    val code: @Composable (Dp) -> Unit = { s -> QrCode(a.uploadUrl, s, "사진 올리기 QR") }
    val texts: @Composable (Alignment.Horizontal, TextAlign) -> Unit = { h, ta ->
        Column(horizontalAlignment = h, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("오늘 사진은 각자 폰에서", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = ta)
            Text("앱 설치 없이 스캔 한 번", style = MaterialTheme.typography.bodyMedium, color = muted(), textAlign = ta)
        }
    }
    val close: @Composable (Modifier) -> Unit = { m ->
        Button(onClose, m.heightIn(min = 96.dp), shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text("닫기", style = MaterialTheme.typography.titleSmall)
        }
    }
    Surface(modifier, shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, lineColor())) {
        if (vertical) Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            code(340.dp)
            texts(Alignment.CenterHorizontally, TextAlign.Center)
            close(Modifier.fillMaxWidth())
        } else Row(Modifier.padding(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            code(240.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                texts(Alignment.Start, TextAlign.Start)
                close(Modifier.fillMaxWidth())
            }
        }
    }
}
