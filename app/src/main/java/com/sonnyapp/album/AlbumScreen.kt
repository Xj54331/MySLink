package com.sonnyapp.album

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonnyapp.preview.RegionZoomableImage
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink0
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid
import com.sonnyapp.ui.AlbumThumb
import com.sonnyapp.ui.CellFrame
import com.sonnyapp.util.Dates

/**
 * 相册页：本机已导入照片（媒体库按日期分组）+ 全屏查看器。
 *
 * 交互对齐 DJI MIMO 相册 tab：点缩略图进查看器（可缩放），底部分享 / 删除。
 * 删除走系统回收站确认流（MediaStore.createTrashRequest），结果由
 * ContentObserver 推送回来自动刷新 —— 不依赖 onActivityResult。
 */
@Composable
fun AlbumScreen(vm: AlbumViewModel = viewModel()) {
    val days by vm.days.collectAsState()
    val photos by vm.photos.collectAsState()
    val totalSize by vm.totalSize.collectAsState()
    val busy by vm.busy.collectAsState()
    val context = LocalContext.current

    var viewerIndex by remember { mutableStateOf(-1) }

    Box(Modifier.fillMaxSize().background(Ink0)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // 顶栏
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "相册",
                        color = TextHi, fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Serif,
                    )
                    Text(
                        photos.size.toString() + " 张 · " + humanGb(totalSize) +
                            " · 已同步系统相册",
                        color = TextLo, fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    "刷新",
                    color = Accent, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .clickable { vm.refresh() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            if (busy && photos.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = Accent, strokeWidth = 2.5.dp,
                    )
                }
            } else if (photos.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有导入的照片\n去「设备」页选择照片下载",
                        color = TextLo, fontSize = 12.sp,
                        lineHeight = 20.sp,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (day in days) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h" + day.date) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 6.dp, top = 14.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(day.label, color = TextHi, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium, fontFamily = FontFamily.Serif)
                                Spacer(Modifier.size(8.dp))
                                Text(day.items.size.toString() + " 张",
                                    color = TextLo, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                        items(day.items, key = { "a" + it.id }) { photo ->
                            Box {
                                CellFrame(selected = false, onClick = {
                                    viewerIndex = photos.indexOfFirst { it.id == photo.id }
                                }) {
                                    AlbumThumb(photo.uri, Modifier.fillMaxSize())
                                }
                                if (photo.isPreview) {
                                    Text(
                                        "预览",
                                        color = Color(0xFF171204), fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(5.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(Accent)
                                            .padding(horizontal = 5.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 查看器覆盖层
        if (viewerIndex in photos.indices) {
            AlbumViewer(
                photos = photos,
                startIndex = viewerIndex,
                vm = vm,
                onBack = { viewerIndex = -1 },
                onDeleted = { viewerIndex = -1 },
            )
        }
    }
}

@Composable
private fun AlbumViewer(
    photos: List<AlbumRepository.LocalPhoto>,
    startIndex: Int,
    vm: AlbumViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex) { photos.size }
    val photo = photos.getOrNull(pagerState.currentPage)

    Box(Modifier.fillMaxSize().background(Color(0xF608080A))) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { photos[it].id },
        ) { page ->
            val p = photos[page]
            val path = p.filePath
            if (path != null) {
                RegionZoomableImage(path, Modifier.fillMaxSize())
            } else {
                // 路径不可读（跨用户/权限变化）时退回整图加载
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AlbumThumb(p.uri, Modifier.fillMaxSize())
                }
            }
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
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = TextHi, fontSize = 22.sp)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    photo?.name ?: "",
                    color = TextHi, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    photo?.let {
                        (if (it.width > 0) "${it.width}×${it.height} · " else "") +
                            com.sonnyapp.device.DeviceViewModel.humanSize(it.size)
                    } ?: "",
                    color = TextLo, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                (pagerState.currentPage + 1).toString() + " / " + photos.size,
                color = TextMid, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // 底部操作
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ViewerButton(
                label = "分享",
                onClick = {
                    val intent = vm.shareIntent(listOfNotNull(photo?.uri))
                    if (intent != null) {
                        try {
                            context.startActivity(
                                android.content.Intent.createChooser(intent, "分享到"),
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
            ViewerButton(
                label = "删除",
                danger = true,
                onClick = {
                    val p = photo ?: return@ViewerButton
                    if (vm.requestTrash(p)) {
                        Toast.makeText(context, "已移至回收站", Toast.LENGTH_SHORT).show()
                        onDeleted()
                    } else {
                        Toast.makeText(context, "删除失败：没有权限", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ViewerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (danger) Color(0x1FE2574C) else Ink1)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (danger) com.sonnyapp.theme.ErrorRed else TextHi,
            fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
        )
    }
}

private fun humanGb(bytes: Long): String =
    if (bytes >= 1024L * 1024 * 1024) {
        String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
    } else {
        (bytes / 1048576).toString() + " MB"
    }
