package com.sonnyapp.import

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.sonnyapp.core.dlna.DlnaItem
import com.sonnyapp.core.importer.DownloadedIndex
import com.sonnyapp.core.importer.ImportOutcome
import com.sonnyapp.core.importer.ImportQueue
import com.sonnyapp.core.importer.ImportTask
import com.sonnyapp.core.importer.RateSampler
import com.sonnyapp.device.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 导入管理器：队列调度 + MediaStore/SAF 落盘 + 已下载索引。
 *
 * 挂在 [com.sonnyapp.SonnyApp]（Application 级），生命周期与进程一致 ——
 * 批量下载中切页面/旋转屏幕不丢任务；真正的「息屏不死」由前台服务保障。
 *
 * ## 落盘策略（ADR-1/ADR-5）
 *
 *  默认写 MediaStore `Pictures/SonnyLink`（IS_PENDING 保护 + 完成后全系统可见，
 *  系统相册自动同步、免存储权限）。用户可在设置里选 SAF 目录（高级选项），
 *  相册 tab 只索引默认路径。
 *
 * ## RAW 修复（上游缺陷）
 *
 *  RAW(.ARW) 没有 ORG 原图，下载的是相机转码的 JPEG。上游直接用 `.ARW`
 *  文件名落盘，MIME 与扩展名不一致，部分相册 App 无法打开。
 *  这里统一重命名为 `DSC00437_预览.jpg` 并写 EXIF 备注。
 */
class ImportManager(context: Context) {

    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("sonnyapp_import", 0)

    /** 队列协程作用域：进程级，独立于任何页面。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val queue = ImportQueue(scope, concurrencyProvider = { _concurrency.value })

    /** 任务快照（含进度），UI 直接 collect。 */
    val tasks: StateFlow<List<ImportTask>> = queue.tasks

    /** DLNA 条目引用表（任务 id -> 条目），供 performer 解析下载 URL。 */
    private val itemsById = ConcurrentHashMap<String, DlnaItem>()

    @Volatile
    private var repo: DeviceRepository? = null

    /** 已下载索引（文件名 + 大小），持久化到 filesDir。 */
    val downloadedIndex = DownloadedIndex(FileStore(File(ctx.filesDir, DOWNLOADED_INDEX)))

    private val _concurrency = MutableStateFlow(prefs.getInt(KEY_CONC, 3))
    val concurrency: StateFlow<Int> = _concurrency.asStateFlow()

    private val _saveTreeUri = MutableStateFlow(prefs.getString(KEY_TREE, null))
    val saveTreeUri: StateFlow<String?> = _saveTreeUri.asStateFlow()

    /** 队列是否在干活（含等待中的任务）。 */
    val isActive: Boolean get() = queue.isActive

    /** 关联当前相机会话（连接成功时调用）。 */
    fun attachRepository(r: DeviceRepository) {
        repo = r
    }

    /** 会话结束。剩余排队任务统一失败（重连后用户可重试，索引会去重已成功的）。 */
    fun detachRepository() {
        queue.cancelAll("连接已断开")
        repo = null
    }

