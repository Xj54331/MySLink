package com.sonnyapp.core.importer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 导入队列：并发受限、失败重试一次、进度不可变快照发布。
 *
 * ## 设计要点
 *
 *  - **纯 Kotlin**：不依赖 Android / 真实网络。真正的下载动作由 [Performer] 注入，
 *    因此整个状态机可以在 JVM 上跑单测（见 ImportQueueTest）。
 *  - **不可变任务列表**：进度每 tick 只替换一个下标，其余引用复用，
 *    UI 侧 StateFlow 比较天然跳过无变化的重组。
 *  - **并发闸门**：相机端是它自己的小 HTTP 服务，多路能提高总吞吐，
 *    但超过相机处理能力只是互相抢带宽 —— 并发数由 [concurrencyProvider] 动态取，
 *    UI 可在设置里实时调整，下一批任务生效。
 *  - **重试一次**：弱网下偶发断流很常见，自动重试一次能消掉大多数"下了一半失败"；
 *    重试仍失败就标 FAILED 交给用户，避免无限重试拖死队列。
 */
class ImportQueue(
    private val scope: CoroutineScope,
    private val concurrencyProvider: () -> Int = { 3 },
    private val maxAttempts: Int = 2,
) {

    /** 真正干活的执行器：下载 + 落盘。实现方负责节流 onProgress（建议 500ms）。 */
    fun interface Performer {
        suspend fun perform(task: ImportTask, onProgress: (Long) -> Unit): ImportOutcome
    }

    private val _tasks = MutableStateFlow<List<ImportTask>>(emptyList())
    val tasks: StateFlow<List<ImportTask>> = _tasks.asStateFlow()

    private var job: Job? = null

    /** 队列是否正在执行（含等待重试）。 */
    val isActive: Boolean get() = job?.isActive == true

    /** 未完成任务数。 */
    val pendingCount: Int get() = _tasks.value.count { !it.isFinished }

    /**
     * 提交任务。队列中（QUEUED/RUNNING）已存在同 id 时跳过 —— 同一张图重复点下载是常见操作。
     * 已结束（DONE/FAILED）的同 id 任务会被新任务替换（用户显式重试）。
     */
    fun submit(new: List<ImportTask>): SubmitResult {
        var accepted = 0
        var skipped = 0
        val cur = _tasks.value
        // 活跃 id 集合（队列中已有的 + 本批次内已收的，用 HashSet 判重）
        val activeIds = HashSet(cur.filter { !it.isFinished }.map { it.id })
        val list = cur.filterNot { it.isFinished && new.any { n -> n.id == it.id } }.toMutableList()
        for (t in new) {
            if (!activeIds.add(t.id)) {
                // 队列中已存在，或同批次内重复
                skipped++
                continue
            }
            list.add(t.copy(state = ImportState.QUEUED, doneBytes = 0L, attempts = 0, note = ""))
            accepted++
        }
        _tasks.value = list
        return SubmitResult(accepted, skipped)
    }

    /**
     * 启动执行。已有 job 在跑时什么都不做（新提交的任务会被现有 worker 拾起的前提
     * 不成立 —— worker 是按启动时的 QUEUED 列表铺开的，因此 [submit] 之后
     * 如果 [isActive] 为 false 需要再调一次 start）。
     */
    fun start(performer: Performer) {
        if (job?.isActive == true) return
        val initial = _tasks.value.filter { it.state == ImportState.QUEUED }
        if (initial.isEmpty()) return
        job = scope.launch {
            val gate = Semaphore(concurrencyProvider().coerceIn(1, 8))
            coroutineScope {
                initial.map { t ->
                    async {
                        gate.withPermit { runOne(t.id, performer) }
                    }
                }.awaitAll()
            }
        }
    }

    /** 取消整个队列（未完成任务标 FAILED）。 */
    fun cancelAll(reason: String = "已取消") {
        job?.cancel()
        job = null
        _tasks.value = _tasks.value.map {
            if (!it.isFinished) it.copy(state = ImportState.FAILED, note = reason) else it
        }
    }

    /** 清掉已结束的任务（UI 确认后调用）。 */
    fun clearFinished() {
        _tasks.value = _tasks.value.filterNot { it.isFinished }
    }

    private suspend fun runOne(id: String, performer: Performer) {
        while (true) {
            val task = _tasks.value.firstOrNull { it.id == id } ?: return
            if (task.state != ImportState.QUEUED) return
            update(id) { it.copy(state = ImportState.RUNNING, attempts = it.attempts + 1) }

            val outcome = try {
                performer.perform(task.copy(state = ImportState.RUNNING)) { bytes ->
                    update(id) { t -> if (t.state == ImportState.RUNNING) t.copy(doneBytes = bytes) else t }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消必须向上传播：吞掉它会让 runOne 在 cancelAll 之后
                // 继续在 QUEUED/RUNNING 之间空转，状态永远标不成 FAILED。
                throw e
            } catch (e: Exception) {
                ImportOutcome.Failure(e.message ?: e.javaClass.simpleName, retryable = true)
            }

            when (outcome) {
                is ImportOutcome.Success -> {
                    update(id) {
                        it.copy(
                            state = ImportState.DONE,
                            doneBytes = if (it.totalBytes > 0) it.totalBytes else it.doneBytes,
                            note = outcome.note.ifEmpty { humanSize(outcome.bytes) },
                        )
                    }
                    return
                }
                is ImportOutcome.Failure -> {
                    val cur = _tasks.value.firstOrNull { it.id == id } ?: return
                    if (outcome.retryable && cur.attempts < maxAttempts) {
                        // 重试：回到 QUEUED 立即重跑（保留同一个 permit，不放其他任务插队抢带宽）
                        update(id) { it.copy(state = ImportState.QUEUED, doneBytes = 0L) }
                        continue
                    }
                    update(id) { it.copy(state = ImportState.FAILED, note = outcome.reason) }
                    return
                }
            }
        }
    }

    /** 只替换目标下标，其余元素保持同一实例（利于 Compose 稳定性）。 */
    private fun update(id: String, f: (ImportTask) -> ImportTask) {
        val cur = _tasks.value
        val i = cur.indexOfFirst { it.id == id }
        if (i < 0) return
        val next = f(cur[i])
        if (next === cur[i]) return
        val list = cur.toMutableList()
        list[i] = next
        _tasks.value = list
    }

    companion object {
        fun humanSize(bytes: Long): String = when {
            bytes <= 0 -> "?"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        }
    }
}
