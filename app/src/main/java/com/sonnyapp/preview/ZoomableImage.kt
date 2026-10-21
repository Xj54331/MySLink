package com.sonnyapp.preview

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * 位图捏合缩放视图（LRG 预览用）。
 *
 *  - 双指捏合 1x~8x，带平移；缩放回 1x 时偏移自动归零
 *  - 双击在 1x / 2.5x 间切换（带 180ms 过渡，视觉上是「聚焦」而不是跳变）
 *  - 平移被夹紧在图片边界内，不会把图拖出屏幕找不到
 *
 * 实现：graphicsLayer 三个标量（scale + translation），手势走
 * rememberTransformableState —— 全部在重组外的快路径上更新，120Hz 无压力。
 */
@Composable
fun ZoomableImage(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 8f,
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // 双击动画
    var targetScale by remember(bitmap) { mutableFloatStateOf(1f) }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(180),
        label = "dblclick-scale",
    )

    fun clampOffset(next: Offset, s: Float): Offset {
        if (viewSize == IntSize.Zero) return Offset.Zero
        val limX = (viewSize.width * (s - 1) / 2f).coerceAtLeast(0f)
        val limY = (viewSize.height * (s - 1) / 2f).coerceAtLeast(0f)
        return Offset(next.x.coerceIn(-limX, limX), next.y.coerceIn(-limY, limY))
    }

    Box(
        modifier
            .onSizeChanged { viewSize = it }
            .pointerInput(bitmap) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    targetScale = -1f // 手势接管时取消双击动画目标
                    val ns = (scale * zoom).coerceIn(minScale, maxScale)
                    scale = ns
                    offset = clampOffset(offset + pan, ns)
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1.5f) {
                        targetScale = 1f
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        targetScale = 2.5f
                        scale = animatedScale
                        // 以双击点为焦点放大：把该点移到屏幕中心
                        val cx = viewSize.width / 2f
                        val cy = viewSize.height / 2f
                        offset = clampOffset(
                            Offset((cx - tap.x) * 2.5f, (cy - tap.y) * 2.5f),
                            2.5f,
                        )
                    }
                })
            }
            .graphicsLayer {
                // 手势路径直接用 scale；双击路径用动画值
                val s = if (targetScale >= 0f) animatedScale else scale
                scaleX = s
                scaleY = s
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
