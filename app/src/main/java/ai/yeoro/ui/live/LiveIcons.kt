package ai.yeoro.ui.live

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 시안(album-prototype `const I`)의 선 아이콘. 24 뷰박스, 선 굵기 1.8, 둥근 끝. 색은 Icon의 tint로 칠한다.
 * SVG circle·rect는 같은 모양의 path로 옮겼다.
 */
private fun stroke(name: String, vararg d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    d.forEach { addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
}.build()

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

object LiveIcons {
    val Seat = stroke("seat", "M7 3h6a2 2 0 0 1 2 2v7H9a2 2 0 0 1-2-2zM5 13h12l2 6H7z")
    val Stop = stroke("stop", "M6 3v18M6 4h11l-2 4 2 4H6")
    val Rain = stroke("rain", "M6 14a4 4 0 0 1 .5-8A5 5 0 0 1 16 7a3.5 3.5 0 0 1 1 7z", "M8 17l-1 3M12 17l-1 3M16 17l-1 3")
    val Sun = stroke("sun", circle(12f, 12f, 4f), "M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5 19 19M5 19l1.5-1.5M17.5 6.5 19 5")
    val Window = stroke("window", "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M4 11h16M12 4v16")
    val Flag = stroke("flag", "M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11z", circle(12f, 10f, 2.5f))
    val Trunk = stroke("trunk", "M4 14h16v5H4zM6 14l2-7h8l2 7", "M9 17h6")
    val Clock = stroke("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Mic = stroke("mic", "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0M12 18v3")
    val User = stroke("user", circle(12f, 8f, 4f), "M4 21a8 8 0 0 1 16 0")
    val Ticket = stroke("ticket", "M3 8a2 2 0 0 0 0 4v4h18v-4a2 2 0 0 0 0-4V4H3z", "M13 5v2M13 9v2M13 13v2")
    val Photo = stroke("photo", "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", circle(8.5f, 9.5f, 1.5f), "M21 15l-5-5L5 19")
    val Check = stroke("check", circle(12f, 12f, 9f), "M8 12.5l2.6 2.6L16 9.5")
    val Bowl = stroke("bowl", "M3 11h18a9 9 0 0 1-18 0z", "M8 7c0-1.5 1-2 1-3.5M12 7c0-1.5 1-2 1-3.5M16 7c0-1.5 1-2 1-3.5", "M7 20h10")
    val Coins = stroke("coins", circle(9f, 9f, 6f), circle(15f, 15f, 6f), "M9 6.5v5M7 9h4")
    val Back = stroke("back", "M15 5l-7 7 7 7")
    val Navi = stroke("navi", "M12 2l8 19-8-4-8 4z")
    val Gift = stroke("gift", "M4 9h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1z", "M3 13h18M12 9v12M12 9c-2-4-6-4-6-1s6 1 6 1zm0 0c2-4 6-4 6-1s-6 1-6 1z")

    fun of(i: MomentIcon): ImageVector = when (i) {
        MomentIcon.RAIN -> Rain; MomentIcon.SUN -> Sun; MomentIcon.WINDOW -> Window; MomentIcon.STOP -> Stop; MomentIcon.USER -> User
    }
}
