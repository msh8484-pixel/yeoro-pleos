package ai.yeoro.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * 내려받아 둔 사진 파일(filesDir/photos/…)을 그린다. 이미지 라이브러리 없이 BitmapFactory + 프로세스 LruCache.
 * 사진 파일은 캐시가 아니라 영구 저장이라 오프라인에서도 앨범이 그대로다. 메모리 캐시만 비울 수 있다.
 */

private object PhotoCache : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

/** 긴 변이 maxPx 근처가 되도록 2의 거듭제곱으로 줄여 읽는다. 읽을 수 없으면 null */
internal fun decodeSampled(file: String, maxPx: Int): Bitmap? {
    if (!File(file).isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file, bounds)
    val long = maxOf(bounds.outWidth, bounds.outHeight)
    if (long <= 0) return null
    var sample = 1
    while (long / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeFile(file, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** file이 없거나 아직 읽는 중이거나 읽을 수 없으면 placeholder. 읽기는 Dispatchers.IO에서 */
@Composable fun PhotoImage(file: String?, modifier: Modifier, maxPx: Int, placeholder: @Composable () -> Unit) {
    val key = file?.let { "$it@$maxPx" }
    val bitmap by produceState(key?.let { PhotoCache.get(it) }, key) {
        value = key?.let { PhotoCache.get(it) }   // 같은 자리에 다른 사진이 오면 이전 그림을 남기지 않는다
        if (key == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { decodeSampled(file, maxPx)?.also { PhotoCache.put(key, it) } }
    }
    val b = bitmap
    if (b == null) Box(modifier) { placeholder() }
    else Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
}
