package com.sonnyapp.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonnyapp.device.DeviceViewModel
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink0
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid

/**
 * 连接引导页（MIMO 式三步引导）。
 *
 * 相机一次只能跑一种模式 —— 必须先在相机上进「发送到智能手机」，
 * 手机再连它的 DIRECT-xx 热点。App 检测到相机热点后**零操作自动连接**。
 */
@Composable
fun ConnectScreen(vm: DeviceViewModel) {
    val context = LocalContext.current
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val wifi by vm.wifiState.collectAsState()
    var ssid by remember { mutableStateOf(vm.savedSsid) }
    var pass by remember { mutableStateOf(vm.savedPass) }
    var advanced by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }

    // 进入页面时自动判断：当前 Wi-Fi 为相机热点则直接连接
    LaunchedEffect(Unit) { vm.autoConnect() }

    Column(
        Modifier.fillMaxSize().background(Ink0).safeDrawingPadding()
            .padding(horizontal = 26.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))

        Text(
            "SONNYLINK",
            color = Accent, fontSize = 10.sp, letterSpacing = 4.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "导入照片",
            color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Serif,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "连接你的索尼相机 · Wi-Fi 直连",
            color = TextLo, fontSize = 12.sp,
        )

        Spacer(Modifier.height(30.dp))

        // 三步引导
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink1)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            GuideStep("1", "相机菜单 → 应用程序 → 发送到智能手机", "保持这个界面不要退出")
            GuideStep("2", "手机 Wi-Fi 设置里连接相机热点", "SSID 形如 DIRECT-xxxx:ILCE-xxxx")
            GuideStep("3", "回到本页，自动检测并连接", "系统提示「无法访问互联网」是正常的")
        }

        Spacer(Modifier.height(20.dp))

        WifiConnectSection(
            wifi = wifi,
            busy = busy,
            onOpenSettings = {
                try {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                }
            },
            onConnect = { vm.connectViaSystemWifi() },
            onRetry = { vm.autoConnect() },
        )

        Spacer(Modifier.height(14.dp))

        Text(
            if (advanced) "收起手动连接" else "手动输入 SSID / 密码（备用）",
            color = TextMid, fontSize = 11.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(50))
                .background(Ink1)
                .clickable { advanced = !advanced }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )

        if (advanced) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = ssid, onValueChange = { ssid = it },
                label = { Text("相机 SSID") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("密码") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.connect(ssid.trim(), pass.trim()) },
                enabled = !busy && ssid.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("用 SSID / 密码连接", fontSize = 14.sp) }
        }

        Spacer(Modifier.height(18.dp))

        // 连接过程日志（等宽小字，诊断用）
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink1)
                .padding(14.dp),
        ) {
            Text(
                status,
                color = TextMid, fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "第三方非官方软件，与索尼公司无任何关联\n仅用于连接你自己拥有的相机",
            color = TextLo.copy(alpha = 0.7f), fontSize = 9.5.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(bottom = 30.dp),
        )
    }

    if (logOpen) {
        LogPanel(vm.buildDiag()) { logOpen = false }
    }
}

@Composable
private fun GuideStep(n: String, title: String, sub: String) {
    Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(
            n,
            color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(end = 12.dp, top = 1.dp),
        )
        Column {
            Text(title, color = TextHi, fontSize = 13.sp, lineHeight = 19.sp)
            Text(sub, color = TextLo, fontSize = 10.sp)
        }
    }
}
