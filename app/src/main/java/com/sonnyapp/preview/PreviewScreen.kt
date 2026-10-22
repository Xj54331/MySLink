package com.sonnyapp.preview

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonnyapp.core.dlna.DlnaItem
import com.sonnyapp.core.importer.ImportState
import com.sonnyapp.device.DeviceViewModel
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink0
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid
import com.sonnyapp.theme.WarnAmber

/**
 * 全屏预览页（未下载状态的放大查看 + 一键导入）。
 *
 * ## 三级加载
 *
 *  1. TN：网格缩略图直接带过来当占位（内存缓存里有就瞬时显示）
 *  2. LRG：进入页面自动加载大预览（a6300 实测 1616×1080），双指缩放到 8x
 *  3. ORG：点「加载原图」后流式拉取完整 JPEG 落盘，切换到区域解码视图 ——
 *     这时是**原生分辨率像素级**查看，且内存占用与屏幕同阶而不是原图大小
 *
 * RAW(.ARW) 只有 LRG（相机不提供 ORG，请求返回 406），UI 明确说明。
 *
 * 左右滑动切换同组照片；底部按钮随任务状态变化：下载 / 导入中 / 已在相册。
 */
@Composable
fun PreviewScreen(
    items: List<DlnaItem>,
    startIndex: Int,
    vm: DeviceViewModel,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
    ) { items.size }

    Box(Modifier.fillMaxSize().background(Ink0)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { items[it].id },
        ) { page ->
            PreviewPage(
                item = items[page],
                vm = vm,
                thumb = { vm.peekThumb(items[page].id) },
            )
        }

        // 顶栏
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0x66000000))
                    .clickableNoIndication(onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = TextHi, fontSize = 22.sp)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                val item = items.getOrNull(pagerState.currentPage)
                Text(
                    item?.title ?: "",
                    color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        item == null -> ""
                        item.isRaw -> "RAW · 仅转码预览"
                        else -> "JPEG 原图可下载"
                    },
                    color = TextLo, fontSize = 10.sp,
                )
            }
            Text(
                (pagerState.currentPage + 1).toString() + " / " + items.size,
                color = TextMid, fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PreviewPage(
    item: DlnaItem,
    vm: DeviceViewModel,
    thumb: () -> Bitmap?,
) {
    val loader = vm.previewLoader
    var lrg by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var fullFile by remember(item.id) { mutableStateOf<String?>(null) }
    var fullBytes by remember(item.id) { mutableLongStateOf(0L) }
    var fullLoading by remember(item.id) { mutableStateOf(false) }
    var fullFailed by remember(item.id) { mutableStateOf(false) }

    val importTasks by vm.imports.tasks.collectAsState()
    val downloadedIds by vm.downloadedIds.collectAsState()
    val task = importTasks.firstOrNull { it.id == item.id && !it.isFinished }
    val isDownloaded = item.id in downloadedIds

    // LRG 自动加载
    LaunchedEffect(item.id) {
        if (lrg == null) lrg = loader.loadLarge(item)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val file = fullFile
        when {
            file != null -> RegionZoomableImage(file, Modifier.fillMaxSize())
            lrg != null -> ZoomableImage(lrg!!, Modifier.fillMaxSize())
            else -> {
                // 占位：网格缩略图（内存缓存里有就立即显示，不闪黑）
                val t = thumb()
                if (t != null) {
                    ZoomableImage(t, Modifier.fillMaxSize())
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Accent, strokeWidth = 2.5.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("加载预览…", color = TextLo, fontSize = 11.sp)
                    }
                }
            }
        }

        // 顶部加载态说明（原图渐进）
        if (fullLoading && item.originalSize > 0) {
            val pct = (fullBytes * 100 / item.originalSize).toInt().coerceIn(0, 99)
            Text(
                "加载原图 $pct%",
                color = Color(0xCCF2EFE9), fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (fullFailed) {
            Text(
                "原图加载失败（连接可能已断开）",
                color = WarnAmber, fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // 底部操作区
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xE608080A)),
                    ),
                )
                .safeDrawingPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        item.title,
                        color = TextHi, fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    Text(
                        buildString {
                            append(if (item.downloadIsOriginal) "原图 " else "转码预览 ")
                            append(DeviceViewModel.humanSize(
                                if (item.originalSize > 0) item.originalSize
                                else item.resources.firstOrNull { it.isPreviewLarge }?.sizeBytes ?: 0L,
                            ))
                        },
                        color = TextLo, fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
                if (fullFile == null && !item.isRaw && !fullLoading) {
                    Text(
                        "加载原图查看细节",
                        color = Accent, fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x1FE8A33D))
                            .clickableNoIndication {
                                fullLoading = true
                                fullFailed = false
                                vm.fetchOriginal(item,
                                    onProgress = { fullBytes = it },
                                    onDone = { f ->
                                        fullLoading = false
                                        if (f != null) fullFile = f else fullFailed = true
                                    },
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 主操作按钮：下载 / 导入中 / 已在相册
            when {
                task != null -> {
                    Button(
                        onClick = { }, enabled = false,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        val pct = if (task.fraction >= 0f) {
                            (task.fraction * 100).toInt().toString() + "%"
                        } else "…"
                        Text(
                            "导入中 $pct",
                            color = Color(0xFF171204), fontSize = 14.sp,
                        )
                    }
                }
                isDownloaded -> {
                    Button(
                        onClick = { }, enabled = false,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Text("✓ 已在相册", color = Accent, fontSize = 14.sp)
                    }
                }
                else -> {
                    Button(
                        onClick = { vm.enqueueOne(item) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Text(
                            if (item.isRaw) "下载转码预览" else "下载原图",
                            color = Color(0xFF171204), fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (item.isRaw) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "RAW 无法通过 Wi-Fi 传输原文件（相机只提供 JPEG 转码预览，请求 .ARW 原图返回 HTTP 406）",
                            color = WarnAmber.copy(alpha = 0.8f), fontSize = 9.5.sp,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 无水波纹的点击（预览画面上的控件，水波纹会污染画面）。
 *
 * 写成 @Composable 扩展函数而不是 Modifier.composed ——
 * `composed` 在新版 Compose（1.10+）里已从 runtime 移除，且会引入重组开销。
 */
@Composable
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return this.clickable(
        interactionSource = source,
        indication = null,
        onClick = onClick,
    )
}