    /**
     * 提交导入。已在索引里（之前导过）或队列中的自动跳过。
     *
     * @return 实际进入队列的数量；0 表示全部重复。
     */
    fun enqueue(items: List<DlnaItem>): Int {
        downloadedIndex.ensureLoaded()
        val fresh = items.filterNot {
            downloadedIndex.contains(it.title, indexKeySize(it))
        }
        val tasks = fresh.map { item ->
            ImportTask(
                id = item.id,
                title = saveName(item),
                totalBytes = item.originalSize.takeIf { it > 0 } ?: (item.previewSize()),
                isPreview = !item.downloadIsOriginal,
            )
        }
        for (item in fresh) itemsById[item.id] = item
        val result = queue.submit(tasks)
        if (result.accepted > 0) {
            queue.start(performer)
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ImportService::class.java),
            )
        }
        return result.accepted
    }

    fun clearFinished() = queue.clearFinished()

    fun setConcurrency(n: Int) {
        _concurrency.value = n.coerceIn(1, 8)
        prefs.edit().putInt(KEY_CONC, _concurrency.value).apply()
    }

    fun setSaveTreeUri(uri: String?) {
        _saveTreeUri.value = uri
        prefs.edit().putString(KEY_TREE, uri).apply()
    }

    /** 启动时清掉上次进程死亡留下的 IS_PENDING 半成品。 */
    fun cleanupStalePending() {
        Thread {
            try {
                val resolver = ctx.contentResolver
                val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING}=1"
                val args = arrayOf("Pictures/SonnyLink%")
                val n = resolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, sel, args,
                )
                if (n > 0) Log.i(TAG, "清理残留 IS_PENDING 条目 $n 个")
            } catch (e: Exception) {
                Log.w(TAG, "清理 IS_PENDING 失败: " + e.message)
            }
        }.start()
    }

    // ------------------------------------------------------------ 执行器

    private val performer = ImportQueue.Performer { task, onProgress ->
        val r = repo
            ?: return@Performer ImportOutcome.Failure("未连接相机", retryable = false)
        val item = itemsById[task.id]
            ?: return@Performer ImportOutcome.Failure("条目已失效，请重新浏览", retryable = false)
        val url = item.bestDownloadUrl
            ?: return@Performer ImportOutcome.Failure("没有可用资源", retryable = false)

        val sampler = RateSampler()
        val tree = _saveTreeUri.value
        val written = if (tree != null) {
            downloadToTree(r, item, url, Uri.parse(tree), onProgress)
        } else {
            downloadToMediaStore(r, item, url, onProgress)
        }
        if (written <= 0) {
            // 网络类失败可重试；确定性失败（如 406）不重试
            val retryable = r.isReady
            ImportOutcome.Failure("下载失败", retryable = retryable)
        } else {
            downloadedIndex.add(item.title, indexKeySize(item))
            val note = (if (item.downloadIsOriginal) "原图 " else "预览图 ") +
                ImportQueue.humanSize(written)
            sampler.reset()
            ImportOutcome.Success(written, note)
        }
    }

    // ------------------------------------------------------------ 落盘

    /** 落盘用的显示名。RAW 的转码 JPEG 重命名为 *_预览.jpg。 */
    private fun saveName(item: DlnaItem): String =
        if (item.downloadIsOriginal) item.title
        else item.title.substringBeforeLast('.') + "_预览.jpg"

    /** 索引键里的大小：原图用真实大小；RAW 预览没有原图大小，用 -1 保持稳定。 */
    private fun indexKeySize(item: DlnaItem): Long =
        if (item.originalSize > 0) item.originalSize else -1L

    private fun DlnaItem.previewSize(): Long =
        resources.firstOrNull { it.isPreviewLarge }?.sizeBytes ?: -1L

    private suspend fun downloadToMediaStore(
        r: DeviceRepository,
        item: DlnaItem,
        url: String,
        onProgress: (Long) -> Unit,
    ): Long {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, saveName(item))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SonnyLink")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return -1L
        return try {
            var written = -1L
            resolver.openOutputStream(uri)?.use { out ->
                written = r.downloadTo(url, out, onProgress)
            }
            if (written <= 0) {
                resolver.delete(uri, null, null)
                return -1L
            }
            // 写完回填拍摄时间（相册按 DATE_TAKEN 分组）并解除 pending。
            // 优先用 DLNA item 的 dc:date（相机端权威时间），EXIF 作 fallback ——
            // 某些转码 JPEG 无 EXIF 时也能正确分组。
            values.clear()
            val taken = parseDate(item.date) ?: readDateTaken(uri)
            if (taken > 0) values.put(MediaStore.MediaColumns.DATE_TAKEN, taken)
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            written
        } catch (e: Exception) {
            Log.w(TAG, "保存失败: " + e.message)
            try { resolver.delete(uri, null, null) } catch (e2: Exception) { }
            -1L
        }
    }

    /** DLNA 的 dc:date（ISO 8601）-> epoch 毫秒；解析失败返回 null。 */
    private fun parseDate(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt.parse(iso.substringBefore('.'))?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun readDateTaken(uri: Uri): Long = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val ex = ExifInterface(input)
            val d = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
            if (d != null) {
                val fmt = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                fmt.parse(d)?.time ?: 0L
            } else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }

    private suspend fun downloadToTree(
        r: DeviceRepository,
        item: DlnaItem,
        url: String,
        tree: Uri,
        onProgress: (Long) -> Unit,
    ): Long {
        return try {
            val resolver = ctx.contentResolver
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree),
            )
            val name = uniqueName(tree, saveName(item))
            val fileUri = DocumentsContract.createDocument(resolver, parentDoc, "image/jpeg", name)
                ?: return -1L
            var written = -1L
            resolver.openOutputStream(fileUri, "wt")?.use { out ->
                written = r.downloadTo(url, out, onProgress)
            }
            if (written <= 0) {
                try { resolver.delete(fileUri, null, null) } catch (e: Exception) { }
                -1L
            } else written
        } catch (e: Exception) {
            Log.w(TAG, "写入 SAF 目录失败: " + e.message)
            -1L
        }
    }

    /** 目录里已有同名文件时生成不冲突的名字（a (1).jpg / a (2).jpg …）。 */
    private fun uniqueName(tree: Uri, name: String): String {
        val resolver = ctx.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree),
        )
        val existing = HashSet<String>()
        try {
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cur ->
                while (cur.moveToNext()) existing.add(cur.getString(0) ?: "")
            }
        } catch (e: Exception) {
            // 查不到就当没有重名
        }
        if (!existing.contains(name)) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (i < 10000) {
            val cand = "$base ($i)" + (if (ext.isEmpty()) "" else ".$ext")
            if (!existing.contains(cand)) return cand
            i++
        }
        return name
    }

    // ------------------------------------------------------------ 存储

    /** 文件级原子写：先写临时文件再改名，进程被杀不会留下半截索引。 */
    private class FileStore(private val file: File) : DownloadedIndex.Store {
        override fun read(): String =
            if (file.exists()) file.readText(Charsets.UTF_8) else ""

        override fun write(text: String) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    companion object {
        private const val TAG = "ImportManager"
        private const val KEY_CONC = "downloadConcurrency"
        private const val KEY_TREE = "saveTreeUri"
        private const val DOWNLOADED_INDEX = "downloaded-index.txt"
    }
}
