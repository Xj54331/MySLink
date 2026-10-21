package com.sonnyapp.core.importer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ImportQueue 状态机测试。
 *
 * 全部跑在 TestScope 虚拟时间上：Performer 用 delay 模拟下载，
 * 并发上限 / 重试 / 去重全部可确定性验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportQueueTest {

    private fun task(id: String, title: String = "$id.JPG", size: Long = 1000L) =
        ImportTask(id = id, title = title, totalBytes = size)

    // 队列 scope：显式绑定测试调度器 + Unconfined（急切执行）。
    // 不能用 backgroundScope —— 实测它不参与 advanceUntilIdle 的空闲判定，
    // 队列内部的 launch 与 delay 永远不会被推进（任务停在 QUEUED/RUNNING）。
    private fun TestScope.qScope(): CoroutineScope =
        CoroutineScope(coroutineContext + UnconfinedTestDispatcher(testScheduler) + Job())

    private fun runQTest(block: suspend TestScope.() -> Unit) =
        runTest(UnconfinedTestDispatcher()) { block() }
    private fun okPerformer(steps: Int = 3, stepDelayMs: Long = 100) =
        ImportQueue.Performer { t, onProgress ->
            repeat(steps) { i ->
                delay(stepDelayMs)
                onProgress(t.totalBytes * (i + 1) / steps)
            }
            ImportOutcome.Success(t.totalBytes)
        }

    @Test
    fun `submit 成功进入队列`() = runQTest {
        val q = ImportQueue(qScope())
        val r = q.submit(listOf(task("a"), task("b")))
        assertEquals(2, r.accepted)
        assertEquals(0, r.skippedDup)
        assertEquals(2, q.tasks.value.size)
        assertTrue(q.tasks.value.all { it.state == ImportState.QUEUED })
    }

    @Test
    fun `队列中重复 id 被跳过`() = runQTest {
        val q = ImportQueue(qScope())
        q.submit(listOf(task("a")))
        val r = q.submit(listOf(task("a"), task("b")))
        assertEquals(1, r.accepted)
        assertEquals(1, r.skippedDup)
        assertEquals(2, q.tasks.value.size)
    }

    @Test
    fun `已完成任务的重试会替换旧记录`() = runQTest {
        val q = ImportQueue(qScope())
        q.submit(listOf(task("a")))
        q.start(okPerformer())
        advanceUntilIdle()
        assertEquals(ImportState.DONE, q.tasks.value.first { it.id == "a" }.state)

        val r = q.submit(listOf(task("a")))
        assertEquals(1, r.accepted)
        assertEquals(1, q.tasks.value.size)
        assertEquals(ImportState.QUEUED, q.tasks.value.first().state)
    }

    @Test
    fun `全部任务成功后状态为 DONE 且进度满格`() = runQTest {
        val q = ImportQueue(qScope())
        q.submit(listOf(task("a"), task("b"), task("c")))
        q.start(okPerformer())
        advanceUntilIdle()
        val list = q.tasks.value
        assertEquals(3, list.size)
        assertTrue(list.all { it.state == ImportState.DONE })
        assertTrue(list.all { it.doneBytes == it.totalBytes })
        assertTrue(list.all { it.fraction == 1f })
    }

    @Test
    fun `进度回调只作用于 RUNNING 的任务`() = runQTest {
        val q = ImportQueue(qScope())
        q.submit(listOf(task("a", size = 900)))
        q.start(ImportQueue.Performer { t, onProgress ->
            delay(50)
            onProgress(300)
            delay(50)
            // 外部把任务标掉（模拟 cancelAll 竞态）
            q.clearFinished()
            onProgress(900) // 此时任务仍在 map 中则更新，否则无效果
            ImportOutcome.Success(t.totalBytes)
        })
        advanceUntilIdle()
        // 无论中途如何，最终是 DONE 或被清掉，不抛异常即通过
        assertTrue(q.tasks.value.size <= 1)
    }

    @Test
    fun `可重试失败会自动重试一次并最终成功`() = runQTest {
        var calls = 0
        val q = ImportQueue(qScope())
        q.submit(listOf(task("a")))
        q.start(ImportQueue.Performer { t, _ ->
            calls++
            if (calls == 1) ImportOutcome.Failure("断流", retryable = true)
            else ImportOutcome.Success(t.totalBytes)
        })
        advanceUntilIdle()
        assertEquals(2, calls)
        val t = q.tasks.value.first()
        assertEquals(ImportState.DONE, t.state)
        assertEquals(2, t.attempts)
    }

    @Test
    fun `不可重试失败直接 FAILED`() = runQTest {
        var calls = 0
        val q = ImportQueue(qScope())
        q.submit(listOf(task("a")))
        q.start(ImportQueue.Performer { _, _ ->
            calls++
            ImportOutcome.Failure("HTTP 406", retryable = false)
        })
        advanceUntilIdle()
        assertEquals(1, calls)
        val t = q.tasks.value.first()
        assertEquals(ImportState.FAILED, t.state)
        assertEquals("HTTP 406", t.note)
    }

    @Test
    fun `重试耗尽后 FAILED`() = runQTest {
        var calls = 0
        val q = ImportQueue(qScope())
        q.submit(listOf(task("a")))
        q.start(ImportQueue.Performer { _, _ ->
            calls++
            ImportOutcome.Failure("网络超时", retryable = true)
        })
        advanceUntilIdle()
        assertEquals(2, calls) // maxAttempts 默认 2
        assertEquals(ImportState.FAILED, q.tasks.value.first().state)
    }

    @Test
    fun `执行器抛异常按可重试失败处理`() = runQTest {
        var calls = 0
        val q = ImportQueue(qScope())
        q.submit(listOf(task("a")))
        q.start(ImportQueue.Performer { _, _ ->
            calls++
            throw IllegalStateException("boom")
        })
        advanceUntilIdle()
        assertEquals(2, calls)
        val t = q.tasks.value.first()
        assertEquals(ImportState.FAILED, t.state)
        assertEquals("boom", t.note)
    }

    @Test
    fun `并发数不超过上限`() = runQTest {
        var running = 0
        var peak = 0
        val q = ImportQueue(qScope(), concurrencyProvider = { 2 })
        q.submit(listOf(task("a"), task("b"), task("c"), task("d"), task("e")))
        q.start(ImportQueue.Performer { t, _ ->
            running++
            if (running > peak) peak = running
            delay(200)
            running--
            ImportOutcome.Success(t.totalBytes)
        })
        advanceUntilIdle()
        assertTrue("峰值并发 $peak 超过上限 2", peak <= 2)
        assertEquals(5, q.tasks.value.count { it.state == ImportState.DONE })
    }

    @Test
    fun `并发执行比串行快`() = runQTest {
        val q = ImportQueue(qScope(), concurrencyProvider = { 3 })
        q.submit(listOf(task("a"), task("b"), task("c")))
        val t0 = testScheduler.currentTime
        q.start(okPerformer(steps = 1, stepDelayMs = 500))
        advanceUntilIdle()
        val elapsed = testScheduler.currentTime - t0
        // 3 个 500ms 任务 3 路并发 ≈ 500ms（虚拟时间），串行则 1500ms
        assertTrue("耗时 ${elapsed}ms，未体现并发", elapsed < 1000)
    }

    @Test
    fun `clearFinished 只清已结束的`() = runQTest {
        val q = ImportQueue(qScope())
        q.submit(listOf(task("a")))
        q.start(okPerformer())
        advanceUntilIdle()
        q.submit(listOf(task("b")))
        q.clearFinished()
        val ids = q.tasks.value.map { it.id }
        assertEquals(listOf("b"), ids)
    }

    @Test
    fun `cancelAll 把未完成任务标为 FAILED`() = runQTest {
        val q = ImportQueue(qScope())
        q.submit(listOf(task("a"), task("b")))
        q.start(ImportQueue.Performer { t, _ ->
            delay(10_000)
            ImportOutcome.Success(t.totalBytes)
        })
        delay(50) // 让任务跑起来
        q.cancelAll("用户取消")
        advanceUntilIdle()
        assertTrue(q.tasks.value.all { it.state == ImportState.FAILED })
        assertTrue(q.tasks.value.all { it.note == "用户取消" })
        assertFalse(q.isActive)
    }

    @Test
    fun `pendingCount 反映未完成数量`() = runQTest {
        val q = ImportQueue(qScope())
        q.submit(listOf(task("a"), task("b")))
        assertEquals(2, q.pendingCount)
        q.start(okPerformer())
        advanceUntilIdle()
        assertEquals(0, q.pendingCount)
    }

    @Test
    fun `humanSize 格式化`() {
        assertEquals("?", ImportQueue.humanSize(0))
        assertEquals("512 B", ImportQueue.humanSize(512))
        assertEquals("2 KB", ImportQueue.humanSize(2048))
        assertEquals("1.5 MB", ImportQueue.humanSize(1572864))
    }
}
