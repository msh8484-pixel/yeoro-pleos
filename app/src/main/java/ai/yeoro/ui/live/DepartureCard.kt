package ai.yeoro.ui.live

import ai.yeoro.PendingAsk
import ai.yeoro.engine.Seat
import ai.yeoro.service.askText
import ai.yeoro.service.askWhere
import ai.yeoro.ui.theme.Dusk
import ai.yeoro.ui.theme.duskInk
import ai.yeoro.ux.rememberMotionProgress
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val CardShape = RoundedCornerShape(32.dp)
private const val SeatStepMs = 360

@Composable internal fun lineColor() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
@Composable internal fun muted() = MaterialTheme.colorScheme.onSurfaceVariant

/**
 * 출발 전 질문 / 소급 질문 카드. 답은 onAnswer(수락 여부) 하나로만 나간다(EngineBus.answer 경로).
 * listening: 음성으로 답을 듣는 중(마이크 열림)이면 버튼 위에 정적인 "듣고 있어요" 줄을 보인다(모션 없음).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun AskCard(ask: PendingAsk, bufferedMoments: Int, names: Map<Seat, String>, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier, listening: Boolean = false) {
    val t = askText(ask)
    val retro = ask.kind == "retro"
    // 들어오는 연출: 모션 허용(P단) 때만, 질문마다 한 번
    val enter by rememberMotionProgress("ask-${ask.askedMs}", 450)
    Surface(
        modifier.widthIn(max = if (retro) 1080.dp else 1480.dp).fillMaxWidth().graphicsLayer { alpha = enter; translationY = (1f - enter) * -48.dp.toPx() },
        shape = CardShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 16.dp, border = BorderStroke(1.dp, lineColor()),
    ) {
        val content: @Composable ColumnScope.() -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AppMark(32.dp)
                Text(if (retro) "기록해 둔 여행" else "출발 전 제안", style = MaterialTheme.typography.labelLarge, color = duskInk())
            }
            Text(t.title, style = if (retro) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            if (retro) {
                Text(t.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (bufferedMoments > 0) Text("지금까지 남긴 순간 ${bufferedMoments}개", style = MaterialTheme.typography.bodyMedium, color = muted())
            } else {
                Destination(ask)
                val facts = askFacts(ask)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    facts.forEach { (icon, label) -> FactTile(icon, label) }
                }
            }
            if (listening) ListeningLine()
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                val shape = RoundedCornerShape(24.dp)
                Button({ onAnswer(false) }, Modifier.weight(1f).heightIn(min = 104.dp), shape = shape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Text(t.no, style = MaterialTheme.typography.titleMedium)
                }
                Button({ onAnswer(true) }, Modifier.weight(1f).heightIn(min = 104.dp), shape = shape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                    Text(t.yes, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (retro) Column(Modifier.padding(horizontal = 44.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
        else Row(Modifier.padding(horizontal = 48.dp, vertical = 44.dp), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
            // 차가 알아챈 것: 탄 좌석과 트렁크를 그림으로
            Box(Modifier.size(330.dp, 640.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f), RoundedCornerShape(28.dp)).padding(vertical = 28.dp, horizontal = 24.dp)) {
                CarTopView(ask.seats, trunk = ask.signals.any { "트렁크" in it }, Modifier.fillMaxSize(), names = names, trunkLabel = "트렁크")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(30.dp), content = content)
        }
    }
}

/** 목적지 한 줄: 핀 + 이름 + 큰 거리 */
@Composable private fun Destination(ask: PendingAsk) {
    val dest = ask.destination?.trim()?.takeIf { it.isNotEmpty() }
    val km = ask.distanceKm?.let { Math.round(it) }
    if (dest == null && km == null) return
    Row(Modifier.fillMaxWidth().background(Dusk.copy(alpha = 0.12f), RoundedCornerShape(28.dp)).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Icon(LiveIcons.Flag, null, Modifier.size(56.dp), tint = duskInk())
        Text(dest ?: "목적지", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (km != null) Row(verticalAlignment = Alignment.Bottom) {
            Text("$km", style = androidx.compose.ui.text.TextStyle(fontFamily = ai.yeoro.ui.theme.Pretendard, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = androidx.compose.ui.unit.TextUnit(72f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(80f, androidx.compose.ui.unit.TextUnitType.Sp), fontFeatureSettings = "tnum"), color = duskInk())
            Text("km", Modifier.padding(start = 6.dp, bottom = 12.dp), style = MaterialTheme.typography.titleMedium, color = duskInk())
        }
    }
}

/** 큰 아이콘 + 짧은 말 한 칸 */
@Composable private fun FactTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(Modifier.border(1.dp, lineColor(), RoundedCornerShape(24.dp)).padding(start = 14.dp, end = 26.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(60.dp).background(Dusk.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(34.dp), tint = duskInk())
        }
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * 출발 제안의 판단 근거를 아이콘 칸으로: 탄 사람 수, 트렁크, 그 밖의 신호(관광·숙박지, 주말 …).
 * 목적지 거리는 목적지 줄에, 뒷좌석 인원은 사람 수에 이미 들어 있어 뺀다.
 */
internal fun askFacts(ask: PendingAsk): List<Pair<androidx.compose.ui.graphics.vector.ImageVector, String>> {
    val out = mutableListOf<Pair<androidx.compose.ui.graphics.vector.ImageVector, String>>()
    if (ask.seats.isNotEmpty()) out += LiveIcons.User to "${ask.seats.size}명 탑승"
    ask.signals.firstOrNull { "트렁크" in it }?.let { s -> out += LiveIcons.Trunk to ("트렁크 " + (Regex("\\d+회").find(s)?.value ?: "열림")) }
    ask.signals.filter { "트렁크" !in it && "뒷좌석" !in it && !Regex("^목적지 \\d+km$").matches(it) }.take(3).forEach { s ->
        val label = s.removePrefix("목적지 ")
        out += (if ("주말" in s || "휴일" in s) LiveIcons.Clock else LiveIcons.Flag) to label
    }
    return out
}

/** 마이크가 열려 있는 동안만 보이는 한 줄 (정적: 깜빡임·파형 없음) */
@Composable private fun ListeningLine() {
    Row(Modifier.fillMaxWidth().background(Dusk.copy(alpha = 0.14f), RoundedCornerShape(24.dp)).padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(LiveIcons.Mic, null, Modifier.size(40.dp), tint = duskInk())
        Text("듣고 있어요 · ‘응’ 또는 ‘아니’", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
    }
}

private val SeatOrder = listOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_CENTER, Seat.REAR_RIGHT)
private fun orderedSeats(s: Set<Seat>) = SeatOrder.filter { it in s }

@Composable private fun SeatChip(s: Seat, names: Map<Seat, String>) {
    Row(Modifier.border(1.dp, lineColor(), RoundedCornerShape(50)).padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).background(Dusk, CircleShape), contentAlignment = Alignment.Center) {
            Icon(LiveIcons.Seat, null, Modifier.size(28.dp), tint = Color(0xFF1A1306))
        }
        Text(personName(s, names), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 여로 앱 표시: 설정 화면과 같은 YeoroMark (핀 포함) */
@Composable internal fun AppMark(size: androidx.compose.ui.unit.Dp) = ai.yeoro.ui.settings.YeoroMark(size)

/**
 * 기록 시작 장면 (출발 질문에 수락한 뒤, 아직 P단). 좌석이 360ms 간격으로 하나씩 켜지고 미니 경로가 그려진다.
 * 모든 움직임은 rememberMotionProgress를 거치므로 주행 제한 중이면 처음부터 다 켜진 정적 화면이다.
 */
@Composable fun StartScene(p: PendingAsk, wide: Boolean, names: Map<Seat, String>, modifier: Modifier = Modifier) {
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("오늘의 여행을\n기억하기 시작했어요", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            val where = askWhere(p)
            if (where.isNotEmpty()) Text(where, style = MaterialTheme.typography.titleMedium, color = muted())
        }
    }
    val calm: @Composable (Modifier) -> Unit = { m ->
        Row(m.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp)).border(1.dp, lineColor(), RoundedCornerShape(28.dp)).padding(32.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Icon(LiveIcons.Flag, null, Modifier.size(48.dp), tint = duskInk())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("이제 출발하세요", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("달리는 동안에는 조용히 기록만 할게요.", style = MaterialTheme.typography.bodyMedium, color = muted())
            }
        }
    }
    if (wide) Column(modifier, verticalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterVertically)) {
        Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(40.dp)) {
                text(Modifier)
                SeatMap(p, names, Modifier.widthIn(max = 820.dp))
            }
            MiniRoute(p.askedMs, Modifier.width(560.dp).height(340.dp))
        }
        calm(Modifier.fillMaxWidth())
    } else Column(modifier, verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Box(Modifier.fillMaxWidth()) {
            text(Modifier.padding(end = 220.dp))
            MiniRoute(p.askedMs, Modifier.align(Alignment.TopEnd).size(200.dp, 140.dp))
        }
        SeatMap(p, names, Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        calm(Modifier.fillMaxWidth())
    }
}

