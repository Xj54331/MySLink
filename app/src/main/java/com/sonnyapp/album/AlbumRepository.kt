package com.sonnyapp.album

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * 相册仓库：查询本 App 导入到系统相册的照片（Pictures/SonnyLink）。
 *
 * ## ADR-1：MediaStore 是唯一事实源
 *
 *  不建私有数据库 —— 写入端本来就落 MediaStore（系统相册天然同步、免存储权限、
 *  卸载不丢照片），这里只做查询端。查自己的贡献文件在 API 29+ **零权限**。
 *
 * ## 分组依据
 *
 *  优先 DATE_TAKEN（导入时已从 EXIF 回填）；没有就退 DATE_ADDED。
 *  RAW 的转码预览以 `_预览.jpg` 命名，isPreview 由文件名判定。
 */
class AlbumRepository(private val context: Context) {

    data class LocalPhoto(
        val id: Long,
        val uri: Uri,
        val name: String,
        val dateMillis: Long,
        val size: Long,
        val width: Int,
        val height: Int,
        val path: String?,
        val isPreview: Boolean,
    ) {
        /** 区域解码需要文件路径；SAF/异常情况下为 null（调用方回退整图解码）。 */
        val filePath: String? get() = path?.let { if (File(it).canRead()) it else null }
    }

    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** 查询全部导入的照片（新 -> 旧）。 */
    fun query(): List<LocalPhoto> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATA,
        )
        val out = ArrayList<LocalPhoto>()
        try {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("Pictures/SonnyLink%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val takenIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val addedIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val wIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val hIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val pathIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (c.moveToNext()) {
                    val taken = c.getLong(takenIdx).takeIf { it > 0 }
                        ?: c.getLong(addedIdx) * 1000
                    val name = c.getString(nameIdx) ?: continue
                    out.add(
                        LocalPhoto(
                            id = c.getLong(idIdx),
                            uri = ContentUris.withAppendedId(collection, c.getLong(idIdx)),
                            name = name,
                            dateMillis = taken,
                            size = c.getLong(sizeIdx),
                            width = c.getInt(wIdx),
                            height = c.getInt(hIdx),
                            path = c.getString(pathIdx),
                            isPreview = name.contains("_预览"),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询相册失败: " + e.message)
        }
        return out
    }

    /** ContentObserver -> 刷新信号（任一媒体变化触发一次）。 */
    fun observe(): Flow<Unit> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        var lastAt = 0L
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val now = System.currentTimeMillis()
                if (now - lastAt < 500) return // 去抖：批量导入完成会连发
                lastAt = now
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        awaitClose {
            try { context.contentResolver.unregisterContentObserver(observer) } catch (e: Exception) { }
        }
    }

    /**
     * 移至系统回收站。
     *
     * 自己导入的文件（own contribution）可以直接删；但 App 卸载重装后归属变化，
     * 此时需要走 [MediaStore.createTrashRequest] 的系统确认流 —— 这里统一走确认流，
     * 语义安全且任何归属状态都能工作。
     */
    /**
     * 系统回收站请求。
     *
     * **注意**：`MediaStore.createTrashRequest` 返回的是 [android.app.PendingIntent]
     * （不是 Intent），调用 `send()` 后系统弹确认框、确认即移入回收站。
     * 自己导入的文件（own contribution）可以直接删；但 App 卸载重装后归属变化，
     * 走系统确认流在任何归属状态下都能工作。
     *
     * @return 可发送的 PendingIntent；API 29 或失败返回 null（调用方回退直接删除）。
     */
    fun trashRequest(photo: LocalPhoto): android.app.PendingIntent? = try {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            MediaStore.createTrashRequest(
                context.contentResolver, listOf(photo.uri), false,
            )
        } else {
            null
        }
    } catch (e: Exception) {
        Log.w(TAG, "createTrashRequest 失败: " + e.message)
        null
    }

    /** 直接删除（trashIntent 不可用时的回退；对自己的贡献文件有效）。 */
    fun delete(photo: LocalPhoto): Boolean = try {
        context.contentResolver.delete(photo.uri, null, null) > 0
    } catch (e: Exception) {
        false
    }

    /** 启动清理：删除本目录下残留的 IS_PENDING 半成品（进程被杀留下的）。 */
    fun cleanupPending() {
        try {
            val n = context.contentResolver.delete(
                collection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING}=1",
                arrayOf("Pictures/SonnyLink%"),
            )
            if (n > 0) Log.i(TAG, "相册清理残留 pending $n 条")
        } catch (e: Exception) {
            Log.w(TAG, "清理 pending 失败: " + e.message)
        }
    }

    /** 占用空间统计。 */
    fun stats(photos: List<LocalPhoto>): Pair<Int, Long> =
        photos.size to photos.sumOf { it.size }

    companion object {
        private const val TAG = "AlbumRepository"
    }
}
