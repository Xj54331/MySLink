package com.sonnyapp.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 原图（6000×4000 级）像素级查看器：**只解码可见区域**。
 *
 * ## 为什么不用「整图解码 + 缩放」
 *
 *  24MP JPEG 解成 ARGB_8888 是 96MB —— 直接 OOM 或触发系统查杀；
 *  降采样到 4096 又丢了「放大看对焦」的本钱（用户放大预览的核心诉求之一）。
 *
 * ## 这里的做法
 *
 *  维护「整图虚拟坐标系」：手势只改 scale/offset（纯数值）；
 *  绘制时把当前视口换算成原图坐标矩形，用 BitmapRegionDecoder
 *  **只解码这个矩形**（外扩 1.3 倍做平移余量，按视口像素降采样）。
 *  内存占用 = O(屏幕) 而不是 O(原图)，与分辨率无关。
 *
 *  解码在 IO 线程，按「量化后的视口」去抖：连续拖动时同一视口只解一次，
 *  手势停下来后 1 帧内出清晰图 —— 这正是 Google Photos 放大时的行为。
 *
 *  已知代价：快速拖动时会出现短暂的上一区域拉伸画面（约 20~80ms），
 *  属于可接受的感知代价；比整图解码省一个数量级内存。
 */
@Composable
fun RegionZoomableImage(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    var imgW by remember(filePath) { mutableFloatStateOf(0f) }
    var imgH by remember(filePath) { mutableFloatStateOf(0f) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(filePath) { mutableFloatStateOf(1f) }
    var offset by remember(filePath) { mutableStateOf(Offset.Zero) }
    // 当前解码区域 + 位图（随视口更新）
    var decodedRect by remember(filePath) { mutableStateOf<Rect?>(null) }
    var decodedBmp by remember(filePath) { mutableStateOf<Bitmap?>(null) }

    // 读取图片尺寸
    LaunchedEffect(filePath) {
        val bounds = withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(filePath, o)
                if (o.outWidth > 0) intArrayOf(o.outWidth, o.outHeight) else null
            } catch (e: Exception) {
                null
            }
        }
        if (bounds != null) {
            imgW = bounds[0].toFloat()
            imgH = bounds[1].toFloat()
        }
    }

    // fit 因子（contain）
    val fit = if (imgW > 0 && viewSize.width > 0) {
        min(viewSize.width / imgW, viewSize.height / imgH)
    } else 0f

    fun clampOffset(next: Offset, s: Float): Offset {
        if (fit <= 0f) return Offset.Zero
        val eff = fit * s
        val limX = ((viewSize.width - imgW * eff) / 2f).coerceAtLeast(0f)
        val limY = ((viewSize.height - imgH * eff) / 2f).coerceAtLeast(0f)
        return Offset(next.x.coerceIn(-limX, limX), next.y.coerceIn(-limY, limY))
    }

    // 视口变化 -> 量化 -> 解码（IO）。LaunchedEffect 的 key 即去抖粒度。
    LaunchedEffect(filePath, imgW, viewSize, scale, offset) {
        if (imgW <= 0 || viewSize == IntSize.Zero || fit <= 0f) return@LaunchedEffect
        // 量化：scale 1/4 档、offset 24px 档 —— 手势抖动不触发重复解码
        val sQ = (scale * 4).roundToInt() / 4f
        val offQ = Offset(
            (offset.x / 24f).roundToInt() * 24f,
            (offset.y / 24f).roundToInt() * 24f,
        )
        val eff = fit * sQ
        val vw = viewSize.width / eff
        val vh = viewSize.height / eff
        val cx = imgW / 2f - offQ.x / eff
        val cy = imgH / 2f - offQ.y / eff
        val margin = 1.3f
        val rx = ((cx - vw * margin / 2f).coerceAtLeast(0f)).toInt()
        val ry = ((cy - vh * margin / 2f).coerceAtLeast(0f)).toInt()
        val rw = min((vw * margin).toInt(), imgW.toInt() - rx)
        val rh = min((vh * margin).toInt(), imgH.toInt() - ry)
        if (rw <= 0 || rh <= 0) return@LaunchedEffect
        val target = Rect(rx, ry, rx + rw, ry + rh)

        // 已有同区域（或被包含）的清晰解码就跳过
        decodedRect?.let { cur ->
            if (cur.contains(target) && decodedBmp?.isRecycled == false) return@LaunchedEffect
        }

        // 解码分辨率：目标 = 视口在屏幕上的像素 * 1.2 余量
        val wantPx = max(viewSize.width, viewSize.height) * 1.2f
        val need = max(rw.toFloat(), rh.toFloat())
        var sample = 1
        while (need / (sample * 2) >= wantPx) sample *= 2

        val bmp = withContext(Dispatchers.IO) {
            try {
                var dec: BitmapRegionDecoder? = null
                try {
                    dec = BitmapRegionDecoder.newInstance(File(filePath).absolutePath, false)
                    val o = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    dec?.decodeRegion(target, o)
                } finally {
                    dec?.recycle()
                }
            } catch (e: Exception) {
                null
            }
        }
        if (bmp != null) {
            decodedRect = target
            decodedBmp = bmp
        }
    }

    Box(
        modifier
            .onSizeChanged { viewSize = it }
            .pointerInput(filePath) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val ns = (scale * zoom).coerceIn(1f, 16f)
                    scale = ns
                    offset = clampOffset(offset + pan, ns)
                }
            }
            .pointerInput(filePath) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.5f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 4f
                    }
                })
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (imgW <= 0 || fit <= 0f) return@Canvas
            val bmp = decodedBmp ?: run {
                drawRect(androidx.compose.ui.graphics.Color(0xFF141417))
                return@Canvas
            }
            val rect = decodedRect ?: return@Canvas
            val s = fit * scale
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.translate(size.width / 2f + offset.x, size.height / 2f + offset.y)
                nc.scale(s, s)
                nc.translate(-imgW / 2f, -imgH / 2f)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                nc.drawBitmap(
                    bmp,
                    rect, // src：解码区域（android.graphics.Rect）
                    RectF(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()),
                    paint,
                )
                nc.restore()
            }
        }
    }
}

/** 供测试/调试：视口矩形换算（纯函数，不依赖 Compose）。 */
object ViewportMath {
    fun visibleRect(
        imgW: Float, imgH: Float,
        viewW: Float, viewH: Float,
        scale: Float, offset: Offset,
    ): RectF? {
        if (imgW <= 0 || imgH <= 0 || viewW <= 0 || viewH <= 0) return null
        val fit = min(viewW / imgW, viewH / imgH)
        val eff = fit * scale
        val vw = viewW / eff
        val vh = viewH / eff
        val cx = imgW / 2f - offset.x / eff
        val cy = imgH / 2f - offset.y / eff
        val l = max(0f, cx - vw / 2f)
        val t = max(0f, cy - vh / 2f)
        val r = min(imgW, cx + vw / 2f)
        val b = min(imgH, cy + vh / 2f)
        return RectF(floor(l), floor(t), r, b)
    }
}
