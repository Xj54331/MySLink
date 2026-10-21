package com.sonnyapp.core.importer

/**
 * 导入任务状态。
 *
 * QUEUED -> RUNNING -> DONE
                     -> FAILED（不可重试 / 重试耗尽）
 * 失败且可重试时会在 [ImportQueue] 内部回到 RUNNING（最多 attempts 上限）。
 */
enum class ImportState { QUEUED, RUNNING, DONE, FAILED }

/**
 * 一个导入任务（从相机 DLNA 下载一张照片到手机）。
 *
 * 纯数据，不持有任何 Android / 网络对象 —— 状态机整体可脱离设备做 JVM 单测。
 *
 * @param id            DLNA itemId，队列内唯一（用于去重与 UI 定位）
 * @param title         相机上的文件名（如 DSC00437.JPG）
 * @param totalBytes    已知的文件大小；未知为 -1
 * @param doneBytes     已下载字节数
 * @param isPreview     true = RAW 的转码预览（相机不提供 ORG 原图）
 */
data class ImportTask(
    val id: String,
    val title: String,
    val totalBytes: Long = -1L,
    val doneBytes: Long = 0L,
    val state: ImportState = ImportState.QUEUED,
    val note: String = "",
    val attempts: Int = 0,
    val isPreview: Boolean = false,
) {
    /** 0..1；totalBytes 未知返回 -1（UI 显示不确定进度）。 */
    val fraction: Float
        get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat() else -1f

    val isFinished: Boolean get() = state == ImportState.DONE || state == ImportState.FAILED
}

/** 单个任务的执行结果。 */
sealed interface ImportOutcome {
    data class Success(val bytes: Long, val note: String = "") : ImportOutcome
    data class Failure(val reason: String, val retryable: Boolean = true) : ImportOutcome
}

/** 提交结果：accepted 进入队列，skippedDup 是队列中已存在同 id 任务而跳过的。 */
data class SubmitResult(val accepted: Int, val skippedDup: Int)
