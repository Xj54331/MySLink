package com.sonnyapp.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonnyapp.device.DeviceViewModel
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink0
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.Ink2
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid

/**
 * 设置页：所有技术项收纳在这里，主界面零暴露（暗房设计原则：界面安静）。
 *
 *  - 下载并发：1 / 3 / 6 / 8（默认 3；相机端是小 HTTP 服务，过大只是抢带宽）
 *  - 保存位置：默认系统相册 Pictures/SonnyLink；可选 SAF 自定义目录（高级）
 *  - 诊断日志：一键复制完整环境报告（机型差异排查用）
 */
@Composable
fun SettingsScreen(
    vm: DeviceViewModel,
    diag: String,
    onBack: () -> Unit,
) {
    val concurrency by vm.imports.concurrency.collectAsState()
    val saveTree by vm.imports.saveTreeUri.collectAsState()
    val context = LocalContext.current
    var logOpen by remember { mutableStateOf(false) }
    val pickTree = RememberSaveTreePicker { uri ->
        vm.imports.setSaveTreeUri(uri)
        Toast.makeText(context, "已改用自选目录", Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize().background(Ink0)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹", color = TextHi, fontSize = 22.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .clickable { onBack() }
                        .padding(end = 14.dp, top = 4.dp, bottom = 4.dp),
                )
                Text(
                    "设置",
                    color = TextHi, fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif,
                )
            }

            SectionTitle("下载")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink1)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("并发路数", color = TextHi, fontSize = 13.5.sp)
                    Text(
                        "多路并发提高总吞吐；超过相机处理能力只是互相抢带宽",
                        color = TextLo, fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in listOf(1, 3, 6, 8)) {
                        Text(
                            n.toString(),
                            color = if (concurrency == n) Color(0xFF171204) else TextMid,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (concurrency == n) Accent else Ink2)
                                .clickable { vm.imports.setConcurrency(n) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            SectionTitle("保存位置")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink1)
                    .padding(16.dp),
            ) {
                Text("落盘位置", color = TextHi, fontSize = 13.5.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (saveTree == null) "系统相册 Pictures/SonnyLink（推荐：免权限、相册页可见）"
                    else "自选目录（相册页不索引此位置）",
                    color = if (saveTree == null) Accent else TextLo,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallChip("改用自选目录") { pickTree() }
                    if (saveTree != null) {
                        SmallChip("恢复默认") { vm.imports.setSaveTreeUri(null) }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            SectionTitle("诊断")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink1)
                    .padding(16.dp)
                    .clickable { logOpen = true },
            ) {
                Text("诊断日志", color = TextHi, fontSize = 13.5.sp)
                Text(
                    "连接过程、缓存统计、环境信息 —— 排查「连不上/传不了」时一键复制",
                    color = TextLo, fontSize = 10.sp,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "SonnyLink 2.0 · 照片导入版\n第三方非官方软件，与索尼公司无任何关联\n仅用于连接你自己拥有的相机",
                color = TextLo.copy(alpha = 0.7f), fontSize = 9.5.sp, lineHeight = 15.sp,
            )
        }

        if (logOpen) {
            LogPanel(diag) { logOpen = false }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = TextLo, fontSize = 10.5.sp, letterSpacing = 1.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp, start = 4.dp),
    )
}

@Composable
private fun SmallChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = TextMid, fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(Ink2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
