package ai.yeoro.ui.live

import ai.yeoro.EngineBus
import ai.yeoro.ui.theme.Dusk
import ai.yeoro.ui.theme.DuskDeep
import ai.yeoro.ui.theme.Pretendard
import ai.yeoro.ui.theme.duskInk
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 근처 혜택 → 쿠폰 → 예약 (컨셉). 지자체·관광공사·지역 가게와 제휴하면 이 자리에 실제 혜택이 들어온다는 사업 모델을 보여 주는 화면.
 * 지금은 서버도 제휴처도 없는 예시 데이터(가상의 가게 이름)이고, 네트워크를 쓰지 않는다. 화면마다 "제휴 시 제공 · 예시" 꼬리표.
 * 차가 서 있을 때(P단)만 보인다: P를 벗어나면 LiveScreen이 그리지 않고 닫는다.
 */
enum class NearbyPage { LIST, RESERVE, DONE }
data class NearbyUi(val page: NearbyPage, val place: String, val seats: Int, val time: String = "18:30")

object Nearby {
    val ui = MutableStateFlow<NearbyUi?>(null)
    val Times = listOf("18:00", "18:30", "19:00")
    fun open(place: String?, seats: Int) { ui.value = NearbyUi(NearbyPage.LIST, place ?: "이곳", seats.coerceAtLeast(1)); Log.i("YeoroEngine", "nearby open") }
    fun reserve() { ui.value = ui.value?.copy(page = NearbyPage.RESERVE); Log.i("YeoroEngine", "nearby reserve") }
    fun pick(t: String) { if (t in Times) ui.value = ui.value?.copy(time = t) }
    fun request() { ui.value = ui.value?.copy(page = NearbyPage.DONE); Log.i("YeoroEngine", "nearby reserved time=${ui.value?.time} n=${ui.value?.seats}") }
    fun back() { val u = ui.value ?: return; ui.value = if (u.page == NearbyPage.LIST) null else u.copy(page = NearbyPage.LIST) }
    fun close() { ui.value = null }
}

/** 가상의 제휴처 (실제 상호 아님). 거리는 차를 세운 곳에서의 예시 값 */
private const val SHOP = "경포 솔밭식당"
private const val SHOP_LAT = 37.8018; private const val SHOP_LON = 128.9065
private val Sea = listOf(Color(0xFF2F6FB0), Color(0xFF7FB8E0))
private val Warm = listOf(Color(0xFFE0782F), Color(0xFFFFC27A))
private val Pine = listOf(Color(0xFF2E7D5B), Color(0xFF8CCB9B))

@Composable fun NearbyPane(modifier: Modifier) {
    val u by Nearby.ui.collectAsStateWithLifecycle()
    val ui = u ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).clickable { Nearby.back() }, contentAlignment = Alignment.Center) {
                Icon(LiveIcons.Back, "뒤로", Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f)) {
                Text(if (ui.page == NearbyPage.LIST) "근처에서 쓰기" else SHOP, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("${ui.place} · 차를 세운 곳 기준", style = MaterialTheme.typography.bodyMedium, color = muted())
            }
            ExampleTag()
        }
        when (ui.page) {
            NearbyPage.LIST -> BenefitList(ui, Modifier.weight(1f))
            NearbyPage.RESERVE, NearbyPage.DONE -> Reserve(ui, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(LiveIcons.Check, null, Modifier.size(32.dp), tint = duskInk())
            Text("차가 확인한 방문이라 따로 체크인하지 않아요 · 지자체 · 관광공사 · 지역 가게 제휴로 제공", style = MaterialTheme.typography.bodyMedium, color = muted(), maxLines = 1)
        }
    }
}

@Composable private fun ExampleTag() {
    Text("제휴 시 제공 · 예시", Modifier.border(1.dp, muted().copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium, color = muted())
}

@Composable private fun BenefitList(ui: NearbyUi, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        OfferCard(Modifier.weight(1f), Warm, LiveIcons.Ticket, "10,000원", "재방문 가족 관광쿠폰", "관광지 · 전통시장", "1.2km", cta = "쿠폰 받음", done = true)
        OfferCard(Modifier.weight(1f), Sea, LiveIcons.Bowl, "15%", "바다 앞 ${ui.seats}인 세트", SHOP, "350m", cta = "${ui.seats}인 예약", onClick = Nearby::reserve)
        OfferCard(Modifier.weight(1f), Pine, LiveIcons.Coins, "28곳", "지역화폐 가맹점", "반경 1km · 강릉페이", "가까운 곳 120m", cta = "지도 보기")
    }
}

