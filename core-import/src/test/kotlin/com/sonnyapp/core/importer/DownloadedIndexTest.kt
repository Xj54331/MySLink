package com.sonnyapp.core.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedIndexTest {

    private class MemStore : DownloadedIndex.Store {
        var text: String? = null
        var writes = 0
        override fun read(): String = text ?: ""
        override fun write(text: String) {
            this.text = text
            writes++
        }
    }

    @Test
    fun `add 后 contains 为真`() {
        val idx = DownloadedIndex(MemStore())
        assertTrue(idx.add("DSC001.JPG", 12345))
        assertTrue(idx.contains("DSC001.JPG", 12345))
    }

    @Test
    fun `同 key 重复 add 返回 false`() {
        val idx = DownloadedIndex(MemStore())
        assertTrue(idx.add("DSC001.JPG", 12345))
        assertFalse(idx.add("DSC001.JPG", 12345))
        assertEquals(1, idx.size())
    }

    @Test
    fun `同名不同大小视为不同照片`() {
        val idx = DownloadedIndex(MemStore())
        idx.add("DSC001.JPG", 100)
        assertTrue(idx.add("DSC001.JPG", 200))
        assertEquals(2, idx.size())
    }

    @Test
    fun `remove 移除条目`() {
        val idx = DownloadedIndex(MemStore())
        idx.add("DSC001.JPG", 100)
        idx.remove("DSC001.JPG", 100)
        assertFalse(idx.contains("DSC001.JPG", 100))
        assertEquals(0, idx.size())
    }

    @Test
    fun `持久化往返`() {
        val store = MemStore()
        val a = DownloadedIndex(store)
        a.add("DSC001.JPG", 100)
        a.add("DSC002.ARW", 200)

        // 新实例从同一 store 加载
        val b = DownloadedIndex(store)
        assertTrue(b.contains("DSC001.JPG", 100))
        assertTrue(b.contains("DSC002.ARW", 200))
        assertEquals(2, b.size())
    }

    @Test
    fun `文件名含 tab 与中文也能正确往返`() {
        val store = MemStore()
        val a = DownloadedIndex(store)
        a.add("照片\t备份.JPG", 999)
        val b = DownloadedIndex(store)
        // tab 在标题里：key 拆分用「最后一个分隔符」，标题里的 tab 会导致错位——
        // 这正是 key 用不可见分隔符 ¦ 的原因；此处 tab 标题应仍可匹配
        // （我们的分隔符是 ¦ 而非 \t，所以 \t 只是普通字符）
        assertTrue(b.contains("照片\t备份.JPG", 999))
    }

    @Test
    fun `读取损坏内容不崩溃`() {
        val store = MemStore()
        store.text = "garbage\nno-tab\n123\tok.JPG\n\n"
        val idx = DownloadedIndex(store)
        assertTrue(idx.contains("ok.JPG", 123))
        assertEquals(1, idx.size())
    }

    @Test
    fun `store 读取抛异常时按空处理`() {
        val idx = DownloadedIndex(object : DownloadedIndex.Store {
            override fun read(): String = throw IllegalStateException("io")
            override fun write(text: String) {}
        })
        assertFalse(idx.contains("a", 1))
        assertTrue(idx.add("a", 1))
    }

    @Test
    fun `store 写入抛异常不影响内存状态`() {
        val idx = DownloadedIndex(object : DownloadedIndex.Store {
            override fun read(): String = ""
            override fun write(text: String) = throw IllegalStateException("ro")
        })
        assertTrue(idx.add("a", 1))
        assertTrue(idx.contains("a", 1))
    }

    @Test
    fun `all 返回快照副本`() {
        val store = MemStore()
        val idx = DownloadedIndex(store)
        idx.add("a", 1)
        val snapshot = idx.all()
        idx.add("b", 2)
        assertEquals(1, snapshot.size)
        assertEquals(2, idx.all().size)
    }
}
