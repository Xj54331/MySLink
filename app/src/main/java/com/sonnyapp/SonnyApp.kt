package com.sonnyapp

import android.app.Application
import com.sonnyapp.import.ImportManager

/**
 * Application 单例。
 *
 * ImportManager 必须比任何页面活得久 —— 批量导入进行中切到相册 tab 甚至
 * 旋转屏幕都不能中断队列，所以它挂在这里而不是 ViewModel 里。
 */
class SonnyApp : Application() {

    val imports: ImportManager by lazy { ImportManager(this) }

    override fun onCreate() {
        super.onCreate()
        // 上次进程被杀可能留下 IS_PENDING 的半成品条目（对其他人不可见，但占文件名）。
        // 这里顺手清掉，避免反复累积。
        imports.cleanupStalePending()
    }
}
