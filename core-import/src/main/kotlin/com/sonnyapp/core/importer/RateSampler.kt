package com.sonnyapp.core.importer

/**
 * 瞬时下载测速器：指数滑动平均，抗抖动。
 *
 * 进度回调按网络真实节奏来（快慢不均），直接 (Δbytes/Δt) 逐次计算会在
 * UI 上跳成心电图。这里对采样率做下限（默认 300ms 才产生一个新样本），
 * 并做 α=0.45 的指数平滑 —— 数值稳定且对速度变化的响应延迟 < 1s。
 *
 * 纯 Kotlin + 可注入时钟，JVM 单测可完全控制时间（见 RateSamplerTest）。
 */
class RateSampler(
    private val now: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 300,
    private val alpha: Double = 0.45,
) {
    private var lastAt = -1L
    private var lastBytes = -1L
    private var rate = 0.0

    /**
     * 喂入最新累计字节数，返回当前平滑速度（字节/秒）。
     * 采样间隔不足时返回上一次的平滑值，不产生新样本。
     */
    @Synchronized
    fun update(doneBytes: Long): Long {
        val t = now()
        if (lastAt < 0) {
            lastAt = t
            lastBytes = doneBytes
            return 0L
        }
        val dt = t - lastAt
        if (dt < minIntervalMs) return rate.toLong()
        val db = doneBytes - lastBytes
        if (db >= 0) {
            val instant = db * 1000.0 / dt
            rate = if (rate <= 0) instant else alpha * instant + (1 - alpha) * rate
        } else {
            // 字节数回退（重试重下）：重置基准，速度清零
            rate = 0.0
        }
        lastAt = t
        lastBytes = doneBytes
        return rate.toLong()
    }

    @Synchronized
    fun reset() {
        lastAt = -1L
        lastBytes = -1L
        rate = 0.0
    }

    /** 当前平滑速度快照（不产生新样本）。 */
    @Synchronized
    fun current(): Long = rate.toLong()

    companion object {
        /** 人类可读速度，如 "2.1 MB/s"。 */
        fun humanRate(bytesPerSecond: Long): String = when {
            bytesPerSecond <= 0 -> ""
            bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
            bytesPerSecond < 1024 * 1024 -> "${bytesPerSecond / 1024} KB/s"
            else -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / 1048576.0)
        }

        /** 按当前速度估算剩余秒数；速度未知返回 -1。 */
        fun etaSeconds(remainingBytes: Long, bytesPerSecond: Long): Long =
            if (bytesPerSecond > 0 && remainingBytes > 0) {
                (remainingBytes + bytesPerSecond - 1) / bytesPerSecond
            } else -1L
    }
}
