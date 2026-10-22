package com.sonnyapp.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonnyapp.core.dlna.DlnaItem
import com.sonnyapp.core.importer.ImportState
import com.sonnyapp.device.DeviceViewModel
import com.sonnyapp.device.FilterMode
import com.sonnyapp.preview.PreviewScreen
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.ErrorRed
import com.sonnyapp.theme.Ink0
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.Ink2
import com.sonnyapp.theme.Ink3
import com.sonnyapp.theme.OkGreen
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid
import com.sonnyapp.theme.WarnAmber
import kotlinx.coroutines.delay

/**
 * 设备页：相机卡内照片的浏览 / 放大预览入口 / 多选批量导入。
 *
 * 交互对齐 DJI MIMO 的「设备」tab：
 *  - 未连接 -> 连接引导（由 AppRoot 层切换）
 *  - 日期分组网格；✓ = 已在本地相册；角标环 = 正在导入
 *  - 单击进全屏预览（可放大）；「选择」进入多选后底栏批量下载
 *
 * 性能：格子首帧同步取内存缓存（不闪灰块），90ms 防抖把带宽留给停留位置。
 */
@Composable
fun DeviceScreen(
    vm: DeviceViewModel = viewModel(),
    onGotoAlbum: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val groups by vm.groups.collectAsState()
    val allFiles by vm.allFiles.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val filter by vm.filter.collectAsState()
    val selected by vm.selected.collectAsState()
    val downloadedIds by vm.downloadedIds.collectAsState()
    val importProgress by vm.importProgress.collectAsState()
    val context = LocalContext.current

    var selectMode by remember { mutableStateOf(false) }
    var previewItems by remember { mutableStateOf<List<DlnaItem>?>(null) }
    var previewIndex by remember { mutableStateOf(0) }
    var sheetOpen by remember { mutableStateOf(false) }

    val runningCount = importProgress.values.count { !it.isFinished }
    // 仅在「从无到有」时自动弹出下载抽屉：
    // 用户点「后台继续」收起后，排队任务转运行会引起 runningCount 变化，
    // 若不区分升降沿，抽屉会一遍遍自动弹回来（实测过的交互陷阱）。
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(runningCount) {
        val running = runningCount > 0
        if (running && !wasRunning) sheetOpen = true
        wasRunning = running
    }

    Box(Modifier.fillMaxSize().background(Ink0)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {

            // 顶栏
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        vm.deviceName.ifEmpty { "相机" },
                        color = TextHi, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(6.dp).clip(CircleShape)
                                .background(if (busy) WarnAmber else OkGreen),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            status,
                            color = TextLo, fontSize = 10.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    if (selectMode) "完成" else "选择",
                    color = Accent, fontSize = 12.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .clickable {
                            selectMode = !selectMode
                            vm.clearSelection()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "⚙",
                    color = TextMid, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .clickable { onOpenSettings() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            // 筛选 chip 行
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (filter == FilterMode.ORIGINALS_ONLY) "仅 JPEG 原图" else "全部（含 RAW 预览）",
                    color = if (filter == FilterMode.ORIGINALS_ONLY) Color(0xFF171204) else TextMid,
                    fontSize = 10.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (filter == FilterMode.ORIGINALS_ONLY) Accent else Ink1)
                        .clickable {
                            vm.setFilter(
                                if (filter == FilterMode.ORIGINALS_ONLY) FilterMode.ALL
                                else FilterMode.ORIGINALS_ONLY,
                            )
                        }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                )
                Text(
                    "按拍摄日期分组",
                    color = TextLo, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            if (busy && groups.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            color = Accent, strokeWidth = 2.5.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(status, color = TextLo, fontSize = 11.sp)
                    }
                }
            } else if (groups.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (allFiles.isEmpty()) "没有读到照片\n请确认相机停留在「发送到智能手机」界面"
                        else "当前筛选下没有可显示的照片",
                        color = TextLo, fontSize = 12.sp, lineHeight = 20.sp,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    // 底部给操作栏 + Tab 栏留位，否则最后一行点不到
                    contentPadding = PaddingValues(top = 2.dp, bottom = 210.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (g in groups) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h" + g.date) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 6.dp, top = 14.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    g.label,
                                    color = TextHi, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium, fontFamily = FontFamily.Serif,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    g.items.size.toString() + " 张",
                                    color = TextLo, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        items(g.items, key = { "i" + it.id }) { item ->
                            val selOrder = selected.indexOf(item.id) + 1
                            DeviceCell(
                                item = item,
                                vm = vm,
                                selected = selectMode && item.id in selected,
                                selectOrder = selOrder,
                                downloaded = item.id in downloadedIds,
                                importTask = importProgress[item.id],
                                onClick = {
                                    if (selectMode) {
                                        vm.toggle(item.id)
                                    } else {
                                        val list = groups.flatMap { it.items }
                                        previewItems = list
                                        previewIndex = list.indexOfFirst { it.id == item.id }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 底部操作栏（上移避开 Tab 栏，否则会被 NavigationBar 遮住）
        if (selectMode && groups.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(bottom = 84.dp)
                    .background(Ink1).safeDrawingPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val selItems = vm.selectedItems()
                    Text(
                        if (selItems.isEmpty()) "点按选择照片"
                        else "已选 " + selItems.size + " 张 · " +
                            DeviceViewModel.humanSize(
                                selItems.sumOf { it.originalSize.coerceAtLeast(0) },
                            ),
                        color = TextHi, fontSize = 12.5.sp,
                    )
                    if (selItems.any { it.isRaw }) {
                        Text(
                            "RAW 将保存为转码预览（_预览.jpg）",
                            color = WarnAmber, fontSize = 9.5.sp,
                        )
                    }
                }
                Button(
                    onClick = {
                        val n = vm.enqueueSelected()
                        if (n == 0) {
                            Toast.makeText(context, "所选照片均已在相册", Toast.LENGTH_SHORT).show()
                        } else {
                            selectMode = false
                        }
                    },
                    enabled = selected.isNotEmpty(),
                    shape = RoundedCornerShape(13.dp),
                ) {
                    Text(
                        if (selected.isEmpty()) "下载" else "下载 " + selected.size + " 张",
                        fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 悬浮导入胶囊（抽屉收起时）
        if (runningCount > 0 && !sheetOpen) {
            ImportPill(
                done = importProgress.values.count { it.state == ImportState.DONE },
                total = importProgress.size,
                onClick = { sheetOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
            )
        }

        // 预览层
        previewItems?.let { list ->
            if (previewIndex in list.indices) {
                PreviewScreen(
                    items = list,
                    startIndex = previewIndex,
                    vm = vm,
                    onBack = { previewItems = null },
                )
            } else {
                previewItems = null
            }
        }

        // 下载抽屉
        if (sheetOpen) {
            ImportSheet(
                vm = vm,
                onGotoAlbum = {
                    sheetOpen = false
                    onGotoAlbum()
                },
                onDismiss = { sheetOpen = false },
            )
        }
    }
}

/**
 * 网格单元：缩略图三级缓存 + RAW 角标 + 已下载 ✓ + 导入进度环 + 选中态。
 */
@Composable
private fun DeviceCell(
    item: DlnaItem,
    vm: DeviceViewModel,
    selected: Boolean,
    selectOrder: Int,
    downloaded: Boolean,
    importTask: com.sonnyapp.core.importer.ImportTask?,
    onClick: () -> Unit,
) {
    // 首帧同步从内存缓存取 —— 缓存里有图就立刻显示，不会闪灰块
    var bmp by remember(item.id) { mutableStateOf(vm.peekThumb(item.id)) }
    LaunchedEffect(item.id) {
        if (bmp != null) return@LaunchedEffect
        delay(90) // 防抖：快速滑过的格子在生效前就离开了组合
        bmp = vm.loadThumb(item)
    }

    CellFrame(selected = selected, onClick = onClick) {
        val b = bmp
        if (b != null) {
            androidx.compose.foundation.Image(
                bitmap = b.asImageBitmap(),
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }

        // 文件名角标行
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                item.title,
                color = TextHi, fontSize = 7.5.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                if (item.canDownloadOriginal)
                    DeviceViewModel.humanSize(item.originalSize) + " 原图"
                else "RAW 转码预览",
                color = if (item.canDownloadOriginal) Color(0xFF9AE6B4) else WarnAmber,
                fontSize = 7.5.sp, fontFamily = FontFamily.Monospace,
            )
        }

        if (item.isRaw) {
            TagBox("RAW", Modifier.align(Alignment.TopStart))
        }

        if (downloaded) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(19.dp)
                    .background(Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color(0xFF171204), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold)
            }
        }

        // 导入进度环
        val task = importTask
        if (task != null && !task.isFinished) {
            Box(
                Modifier.fillMaxSize().background(Color(0x990C0C0E)),
                contentAlignment = Alignment.Center,
            ) {
                if (task.state == ImportState.RUNNING && task.fraction >= 0f) {
                    CircularProgressIndicator(
                        progress = { task.fraction },
                        modifier = Modifier.size(30.dp),
                        color = Accent,
                        strokeWidth = 3.dp,
                        trackColor = Ink3,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Accent, strokeWidth = 2.5.dp,
                    )
                }
            }
        }

        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(21.dp)
                    .background(Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    selectOrder.toString(),
                    color = Color(0xFF171204), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun TagBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.padding(5.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Accent)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text, color = Color(0xFF171204), fontSize = 8.sp,
            fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
        )
    }
}

/** 悬浮导入进度胶囊。 */
@Composable
private fun ImportPill(
    done: Int,
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Ink2)
            .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = Accent, strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "导入中 $done/$total",
            color = Accent, fontSize = 12.sp,
        )
    }
}

/** SAF 目录选择结果处理（设置页用）。 */
@Composable
fun RememberSaveTreePicker(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                // 有些 provider 不支持持久权限，忽略
            }
            onPicked(uri.toString())
        }
    }
    return { picker.launch(null) }
}
