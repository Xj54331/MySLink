package com.sonnyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonnyapp.core.importer.ImportState
import com.sonnyapp.core.importer.ImportTask
import com.sonnyapp.device.DeviceViewModel
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.ErrorRed
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.Ink2
import com.sonnyapp.theme.Ink3
import com.sonnyapp.theme.OkGreen
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid

/**
 * 导入进度底部抽屉（对齐 MIMO 的批量下载面板）。
 *
 *  - 逐文件进度条 + 汇总字节数 + 实时速率
 *  - 「后台继续」收起抽屉，队列继续跑（悬浮胶囊可再唤起）
 *  - 全部完成后给「查看相册」直达入口
 */
@Composable
fun ImportSheet(
    vm: DeviceViewModel,
    onGotoAlbum: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tasks by vm.imports.tasks.collectAsState()
    if (tasks.isEmpty()) return

    val doneCount = tasks.count { it.state == ImportState.DONE }
    val failedCount = tasks.count { it.state == ImportState.FAILED }
    val running = tasks.count { it.state == ImportState.RUNNING }
    val allFinished = tasks.all { it.isFinished }
    val totalBytes = tasks.sumOf { it.totalBytes.coerceAtLeast(0) }
    val doneBytes = tasks.sumOf {
        if (it.state == ImportState.DONE) it.totalBytes.coerceAtLeast(0)
        else it.doneBytes.coerceAtLeast(0)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Ink1)
            .navigationBarsPadding()
            // Tab 栏占底部 84dp，抽屉内容整体上移避免被遮挡
            .padding(bottom = 84.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (allFinished) "导入完成" else "正在导入",
                color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "$doneCount / ${tasks.size}" +
                    (if (failedCount > 0) " · 失败 $failedCount" else ""),
                color = TextLo, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(10.dp))

        if (totalBytes > 0) {
            val pct = (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Accent,
                trackColor = Ink3,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            when {
                allFinished -> "已写入 相册 · Pictures/SonnyLink"
                else -> "${com.sonnyapp.core.importer.ImportQueue.humanSize(doneBytes)} / " +
                    com.sonnyapp.core.importer.ImportQueue.humanSize(totalBytes) +
                    (if (running > 0) " · $running 路并发" else "")
            },
            color = TextLo, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(8.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            for (t in tasks) TaskRow(t)
        }

        Spacer(Modifier.height(12.dp))

        if (allFinished) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        vm.imports.clearFinished()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) { Text("完成", fontSize = 13.5.sp) }
                Button(
                    onClick = {
                        vm.imports.clearFinished()
                        onGotoAlbum()
                    },
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) { Text("查看相册", fontSize = 13.5.sp, fontWeight = FontWeight.Medium) }
            }
        } else {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(13.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Ink2, contentColor = TextMid,
                ),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text("后台继续", fontSize = 13.5.sp) }
        }
    }
}

@Composable
private fun TaskRow(t: ImportTask) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(
                    when (t.state) {
                        ImportState.DONE -> OkGreen
                        ImportState.FAILED -> ErrorRed
                        ImportState.RUNNING -> Accent
                        ImportState.QUEUED -> Ink3
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                color = TextHi, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace,
            )
            Text(
                when (t.state) {
                    ImportState.QUEUED -> "排队中…"
                    ImportState.RUNNING -> {
                        val rate = com.sonnyapp.core.importer.RateSampler.humanRate(t.doneBytes)
                        com.sonnyapp.core.importer.ImportQueue.humanSize(t.doneBytes) +
                            (if (t.totalBytes > 0) " / " +
                                com.sonnyapp.core.importer.ImportQueue.humanSize(t.totalBytes)
                            else "") + (if (rate.isEmpty()) "" else " · $rate")
                    }
                    ImportState.DONE -> "✓ " + t.note
                    ImportState.FAILED -> "失败 · " + t.note
                },
                color = when (t.state) {
                    ImportState.DONE -> OkGreen
                    ImportState.FAILED -> ErrorRed
                    else -> TextLo
                },
                fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
            )
        }
        if (t.state == ImportState.RUNNING) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Accent, strokeWidth = 2.dp,
            )
        }
    }
}
