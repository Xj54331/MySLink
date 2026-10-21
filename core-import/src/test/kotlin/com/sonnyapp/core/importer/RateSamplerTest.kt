package com.sonnyapp.core.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateSamplerTest {

    /** 手动时钟。 */
    private class Clock(var t: Long = 0L) {
        fun advance(ms: Long) { t += ms }
    }

    @Test
    fun `首次调用返回 0 且只记录基准`() {
        val c = Clock(1000)
        val s = RateSampler(now = { c.t })
        assertEquals(0L, s.update(0))
    }

    @Test
    fun `匀速下载速度正确`() {
        val c = Clock(0)
        val s = RateSampler(now = { c.t }, minIntervalMs = 300)
        s.update(0)
        c.advance(1000)
        // 1 秒 100KB -> ~100KB/s
        val r = s.update(100 * 1024)
        assertTrue("实际 $r", Math.abs(r - 100 * 1024) < 2048)
    }

    @Test
    fun `采样间隔不足时返回旧值`() {
        val c = Clock(0)
        val s = RateSampler(now = { c.t }, minIntervalMs = 300)
        s.update(0)
        c.advance(1000)
        s.update(1000)
        val before = s.current()
        c.advance(100) // < minInterval
        val r = s.update(2000)
        assertEquals(before, r)
    }

    @Test
    fun `指数平滑抑制突变`() {
        val c = Clock(0)
        val s = RateSampler(now = { c.t }, minIntervalMs = 300, alpha = 0.5)
        s.update(0)
        // 第一个样本：1000 B/s
        c.advance(1000)
        s.update(1000)
        // 突增至 100x：平滑后应远小于瞬时值 1MB/s
        c.advance(1000)
        val r = s.update(1000 + 1_000_000)
        assertTrue("平滑失败：$r", r < 800_000)
        assertTrue(r > 1000)
    }

    @Test
    fun `字节数回退时重置基准`() {
        val c = Clock(0)
        val s = RateSampler(now = { c.t })
        s.update(0)
        c.advance(1000)
        s.update(10_000)
        c.advance(1000)
        val r = s.update(500) // 重试后从头开始
        assertEquals(0L, r)
        c.advance(1000)
        val r2 = s.update(1_050_500) // 1s 1MB -> 应恢复速度
        assertTrue("恢复失败：$r2", r2 > 900_000)
    }

    @Test
    fun `reset 清空状态`() {
        val c = Clock(0)
        val s = RateSampler(now = { c.t })
        s.update(0)
        c.advance(1000)
        s.update(50_000)
        s.reset()
        assertEquals(0L, s.current())
        assertEquals(0L, s.update(0))
    }

    @Test
    fun `humanRate 格式化`() {
        assertEquals("", RateSampler.humanRate(0))
        assertEquals("512 B/s", RateSampler.humanRate(512))
        assertEquals("2 KB/s", RateSampler.humanRate(2048))
        assertEquals("2.5 MB/s", RateSampler.humanRate((2.5 * 1048576).toLong()))
    }

    @Test
    fun `etaSeconds 估算`() {
        assertEquals(-1L, RateSampler.etaSeconds(1000, 0))
        assertEquals(-1L, RateSampler.etaSeconds(0, 1000))
        assertEquals(5L, RateSampler.etaSeconds(5000, 1000))
        // 向上取整：1 字节剩余、1000 B/s -> 1s
        assertEquals(1L, RateSampler.etaSeconds(1, 1000))
    }
}
