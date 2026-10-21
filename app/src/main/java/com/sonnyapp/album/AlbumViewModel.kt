package com.sonnyapp.album

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sonnyapp.SonnyApp
import com.sonnyapp.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 相册里按拍摄日期分的一组。 */
data class AlbumDay(val date: String, val label: String, val items: List<AlbumRepository.LocalPhoto>)

/**
 * 相册视图模型：查询 / 分组 / 删除 / 分享。
 *
 * 刷新来源两个：进入页面主动 query + MediaStore ContentObserver 推送
 * （导入完成 1s 内新照片出现）。删除同时同步 DownloadedIndex ——
 * 相册删了，设备页就不该再打 ✓（语义：✓ = 「在相册里」而不是「导过」）。
 */
class AlbumViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AlbumRepository(app)
    private val imports = (app as SonnyApp).imports

    private val _photos = MutableStateFlow<List<AlbumRepository.LocalPhoto>>(emptyList())
    val photos: StateFlow<List<AlbumRepository.LocalPhoto>> = _photos.asStateFlow()

    private val _days = MutableStateFlow<List<AlbumDay>>(emptyList())
    val days: StateFlow<List<AlbumDay>> = _days.asStateFlow()

    private val _totalSize = MutableStateFlow(0L)
    val totalSize: StateFlow<Long> = _totalSize.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refresh()
        // MediaStore 变化推送（导入完成即时反映）
        viewModelScope.launch {
            repo.observe().collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = _photos.value.isEmpty()
            val list = withContext(Dispatchers.IO) { repo.query() }
            _photos.value = list
            _totalSize.value = list.sumOf { it.size }
            _days.value = groupByDay(list)
            _busy.value = false
        }
    }

    private fun groupByDay(list: List<AlbumRepository.LocalPhoto>): List<AlbumDay> {
        val byDay = LinkedHashMap<String, MutableList<AlbumRepository.LocalPhoto>>()
        for (p in list) {
            val day = Dates.dayKey(p.dateMillis)
            byDay.getOrPut(day) { ArrayList() }.add(p)
        }
        return byDay.entries
            .sortedByDescending { it.key }
            .map { AlbumDay(it.key, Dates.prettyDay(it.key), it.value) }
    }

    /**
     * 请求删除（系统回收站确认流）。
     *
     * @return true = 已发出系统请求（或 API 29 直接删除成功）；
     *         false = 失败（无权限等）。
     */
    fun requestTrash(photo: AlbumRepository.LocalPhoto): Boolean {
        val pending = repo.trashRequest(photo)
        if (pending != null) {
            return try {
                pending.send()
                // 确认后的删除由系统执行，ContentObserver 推送回来；
                // 这里先同步索引与本地列表（UI 即时反馈）。
                onPhotoRemoved(photo)
                true
            } catch (e: Exception) {
                deleteDirect(photo)
            }
        }
        return deleteDirect(photo)
    }

    /** 回退路径：直接删除 + 同步索引。 */
    fun deleteDirect(photo: AlbumRepository.LocalPhoto): Boolean {
        val ok = repo.delete(photo)
        if (ok) onPhotoRemoved(photo)
        return ok
    }

    /** UI 确认删除成功（系统回收站流完成后）调用；同步索引与本地列表。 */
    fun onPhotoRemoved(photo: AlbumRepository.LocalPhoto) {
        // 索引键的 size 用导入时的 DLNA 大小，本地文件大小几乎一致（JPEG 字节相同），
        // 但 RAW 预览的键是 -1 —— 用文件名前缀匹配兜底移除。
        imports.downloadedIndex.remove(photo.name, photo.size)
        if (photo.isPreview) {
            // RAW 预览：键是 (原始标题, -1)，从文件名还原 DSC00437 -> DSC00437.ARW 不可行，
            // 保守做法：重建索引里与该文件名前缀一致的条目 —— 规模小（<几千），直接扫描。
            val base = photo.name.substringBefore("_预览")
            val snapshot = imports.downloadedIndex.all()
            for (key in snapshot) {
                val title = key.substringBeforeLast('¦')
                if (title.substringBeforeLast('.') == base) {
                    val size = key.substringAfterLast('¦').toLongOrNull() ?: continue
                    imports.downloadedIndex.remove(title, size)
                }
            }
        }
        _photos.value = _photos.value.filterNot { it.id == photo.id }
        _days.value = groupByDay(_photos.value)
        _totalSize.value = _photos.value.sumOf { it.size }
    }

    /** 分享：构造 ACTION_SEND 多选/单张 Intent（文件路径直给，跨应用可读）。 */
    fun shareIntent(uris: List<Uri>): Intent? {
        if (uris.isEmpty()) return null
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