/** 위쪽 색 그라데이션 + 큰 숫자, 아래 이름·거리·버튼. 버튼이 있는 카드만 눌린다 */
@Composable private fun OfferCard(modifier: Modifier, colors: List<Color>, icon: ImageVector, big: String, title: String, sub: String, distance: String,
                                  cta: String, done: Boolean = false, onClick: (() -> Unit)? = null) {
    Surface(modifier.fillMaxHeight().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier), shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(if (onClick != null) 3.dp else 1.dp, if (onClick != null) Dusk else lineColor()), shadowElevation = if (onClick != null) 6.dp else 0.dp) {
        Column(Modifier.fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Brush.linearGradient(colors, Offset.Zero, Offset(900f, 500f)))) {
                Canvas(Modifier.matchParentSize()) {   // 은은한 물결 두 줄 (정적)
                    val w = size.width; val h = size.height
                    for (k in 0..1) drawCircle(Color.White.copy(alpha = 0.10f - k * 0.04f), radius = w * (0.55f + k * 0.25f), center = Offset(w * 1.05f, h * 1.1f))
                }
                Box(Modifier.padding(28.dp).size(72.dp).background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(40.dp), tint = colors[0])
                }
                Text(big, Modifier.align(Alignment.BottomStart).padding(28.dp),
                    style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 72.sp, lineHeight = 76.sp, fontFeatureSettings = "tnum"), color = Color.White)
            }
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = muted(), maxLines = 1)
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LiveIcons.Flag, null, Modifier.size(28.dp), tint = muted())
                    Text(distance, Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.labelLarge, color = muted(), maxLines = 1)
                    val bg = when { onClick != null -> MaterialTheme.colorScheme.onSurface; else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) }
                    val fg = if (onClick != null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
                    Row(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (done) Icon(LiveIcons.Check, null, Modifier.size(28.dp), tint = Color(0xFF3FB27F))
                        Text(cta, style = MaterialTheme.typography.labelLarge, color = fg)
                    }
                }
            }
        }
    }
}

@Composable private fun Reserve(ui: NearbyUi, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        // 왼쪽: 가게 카드
        Column(Modifier.weight(0.9f).fillMaxHeight().clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(Sea, Offset.Zero, Offset(700f, 900f))).padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(88.dp).background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                Icon(LiveIcons.Bowl, null, Modifier.size(48.dp), tint = Sea[0])
            }
            Spacer(Modifier.weight(1f))
            Text("${ui.seats}인 세트 15%", style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 72.sp), color = Color.White)
            Text("$SHOP · 바다 앞 · 350m", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f))
            Text("다시 온 가족에게 · 오늘 저녁까지", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
        }
        Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)) {
            if (ui.page == NearbyPage.RESERVE) {
                // 인원: 차가 확인한 탑승 인원으로 미리 채움
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(28.dp)).padding(32.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Text("${ui.seats}명", style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 70.sp), color = MaterialTheme.colorScheme.onSurface)
                    Column(Modifier.weight(1f)) {
                        Text("차가 확인한 탑승 인원", style = MaterialTheme.typography.titleSmall, color = duskInk())
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(ui.seats) { Icon(LiveIcons.User, null, Modifier.size(36.dp), tint = duskInk()) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Nearby.Times.forEach { t ->
                        val on = t == ui.time
                        Box(Modifier.weight(1f).height(104.dp).clip(RoundedCornerShape(24.dp))
                            .background(if (on) Dusk.copy(alpha = 0.18f) else Color.Transparent)
                            .border(if (on) 3.dp else 1.dp, if (on) DuskDeep else lineColor(), RoundedCornerShape(24.dp)).clickable { Nearby.pick(t) },
                            contentAlignment = Alignment.Center) {
                            Text(t, style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, fontFeatureSettings = "tnum"),
                                color = if (on) DuskDeep else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Button(Nearby::request, Modifier.fillMaxWidth().height(120.dp), shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) {
                    Text("예약 요청", style = MaterialTheme.typography.titleLarge)
                }
            } else Ticket(ui)
        }
    }
}

/** 예약 요청 뒤: 절취선 티켓 + 길안내 */
@Composable private fun Ticket(ui: NearbyUi) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, lineColor(), RoundedCornerShape(32.dp))) {
        Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(LiveIcons.Check, null, Modifier.size(44.dp), tint = Color(0xFF3FB27F))
                Text("예약 요청됨", style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E8F64))
            }
            Text("${ui.time} · ${ui.seats}명", style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 80.sp, lineHeight = 88.sp, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface)
            Text("가게가 확인하면 차 화면으로 알려 드려요", style = MaterialTheme.typography.bodyMedium, color = muted())
        }
        val line = lineColor()
        Canvas(Modifier.fillMaxWidth().height(2.dp)) { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))) }
        Row(Modifier.padding(horizontal = 40.dp, vertical = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Text("쿠폰 적용 · ${ui.seats}인 세트 15%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text("YR-1114-${ui.time.replace(":", "")}", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum", letterSpacing = 2.sp), color = muted())
            }
            Button({ EngineBus.routeRequests.tryEmit(Triple(SHOP, SHOP_LAT, SHOP_LON)) }, Modifier.height(104.dp), shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Dusk, contentColor = Color(0xFF241606))) {
                Icon(LiveIcons.Navi, null, Modifier.size(36.dp)); Spacer(Modifier.width(12.dp)); Text("길안내", style = MaterialTheme.typography.titleSmall)
            }
            Button(Nearby::close, Modifier.height(104.dp), shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), contentColor = MaterialTheme.colorScheme.onSurface)) {
                Text("완료", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
