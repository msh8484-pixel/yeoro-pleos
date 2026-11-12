package ai.yeoro.ui.live

import ai.yeoro.engine.Seat
import ai.yeoro.ui.theme.Dusk
import ai.yeoro.ui.theme.DuskDeep
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 좌석 칸의 자리 (100×200 격자, 앞이 위). 한국 차는 운전석이 왼쪽 */
private data class SeatBox(val x: Float, val y: Float, val w: Float, val h: Float)
private val SeatBoxes = mapOf(
    Seat.DRIVER to SeatBox(22f, 66f, 24f, 28f), Seat.PASSENGER to SeatBox(54f, 66f, 24f, 28f),
    Seat.REAR_LEFT to SeatBox(17f, 104f, 21f, 28f), Seat.REAR_CENTER to SeatBox(39.5f, 104f, 21f, 28f), Seat.REAR_RIGHT to SeatBox(62f, 104f, 21f, 28f),
)
private val SeatInk = Color(0xFF1A1306)

/**
 * 위에서 내려다본 차 (Canvas, 정적). 탄 좌석은 앰버로 켜고, 출발 전에 트렁크를 열었으면 트렁크 칸을 앰버로 칠한다.
 * names: 켜진 좌석 안에 쓸 짧은 이름(가족 이름이 있을 때만). 비율 1:2 상자 안에 가운데 맞춤으로 그린다.
 */
@Composable fun CarTopView(seats: Set<Seat>, trunk: Boolean, modifier: Modifier = Modifier, names: Map<Seat, String> = emptyMap(), trunkLabel: String? = null) {
    val ink = MaterialTheme.colorScheme.onSurface
    val body = MaterialTheme.colorScheme.surface
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val s = minOf(maxWidth.value / 100f, maxHeight.value / 200f)   // dp per 격자 칸
        val w = (100f * s).dp; val h = (200f * s).dp
        Box(Modifier.size(w, h)) {
            Canvas(Modifier.fillMaxSize()) { drawCar(seats, trunk, ink, body) }
            // 켜진 좌석의 이름 (짧은 이름만: 칸이 좁다)
            seats.forEach { seat ->
                val b = SeatBoxes.getValue(seat)
                val name = names[seat]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 3 } ?: return@forEach
                Box(Modifier.offset((b.x * s).dp, (b.y * s).dp).size((b.w * s).dp, (b.h * 0.72f * s).dp), contentAlignment = Alignment.Center) {
                    Text(name, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = SeatInk, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
            if (trunk && trunkLabel != null) Box(Modifier.offset((20f * s).dp, (162f * s).dp).size((60f * s).dp, (24f * s).dp), contentAlignment = Alignment.Center) {
                Text(trunkLabel, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = SeatInk, maxLines = 1)
            }
        }
    }
}

private fun DrawScope.drawCar(seats: Set<Seat>, trunk: Boolean, ink: Color, body: Color) {
    val u = size.width / 100f
    fun rr(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color, stroke: Float = 0f, dash: Boolean = false) =
        if (stroke > 0f) drawRoundRect(c, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u),
            style = Stroke(stroke * u, pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(3f * u, 2.4f * u)) else null))
        else drawRoundRect(c, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u))
    fun poly(vararg p: Float, c: Color) = drawPath(Path().apply { moveTo(p[0] * u, p[1] * u); var i = 2; while (i < p.size) { lineTo(p[i] * u, p[i + 1] * u); i += 2 }; close() }, c)

    // 바퀴 (차체 밑에 반쯤 숨는다)
    val tire = ink.copy(alpha = 0.55f)
    listOf(30f, 150f).forEach { y -> rr(3f, y, 8f, 22f, 3f, tire); rr(89f, y, 8f, 22f, 3f, tire) }
    // 사이드미러
    rr(1f, 54f, 9f, 6f, 3f, ink.copy(alpha = 0.35f)); rr(90f, 54f, 9f, 6f, 3f, ink.copy(alpha = 0.35f))
    // 차체: 그림자 → 바탕 → 테두리
    rr(8f, 8f, 84f, 186f, 30f, ink.copy(alpha = 0.06f))
    rr(8f, 6f, 84f, 186f, 30f, body)
    rr(8f, 6f, 84f, 186f, 30f, ink.copy(alpha = 0.28f), stroke = 1.2f)
    // 앞유리·뒷유리·지붕선
    poly(20f, 60f, 80f, 60f, 74f, 40f, 26f, 40f, c = ink.copy(alpha = 0.12f))
    poly(24f, 136f, 76f, 136f, 72f, 150f, 28f, 150f, c = ink.copy(alpha = 0.12f))
    // 전조등
    rr(16f, 10f, 18f, 5f, 2.5f, Dusk.copy(alpha = 0.55f)); rr(66f, 10f, 18f, 5f, 2.5f, Dusk.copy(alpha = 0.55f))
    // 트렁크 칸
    if (trunk) { rr(20f, 160f, 60f, 26f, 8f, Dusk.copy(alpha = 0.85f)); rr(20f, 160f, 60f, 26f, 8f, DuskDeep, stroke = 1f) }
    else rr(20f, 160f, 60f, 26f, 8f, ink.copy(alpha = 0.18f), stroke = 0.8f, dash = true)
    // 좌석: 방석 + 등받이(뒤쪽)
    SeatBoxes.forEach { (seat, b) ->
        val on = seat in seats
        val fill = if (on) Dusk else ink.copy(alpha = 0.07f)
        val back = if (on) DuskDeep else ink.copy(alpha = 0.14f)
        rr(b.x, b.y, b.w, b.h * 0.72f, 5f, fill)
        rr(b.x + 1.5f, b.y + b.h * 0.76f, b.w - 3f, b.h * 0.24f, 3f, back)
    }
}
