package com.sonnyapp.import

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sonnyapp.SonnyApp
import com.sonnyapp.core.importer.ImportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 导入前台服务：批量下载期间保活 + 系统通知进度。
 *
 * 为什么必须有：几十张原图在弱网下要几分钟，普通进程随时可能因息屏/后台被杀 ——
 * 一旦被杀，正在写的半成品只能靠 IS_PENDING 清理兜底，用户看到的是「下到 80% 消失了」。
 *
 * 服务本身很薄：ImportManager 才是队列本体（Application 级），
 * 这里只观察 tasks 快照刷通知，队列空闲后自动 stopSelf。
 * 类型 mediaProcessing（API 34+ 要求声明具体类型）。
 */
class ImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastRateAt = 0L
    private var lastDone = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        val manager = (application as SonnyApp).imports
        scope.launch {
            var startedAt = System.currentTimeMillis()
            manager.tasks.collect { tasks ->
                if (tasks.isEmpty()) {
                    stopSelf()
                    return@collect
                }
                val doneCount = tasks.count { it.state == ImportState.DONE }
                val failedCount = tasks.count { it.state == ImportState.FAILED }
                val running = tasks.count { it.state == ImportState.RUNNING }
                val doneBytes = tasks.sumOf {
                    if (it.state == ImportState.DONE) it.totalBytes.coerceAtLeast(0)
                    else it.doneBytes.coerceAtLeast(0)
                }
                val totalBytes = tasks.sumOf { it.totalBytes.coerceAtLeast(0) }

                // 聚合速率：500ms 窗口
                val now = System.currentTimeMillis()
                var rate = 0L
                if (now - lastRateAt >= 500) {
                    rate = (doneBytes - lastDone) * 1000L / (now - lastRateAt).coerceAtLeast(1)
                    lastRateAt = now
                    lastDone = doneBytes
                }

                val allFinished = tasks.all { it.isFinished }
                if (allFinished) {
                    val text = "完成 $doneCount" +
                        (if (failedCount > 0) " · 失败 $failedCount" else "") +
                        " · 已写入系统相册"
                    notify(buildNotification(text, if (totalBytes > 0) 100 else 0, 100, silent = true))
                    // 完成后停留 3 秒再退，让用户看清结果
                    if (now - startedAt > 3000 || failedCount == 0) {
                        stopSelf()
                    }
                } else {
                    val pct = if (totalBytes > 0) {
                        (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
                    } else 0
                    val text = "正在导入 ${doneCount + running}/" +
                        (doneCount + failedCount + running) +
                        (if (rate > 0) " · ${humanRate(rate)}" else "")
                    notify(buildNotification(text, pct, 100, silent = false))
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startAsForeground() {
        ensureChannel()
        val id = NOTIFICATION_ID
        // dataSync：全版本可用的下载类前台服务类型（API 29+）。
        // 注意 androidx.core 的 ServiceCompat 不含 FGS 类型常量，
        // 直接用 framework 的 ServiceInfo 常量。
        val type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, id, buildNotification("准备导入…", 0, 100, true), type)
    }

    private fun notify(n: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "照片导入",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "从相机下载照片时显示进度" },
            )
        }
    }

    private fun buildNotification(
        text: String,
        progress: Int,
        max: Int,
        silent: Boolean,
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("SonnyLink")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(!silent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (!silent && max > 0) builder.setProgress(max, progress, false)
        else if (silent) builder.setProgress(0, 0, false)
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun humanRate(bytesPerSecond: Long): String =
        com.sonnyapp.core.importer.RateSampler.humanRate(bytesPerSecond)

    companion object {
        private const val CHANNEL_ID = "import"
        private const val NOTIFICATION_ID = 42
    }
}
