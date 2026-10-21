package com.sonnyapp.core.importer

/**
 * 「已下载」去重索引：key = 文件名 + 字节数。
 *
 * ## 为什么不用 DLNA itemId
 *
 * itemId 只在**当前相机会话**内稳定 —— 相机重启或重新进入「发送到智能手机」后
 * 可能变化；而文件名 + 大小在卡上是稳定的，跨会话、跨重装都能对上。
 *
 * ## 存储
 *
 * 纯文本，每行 `size<TAB>title`，由注入的 [Store] 负责读写（app 层落到 filesDir）。
 * 一个 5000 张照片的库约 150 KB，全量重写完全可接受；写入放在 add() 内同步完成，
 * 进程被杀也不会丢（导入完成 -> 索引落盘是同一时刻）。
 */
class DownloadedIndex(private val store: Store) {

    /** 持久化接口。实现需保证 read/write 的整体性（文件级原子替换由实现方负责）。 */
    interface Store {
        fun read(): String
        fun write(text: String)
    }

    private val keys = HashSet<String>()
    private var loaded = false

    /** 惰性加载（首次查询时）。重复调用无副作用。 */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = try {
            store.read()
        } catch (e: Exception) {
            ""
        }
        if (text.isEmpty()) return
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            keys.add(line.substring(tab + 1) + KEY_SEP + line.substring(0, tab))
        }
    }

    @Synchronized
    fun contains(title: String, sizeBytes: Long): Boolean {
        ensureLoaded()
        return key(title, sizeBytes) in keys
    }

    /** 记录一条已下载。返回 true 表示是新增（false = 之前就有）。 */
    @Synchronized
    fun add(title: String, sizeBytes: Long): Boolean {
        ensureLoaded()
        val k = key(title, sizeBytes)
        if (!keys.add(k)) return false
        persist()
        return true
    }

    /** 移除一条（相册里删除后调用，保持徽标与相册一致）。 */
    @Synchronized
    fun remove(title: String, sizeBytes: Long) {
        ensureLoaded()
        if (keys.remove(key(title, sizeBytes))) persist()
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return keys.size
    }

    @Synchronized
    fun all(): Set<String> {
        ensureLoaded()
        return HashSet(keys)
    }

    private fun persist() {
        val sb = StringBuilder()
        for (k in keys) {
            val sep = k.lastIndexOf(KEY_SEP)
            if (sep <= 0) continue
            sb.append(k.substring(sep + 1)).append('\t').append(k.substring(0, sep)).append('\n')
        }
        try {
            store.write(sb.toString())
        } catch (e: Exception) {
            // 落盘失败不影响本次会话内存状态
        }
    }

    companion object {
        private const val KEY_SEP = "¦" // 文件名/数字里都不会出现

        fun key(title: String, sizeBytes: Long): String = title + KEY_SEP + sizeBytes
    }
}