/** 좌석 지도: 앞 두 칸 + 뒤 두 칸(가운데에 사람이 있으면 세 칸). 탄 좌석만 순서대로 켜진다. */
@Composable private fun SeatMap(p: PendingAsk, names: Map<Seat, String>, modifier: Modifier) {
    val rows = listOf(listOf(Seat.DRIVER, Seat.PASSENGER),
        if (Seat.REAR_CENTER in p.seats) listOf(Seat.REAR_LEFT, Seat.REAR_CENTER, Seat.REAR_RIGHT) else listOf(Seat.REAR_LEFT, Seat.REAR_RIGHT))
    val lit = orderedSeats(p.seats)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { s -> SeatCell(s, names[s], lit.indexOf(s), p.askedMs, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable private fun SeatCell(s: Seat, name: String?, order: Int, key: Long, modifier: Modifier) {
    val on = order >= 0
    val prog by if (on) rememberMotionProgress("seat-$key-$order", 500, delayMs = SeatStepMs * (order + 1)) else remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val ink = MaterialTheme.colorScheme.onSurface
    Row(modifier.heightIn(min = 104.dp)
        .background(lerp(off, Dusk.copy(alpha = 0.14f), prog), RoundedCornerShape(24.dp))
        .border(if (prog > 0f) 2.dp else 1.dp, lerp(lineColor(), Dusk.copy(alpha = 0.7f), prog), RoundedCornerShape(24.dp))
        .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(56.dp).background(lerp(ink.copy(alpha = 0.08f), Dusk, prog), CircleShape), contentAlignment = Alignment.Center) {
            Icon(LiveIcons.Seat, null, Modifier.size(32.dp), tint = lerp(muted().copy(alpha = 0.6f), Color(0xFF1A1306), prog))
        }
        // 좁은 칸에서도 줄바꿈되지 않게 줄 이름과 자리를 두 줄로
        val (rowName, place) = when (s) {
            Seat.DRIVER -> "앞줄" to "운전석"; Seat.PASSENGER -> "앞줄" to "조수석"
            Seat.REAR_LEFT -> "뒷줄" to "왼쪽"; Seat.REAR_CENTER -> "뒷줄" to "가운데"; Seat.REAR_RIGHT -> "뒷줄" to "오른쪽"
        }
        // 가족 이름이 있으면 윗줄에 자리("앞줄 운전석"), 아랫줄에 이름
        Column {
            Text(if (name != null) "$rowName $place" else rowName, style = MaterialTheme.typography.labelMedium, color = muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(name ?: place, style = MaterialTheme.typography.titleSmall, color = lerp(muted(), ink, prog), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// 시안의 경로 곡선(viewBox 100 150 800 350)
private const val RoutePath = "M150 400 C 230 430, 300 380, 380 396 S 470 392, 470 392 C 560 392, 600 360, 660 330 S 760 300, 838 268"

/** 미니 경로: 선이 처음부터 끝까지 그려진 뒤 양 끝 점이 나타난다. 모션이 꺼져 있으면 다 그려진 상태. */
@Composable private fun MiniRoute(key: Long, modifier: Modifier) {
    val prog by rememberMotionProgress("route-$key", 1400, delayMs = 200)
    val path = remember { PathParser().parsePathString(RoutePath).toPath() }
    val measure = remember(path) { PathMeasure().apply { setPath(path, false) } }
    val ink = duskInk()
    Box(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(28.dp)).border(1.dp, lineColor(), RoundedCornerShape(28.dp)).padding(24.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val s = minOf(size.width / 800f, size.height / 350f)
            translate((size.width - 800f * s) / 2f, (size.height - 350f * s) / 2f) {
                scale(s, s, pivot = Offset.Zero) {
                    translate(-100f, -150f) {
                        val seg = Path(); measure.getSegment(0f, measure.length * prog, seg, true)
                        drawPath(path, ink.copy(alpha = 0.12f), style = Stroke(10f, cap = StrokeCap.Round))
                        drawPath(seg, ink, style = Stroke(10f, cap = StrokeCap.Round))
                        drawCircle(ink, 16f, Offset(150f, 400f))
                        if (prog >= 1f) { drawCircle(ink, 22f, Offset(838f, 268f)); drawCircle(Color.White, 9f, Offset(838f, 268f)) }
                    }
                }
            }
        }
    }
}
