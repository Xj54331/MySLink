package com.sonnyapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonnyapp.device.DeviceViewModel

/**
 * 顶层：未连接相机 -> 连接引导页；已连接 -> 双 Tab 主界面。
 *
 * 不再有「遥控 / 传输」模式选择页 —— 遥控已整体移除，
 * App 只做照片导入这一件事（相机端固定在「发送到智能手机」模式）。
 */
@Composable
fun AppRoot() {
    val vm: DeviceViewModel = viewModel()
    val connected by vm.connected.collectAsState()

    if (connected) {
        MainTabs(deviceVm = vm)
    } else {
        ConnectScreen(vm)
    }
}
