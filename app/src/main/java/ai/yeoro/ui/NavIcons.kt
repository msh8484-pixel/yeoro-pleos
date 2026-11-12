package ai.yeoro.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun icon(name: String, block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit) =
    ImageVector.Builder(name, 40.dp, 40.dp, 24f, 24f).apply(block).build()

private fun ImageVector.Builder.fill(d: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    path(fill = SolidColor(Color.Black), pathBuilder = d)

/** 지금: 나침반 바늘이 있는 원 */
val IconLive = icon("live") { fill {
    moveTo(12f, 2f); curveTo(6.5f, 2f, 2f, 6.5f, 2f, 12f); curveTo(2f, 17.5f, 6.5f, 22f, 12f, 22f)
    curveTo(17.5f, 22f, 22f, 17.5f, 22f, 12f); curveTo(22f, 6.5f, 17.5f, 2f, 12f, 2f); close()
    moveTo(16.5f, 7.5f); lineTo(14f, 14f); lineTo(7.5f, 16.5f); lineTo(10f, 10f); close()
} }
/** 우리 가족 여행: 집 */
val IconHome = icon("home") { fill {
    moveTo(12f, 3f); lineTo(2f, 12f); lineTo(5f, 12f); lineTo(5f, 20f); lineTo(10f, 20f); lineTo(10f, 14f)
    lineTo(14f, 14f); lineTo(14f, 20f); lineTo(19f, 20f); lineTo(19f, 12f); lineTo(22f, 12f); close()
} }
/** 앨범: 사진 한 장(산과 해) */
val IconAlbum = icon("album") { fill {
    moveTo(4f, 4f); lineTo(20f, 4f); curveTo(21.1f, 4f, 22f, 4.9f, 22f, 6f); lineTo(22f, 18f)
    curveTo(22f, 19.1f, 21.1f, 20f, 20f, 20f); lineTo(4f, 20f); curveTo(2.9f, 20f, 2f, 19.1f, 2f, 18f)
    lineTo(2f, 6f); curveTo(2f, 4.9f, 2.9f, 4f, 4f, 4f); close()
    moveTo(4f, 18f); lineTo(9f, 11f); lineTo(13f, 16f); lineTo(15.5f, 13f); lineTo(20f, 18f); close()
} }
/** 설정: 슬라이더 세 줄 */
val IconSettings = icon("settings") { fill {
    moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 7f); lineTo(3f, 7f); close()
    moveTo(3f, 11f); lineTo(21f, 11f); lineTo(21f, 13f); lineTo(3f, 13f); close()
    moveTo(3f, 17f); lineTo(21f, 17f); lineTo(21f, 19f); lineTo(3f, 19f); close()
    moveTo(15f, 3f); lineTo(18f, 3f); lineTo(18f, 9f); lineTo(15f, 9f); close()
    moveTo(6f, 9f); lineTo(9f, 9f); lineTo(9f, 15f); lineTo(6f, 15f); close()
    moveTo(12f, 15f); lineTo(15f, 15f); lineTo(15f, 21f); lineTo(12f, 21f); close()
} }
