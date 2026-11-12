package ai.yeoro.ui.live

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import ai.yeoro.data.ShareTokens
import ai.yeoro.service.arrivalUrl
import org.junit.Assert.*
import org.junit.Test

class QrTest {
    private companion object {
        /** 렌더 이미지를 그대로 읽는다(PURE_BARCODE) + TRY_HARDER. 무작위 토큰 때문에 흔들리지 않도록 고정 토큰을 쓴다 */
        val HINTS = mapOf(DecodeHintType.PURE_BARCODE to true, DecodeHintType.TRY_HARDER to true)
        val SEEDED_TOKENS = (1L..20L).map { seed ->
            val b = ByteArray(16).also { java.util.Random(seed).nextBytes(it) }
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
    }

    @Test fun `matrix is square and non-empty`() {
        val m = qrMatrix("https://yeoro.app/u/42")
        assertTrue(m.width >= 21); assertEquals(m.width, m.height)
        assertTrue((0 until m.width).any { x -> (0 until m.height).any { y -> m[x, y] } })
    }
    @Test fun `matrix decodes back to the url`() {
        val url = "https://yeoro.app/u/42"
        val m = qrMatrix(url)
        // 모듈 하나를 8px로, 사방 4모듈 여백을 둔 흑백 이미지로 그려 읽는다
        val s = 8; val q = 4; val w = (m.width + 2 * q) * s
        val px = IntArray(w * w) { i -> val x = i % w / s - q; val y = i / w / s - q
            if (x in 0 until m.width && y in 0 until m.height && m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val r = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, px))), HINTS)
        assertEquals(url, r.text)
    }

    private fun decode(text: String): String {
        val m = qrMatrix(text)
        val s = 8; val q = 4; val w = (m.width + 2 * q) * s
        val px = IntArray(w * w) { i -> val x = i % w / s - q; val y = i / w / s - q
            if (x in 0 until m.width && y in 0 until m.height && m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, px))), HINTS).text
    }

    @Test fun `arrival qr decodes to the upload function with a 128-bit share token`() {
        val tokens = SEEDED_TOKENS + ShareTokens.newToken()
        for (token in tokens) {
            val url = arrivalUrl(token)
            val text = decode(url)
            assertEquals(url, text)
            val m = Regex("""^https://dghpdvpkgvamsaidndqa\.supabase\.co/functions/v1/upload\?t=([A-Za-z0-9_-]{22})$""").matchEntire(text)
            assertNotNull("주소 모양: $text", m)
            assertEquals(16, java.util.Base64.getUrlDecoder().decode(m!!.groupValues[1]).size)
            // 깨끗한 코드: 버전 6(41모듈) 이하
            assertTrue("QR ${qrMatrix(url).width}모듈", qrMatrix(url).width <= 41)
        }
    }

    @Test fun `다시 가기 네이버 지도 QR이 한글 검색 주소 그대로 읽힌다`() {
        val url = ai.yeoro.ui.album.naverMapUrl("강릉 경포해변")
        assertEquals(url, decode(url))
    }
}
