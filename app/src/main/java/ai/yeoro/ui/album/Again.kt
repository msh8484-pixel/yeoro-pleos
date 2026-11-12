package ai.yeoro.ui.album

import ai.yeoro.cloud.TripOutside
import ai.yeoro.service.regularStage
import ai.yeoro.service.regularStageText
import ai.yeoro.ui.live.LiveIcons
import ai.yeoro.ui.live.QrCode
import ai.yeoro.ui.theme.AlbumColors
import ai.yeoro.ui.theme.Pretendard
import ai.yeoro.ux.rememberMotionProgress
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.net.URI
import java.net.URLEncoder

/*
 * 다시 가기 (시안 장면 11). 차 안에서는 예약하지 않는다: 지도·홈페이지 링크를 QR로 폰에 넘긴다.
 * 앨범의 마지막 쪽과 홈 정기 방문의 [다시 가기] 대화상자가 같은 패널(AgainPanel)을 쓴다.
 */

data class AgainLink(val label: String, val url: String)

private const val NAVER_SEARCH = "https://map.naver.com/p/search/"

/** 네이버 지도 검색 주소. 이름은 앞뒤 공백을 떼고 UTF-8로 인코딩하며 공백은 %20 ("강릉 경포해변" → %EA%B0%95…%20…) */
fun naverMapUrl(name: String): String = NAVER_SEARCH + URLEncoder.encode(name.trim(), Charsets.UTF_8).replace("+", "%20")

/** QR로 넘겨도 되는 웹 주소: http/https, 호스트가 있고, 공백·제어 문자가 없다(javascript:·data: 등은 거부) */
fun isWebUrl(s: String?): Boolean {
    if (s.isNullOrBlank() || s.length > 512 || s.any { it.isWhitespace() || it.isISOControl() }) return false
    val u = runCatching { URI(s) }.getOrNull() ?: return false
    return u.scheme?.lowercase() in setOf("http", "https") && !u.host.isNullOrEmpty()
}

/**
 * [지도에서 보기] + 홈페이지(장소 홈페이지, 없거나 못 쓰면 축제 홈페이지)가 http(s)면 [홈페이지]. 최대 2개.
 * 받아 둔 도착 쪽 바깥만 본다(출발 쪽은 집 근처라 다시 갈 곳이 아니다). 목적지 이름이 없으면 빈 목록(다시 가기 쪽을 만들지 않는다).
 */
fun againLinks(destName: String?, outside: TripOutside?): List<AgainLink> {
    val dest = destName?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val map = AgainLink("지도에서 보기 · 네이버 지도", naverMapUrl(dest))
    val home = outside?.arrival?.let { o ->
        o.place?.homepage?.trim()?.takeIf(::isWebUrl)?.let { AgainLink("홈페이지 · ${o.place.name?.takeIf { n -> n.isNotBlank() } ?: dest}", it) }
            ?: o.festival?.homepage?.trim()?.takeIf(::isWebUrl)?.let { AgainLink("홈페이지 · ${o.festival.name?.takeIf { n -> n.isNotBlank() } ?: dest}", it) }
    }
    return listOfNotNull(map, home).take(2)
}

/** 단골 단계 줄: "단골까지 한 번 남았어요 · 세 번째 방문이면 단골이에요", 이미 단골이면 "단골이에요" */
fun againStageLine(visit: Int): String =
    if (regularStage(visit) >= 3) regularStageText(visit) else "${regularStageText(visit)} · 세 번째 방문이면 단골이에요"

/* ───────────────────────── 패널 ───────────────────────── */

private val KoHeading = LineBreak.Heading.copy(wordBreak = LineBreak.WordBreak.Phrase)
private val KoParagraph = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
private val ChipIcon = androidx.compose.ui.graphics.Color(0xFFFFCF8F)

/** 다시 가면 쓸 수 있는 혜택: 2a처럼 고정 예시 데이터("예시" 꼬리표). 실제 제휴처 이름은 쓰지 않는다 */
private data class AgainBenefit(val icon: ImageVector, val title: String, val sub: String)
private val ExampleBenefits = listOf(
    AgainBenefit(LiveIcons.Ticket, "재방문 가족 관광쿠폰 10,000원", "다시 가면 지역 관광지·전통시장에서"),
    AgainBenefit(LiveIcons.Gift, "단골 가게 10%", "세 번째 방문부터"),
)

/**
 * 다시 가기 패널 (정적 글 + 링크마다 QR 카드). 앨범 바탕(남색) 위에 그린다.
 * QR 카드는 visible일 때 200ms 간격으로 떠오른다(LocalMotion이 허용할 때만, 아니면 처음부터 최종 모습). motionKey: 등장 연출을 한 번만 돌리는 열쇠.
 * wide: 넓은 화면은 왼쪽 글·QR, 오른쪽 단골·혜택. 좁으면 위아래로.
 */
