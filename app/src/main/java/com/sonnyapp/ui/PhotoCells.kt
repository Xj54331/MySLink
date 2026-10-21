package com.sonnyapp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink2
import com.sonnyapp.theme.Ink3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网格单元格框架：设备页与相册页共用同一套外观（12dp 圆角、选中描边）。
 */
@Composable
fun CellFrame(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Ink2)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Accent else Ink3,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        content = content,
    )
}

/**
 * 相册缩略图：从 MediaStore content uri 解码。
 *
 * 与设备页同款策略：先探尺寸再 inSampleSize 降采样 + RGB_565；
 * 内存 LRU 16MB 挂在伴生对象上（跨页面滚动复用，App 生命周期）。
 */
@Composable
fun AlbumThumb(uri: Uri, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        thumbCache.get(uri.toString())?.let { bmp = it; return@LaunchedEffect }
        val decoded = withContext(Dispatchers.IO) { decodeThumb(resolver, uri) }
        if (decoded != null) {
            thumbCache.put(uri.toString(), decoded)
            bmp = decoded
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.fillMaxSize().background(Ink3))
    }
}

private val thumbCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

private fun decodeThumb(resolver: android.content.ContentResolver, uri: Uri): Bitmap? = try {
    // MediaStore 的缩略图接口在新版本上返回值不可靠，直接按原图降采样最稳。
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, 320)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
} catch (e: Exception) {
    null
}

private fun sampleFor(w: Int, h: Int, target: Int): Int {
    if (w <= 0 || h <= 0) return 1
    var s = 1
    var ww = w
    var hh = h
    while (ww / 2 >= target && hh / 2 >= target) {
        ww /= 2
        hh /= 2
        s *= 2
    }
    return s
}
