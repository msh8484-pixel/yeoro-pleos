package ai.yeoro.ui.live

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * text를 여백 없는 QR 모듈 행렬(모듈 1개 = 1칸)로. 여백은 화면 쪽 흰 카드가 맡는다.
 * 주소는 모두 ASCII(한글은 %인코딩)라 문자 집합 표시(ECI)를 넣지 않는다. ECI를 제대로 읽지 못하는 스캐너가 있다. ASCII가 아닐 때만 UTF-8.
 */
fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
    buildMap {
        put(EncodeHintType.MARGIN, 0); put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        if (text.any { it.code > 0x7E || it.code < 0x20 }) put(EncodeHintType.CHARACTER_SET, "UTF-8")
    })

/** 모듈 1개 = 1px 비트맵. 그릴 때 FilterQuality.None으로 키워 또렷하게 보인다. */
fun qrImage(text: String): ImageBitmap {
    val m = qrMatrix(text)
    val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF111111.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** QR 사방의 여백(모듈 수). QR 규격의 최소값 */
const val QR_QUIET_MODULES = 4

/**
 * 흰 둥근 카드 안의 QR (사진 올리기·다시 가기 공용). 모듈 한 칸을 정수 픽셀로 키워(남는 폭은 가운데 여백) 칸 너비가 고르게 보이고,
 * 칸 크기를 모듈 수 + 사방 4모듈로 정해 여백(quiet zone)이 늘 4모듈 이상이다. 정적(움직임 없음).
 */
@Composable fun QrCode(text: String, size: Dp, description: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { qrImage(text) }
    Box(modifier.size(size).background(Color.White, RoundedCornerShape(24.dp)).padding(8.dp)) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
            val cell = (minOf(this.size.width, this.size.height) / (qr.width + 2 * QR_QUIET_MODULES)).toInt().coerceAtLeast(1)
            val side = cell * qr.width
            drawImage(qr, dstOffset = IntOffset(((this.size.width - side) / 2).toInt(), ((this.size.height - side) / 2).toInt()),
                dstSize = IntSize(side, side), filterQuality = FilterQuality.None)
        }
    }
}