@Composable fun AgainPanel(dest: String, links: List<AgainLink>, visit: Int?, wide: Boolean, motionKey: Any, visible: Boolean = true, modifier: Modifier = Modifier) {
    val qrSize = if (wide) 280.dp else 240.dp
    val main: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text("다시 가기 · $dest", fontSize = 24.sp, letterSpacing = 0.12.em, color = AlbumColors.Amber)
            Text("$dest, 다시 갈까요?", Modifier.padding(top = 12.dp), style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold,
                fontSize = 54.sp, lineHeight = 68.sp, color = AlbumColors.Text, lineBreak = KoHeading))
            Text("링크를 폰으로 넘겨요. 예약은 폰에서 해요.", Modifier.padding(top = 10.dp), fontSize = 30.sp, lineHeight = 44.sp, color = AlbumColors.Sub,
                style = TextStyle(lineBreak = KoParagraph))
            Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                links.forEachIndexed { i, l -> LinkCard(l, qrSize, "$motionKey-$i", 150 + i * 200, visible) }
            }
        }
    }
    val side: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (visit != null) {
                Text("단골 단계", fontSize = 24.sp, color = AlbumColors.Sub)
                StageBars(visit)
                // "단골까지 한 번 남았어요" / "세 번째 방문이면 단골이에요" 두 줄로
                againStageLine(visit).split(" · ").forEachIndexed { i, line ->
                    Text(line, fontSize = if (i == 0) 30.sp else 24.sp, lineHeight = if (i == 0) 42.sp else 34.sp,
                        color = if (i == 0) AlbumColors.Text else AlbumColors.Sub, style = TextStyle(lineBreak = KoParagraph))
                }
            }
            Text("다시 가면 쓸 수 있는 혜택", Modifier.padding(top = if (visit != null) 24.dp else 0.dp), fontSize = 24.sp, color = AlbumColors.Sub)
            ExampleBenefits.forEach { BenefitRow(it) }
        }
    }
    if (wide) Row(modifier, horizontalArrangement = Arrangement.spacedBy(64.dp)) {
        main(Modifier.weight(1f))
        side(Modifier.width(560.dp).padding(top = 40.dp))
    } else Column(modifier, verticalArrangement = Arrangement.spacedBy(40.dp)) {
        main(Modifier.fillMaxWidth())
        side(Modifier.fillMaxWidth())
    }
}

@Composable private fun LinkCard(l: AgainLink, qrSize: Dp, key: String, delayMs: Int, visible: Boolean) {
    val p by rememberMotionProgress("again-$key", tween(700, easing = EaseOutCubic), delayMs = delayMs, active = visible)
    val dy = with(LocalDensity.current) { 24.dp.toPx() }
    val (what, where) = l.label.split(" · ", limit = 2).let { it[0] to it.getOrNull(1) }
    Column(Modifier.width(qrSize + 48.dp).graphicsLayer { alpha = p; translationY = (1f - p) * dy }
        .clip(RoundedCornerShape(28.dp)).background(AlbumColors.Surface).border(1.dp, AlbumColors.Line, RoundedCornerShape(28.dp))
        .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        QrCode(l.url, qrSize, "${l.label} QR")
        Text(what, Modifier.padding(top = 18.dp), fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, color = AlbumColors.Text,
            textAlign = TextAlign.Center, style = TextStyle(lineBreak = KoParagraph))
        where?.let { Text(it, fontSize = 24.sp, lineHeight = 34.sp, color = AlbumColors.Sub, textAlign = TextAlign.Center, style = TextStyle(lineBreak = KoParagraph)) }
    }
}

@Composable private fun StageBars(visit: Int) {
    val stage = regularStage(visit)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        (1..3).forEach { i -> Box(Modifier.weight(1f).height(16.dp).background(if (i <= stage) AlbumColors.Amber else AlbumColors.Line, RoundedCornerShape(8.dp))) }
    }
}

@Composable private fun BenefitRow(b: AgainBenefit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.size(76.dp).background(AlbumColors.Amber.copy(alpha = 0.14f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Icon(b.icon, null, Modifier.size(40.dp), tint = ChipIcon)
        }
        Column(Modifier.weight(1f)) {
            Text(b.title, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, color = AlbumColors.Text, style = TextStyle(lineBreak = KoParagraph))
            Text(b.sub, fontSize = 24.sp, lineHeight = 34.sp, color = AlbumColors.Sub, style = TextStyle(lineBreak = KoParagraph))
        }
        Text("예시", Modifier.border(1.dp, AlbumColors.Sub.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = 24.sp, color = AlbumColors.Sub)
    }
}
