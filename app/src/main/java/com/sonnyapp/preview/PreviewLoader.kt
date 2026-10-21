package com.sonnyapp.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.sonnyapp.core.dlna.DlnaItem
import com.sonnyapp.device.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 预览加载器：TN -> LRG（默认） -> ORG（显式渐进） 三级。
 *
 * ## 与下载共享缓存（ADR-2）
 *
 *  用户主路径是「点开预览看完再决定下载」。原图预览把 ORG_ 完整拉到
 *  `cacheDir/previews/<key>.jpg` 之后，导入同一张图时**不再从相机拉第二遍** ——
 *  相机 Wi-Fi 是整个链路最贵的资源，同一字节不允许走两趟。
 *  （当前版本导入仍走独立流式下载以保持代码路径简单；共享在「再次预览」时生效，
 *   完整复用留给后续：见方案 §2 P1。）
 *
 * ## 内存与解码
 *
 *  - LRG（a6300 实测 1616x1080）解码到 ARGB_8888 约 7MB，直接进 32MB 内存 LRU；
 *    缩放观感需要全色深，这里**不用** RGB_565。
 *  - 先 inJustDecodeBounds 探尺寸，超过 2048 的按 2 的幂降采样。
 *  - 磁盘缓存 `cacheDir/previews`：LRU 256MB（原图很大，但看完就下是常态，
 *    留 256MB 能覆盖绝大多数「回看」场景；系统紧张时 cacheDir 本身可被清理）。
 */
class PreviewLoader(
    private val repo: DeviceRepository,
    cacheRoot: File,
) {

    private val mem = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val dir = File(cacheRoot, "previews")

    /** LRG 大预览（缩放上限即它的分辨率）。 */
    suspend fun loadLarge(item: DlnaItem): Bitmap? {
        val key = "L|" + key(item)
        mem.get(key)?.let { return it }
        val url = item.previewUrl ?: return null
        val bmp = withContext(Dispatchers.IO) { decodeStream(url, MAX_PREVIEW_PX) } ?: return null
        mem.put(key, bmp)
        return bmp
    }

    /** 原图缓存文件；没有完成缓存返回 null。 */
    fun originalFile(item: DlnaItem): File? {
        if (!item.downloadIsOriginal) return null
        val f = File(dir, key(item) + ".jpg")
        return if (f.exists() && f.length() > 0) f else null
    }

    /**
     * 流式拉取原图到缓存文件，边拉边报进度。返回完成的文件；失败返回 null。
     *
     * 大文件（10~20MB）直接进内存会 OOM，这里始终落盘 —— 后续区域解码（RegionZoomableImage）
     * 也只认文件路径。
     */
    suspend fun fetchOriginal(item: DlnaItem, onProgress: (Long) -> Unit): File? {
        originalFile(item)?.let { return it }
        val url = item.originalUrl ?: return null
        return withContext(Dispatchers.IO) {
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, key(item) + ".part")
            try {
                val written = repo.openStream(url)?.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done)
                        }
                        done
                    }
                } ?: return@withContext null
                if (written <= 0) {
                    tmp.delete()
                    return@withContext null
                }
                val fin = File(dir, key(item) + ".jpg")
                if (!tmp.renameTo(fin)) {
                    tmp.copyTo(fin, overwrite = true)
                    tmp.delete()
                }
                trimDir()
                fin
            } catch (e: Exception) {
                tmp.delete()
                null
            }
        }
    }

    /** 丢弃某条目的原图缓存（空间紧张 / 内容过期）。 */
    fun evictOriginal(item: DlnaItem) {
        File(dir, key(item) + ".jpg").delete()
    }

    fun evictAll() {
        mem.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun key(item: DlnaItem): String =
        Integer.toHexString((item.title + "|" + item.originalSize).hashCode())

    private suspend fun decodeStream(url: String, maxPx: Int): Bitmap? {
        val bytes = repo.openStream(url)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun sampleFor(w: Int, h: Int, target: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        var ww = w
        var hh = h
        while (ww / 2 >= target && hh / 2 >= target) {
            ww /= 2
            hh /= 2
            s *= 2
        }
        return s
    }

    /** 原图缓存裁剪到 256MB，按最后修改时间淘汰。 */
    private fun trimDir(maxBytes: Long = 256L * 1024 * 1024) {
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        /** LRG 解码上限边长：2048 覆盖 1616x1080 全尺寸，再大就是浪费。 */
        private const val MAX_PREVIEW_PX = 2048
    }
}
