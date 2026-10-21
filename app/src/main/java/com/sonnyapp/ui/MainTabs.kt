package com.sonnyapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonnyapp.album.AlbumScreen
import com.sonnyapp.album.AlbumViewModel
import com.sonnyapp.device.DeviceViewModel
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.TextLo

private enum class Tab(val label: String) { DEVICE("设备"), ALBUM("相册") }

/**
 * 主骨架：设备 / 相册 双 Tab（对齐 DJI MIMO 的信息架构）。
 *
 * 技术项（并发、保存位置、日志）全部收进设置页 —— 主界面只留照片。
 * 图标为 Canvas 自绘（不引 material-icons-extended，省一个依赖）。
 */
@Composable
fun MainTabs(
    deviceVm: DeviceViewModel = viewModel(),
    albumVm: AlbumViewModel = viewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(Tab.DEVICE) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsDiag by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        when (tab) {
            Tab.DEVICE -> DeviceScreen(
                vm = deviceVm,
                onGotoAlbum = { tab = Tab.ALBUM },
                onOpenSettings = {
                    settingsDiag = deviceVm.buildDiag()
                    settingsOpen = true
                },
            )
            Tab.ALBUM -> AlbumScreen(vm = albumVm)
        }

        NavigationBar(
            containerColor = Ink1,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            NavigationBarItem(
                selected = tab == Tab.DEVICE,
                onClick = { tab = Tab.DEVICE },
                icon = { DeviceIcon() },
                label = { Text("设备", fontSize = 10.sp, letterSpacing = 1.sp) },
                colors = tabColors(),
            )
            NavigationBarItem(
                selected = tab == Tab.ALBUM,
                onClick = { tab = Tab.ALBUM },
                icon = { AlbumIcon() },
                label = { Text("相册", fontSize = 10.sp, letterSpacing = 1.sp) },
                colors = tabColors(),
            )
        }

        if (settingsOpen) {
            SettingsScreen(
                vm = deviceVm,
                diag = settingsDiag,
                onBack = { settingsOpen = false },
            )
        }
    }
}

@Composable
private fun tabColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Accent,
    selectedTextColor = Accent,
    unselectedIconColor = TextLo,
    unselectedTextColor = TextLo,
    indicatorColor = Color.Transparent,
)

/** 相机轮廓 + 镜头（设备 tab）。 */
@Composable
private fun DeviceIcon() {
    Canvas(Modifier.size(22.dp)) {
        val stroke = Stroke(width = size.width * 0.075f)
        val r = size.width * 0.14f
        // 机身
        drawRoundRect(
            color = Color(0xFFFFFFFF),
            topLeft = Offset(size.width * 0.05f, size.height * 0.24f),
            size = Size(size.width * 0.9f, size.height * 0.62f),
            cornerRadius = CornerRadius(r, r),
            style = stroke,
        )
        // 顶部取景器凸起
        drawLine(
            color = Color(0xFFFFFFFF),
            start = Offset(size.width * 0.33f, size.height * 0.24f),
            end = Offset(size.width * 0.37f, size.height * 0.13f),
            strokeWidth = size.width * 0.075f,
        )
        drawLine(
            color = Color(0xFFFFFFFF),
            start = Offset(size.width * 0.63f, size.height * 0.24f),
            end = Offset(size.width * 0.59f, size.height * 0.13f),
            strokeWidth = size.width * 0.075f,
        )
        // 镜头
        drawCircle(
            color = Color(0xFFFFFFFF),
            radius = size.width * 0.17f,
            center = Offset(size.width * 0.5f, size.height * 0.55f),
            style = stroke,
        )
    }
}

/** 2x2 网格（相册 tab）。 */
@Composable
private fun AlbumIcon() {
    Canvas(Modifier.size(22.dp)) {
        val s = size.width * 0.36f
        val gap = size.width * 0.1f
        val startX = (size.width - s * 2 - gap) / 2
        val startY = (size.height - s * 2 - gap) / 2
        val r = CornerRadius(size.width * 0.06f, size.width * 0.06f)
        val c = Color(0xFFFFFFFF)
        drawRoundRect(c, Offset(startX, startY), Size(s, s), r)
        drawRoundRect(c, Offset(startX + s + gap, startY), Size(s, s), r)
        drawRoundRect(c, Offset(startX, startY + s + gap), Size(s, s), r)
        drawRoundRect(c, Offset(startX + s + gap, startY + s + gap), Size(s, s), r)
    }
}
