package com.sonnyapp.device

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sonnyapp.SonnyApp
import com.sonnyapp.core.dlna.DlnaItem
import com.sonnyapp.core.importer.ImportTask
import com.sonnyapp.diag.Diagnostics
import com.sonnyapp.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 列表筛选模式。默认仅显示原图；RAW 预览图数量过多会影响滚动性能。 */
enum class FilterMode { ORIGINALS_ONLY, ALL }

/** 按拍摄日期分的一组。 */
data class DayGroup(val date: String, val label: String, val items: List<DlnaItem>)

/**
 * 设备页视图模型：连接相机、浏览卡内照片、缩略图缓存、多选。
 *
 * 由上游 GalleryViewModel 改造：**下载动作全部移交 ImportManager**（Application 级队列），
 * 这里只观察任务进度并映射成网格角标。缩略图管线（三级缓存 + 并发闸门 + 防抖）
 * 原样保留 —— 那是实机调优过的部分。
 *
 * ## 性能要点（保留自上游 + 新增）
 *
 *  - 内存缩略图 LruCache 24MB，**必须重写 sizeOf**（否则永不淘汰）
 *  - 磁盘缓存 `cacheDir/thumbs`：上游没有上限，长年累月会涨到几百 MB ——
 *    新增 **64MB LRU 裁剪**（按最后修改时间淘汰）
 *  - 解码先探尺寸再 inSampleSize 降采样 + RGB_565
 *  - 网格 10 路并发闸门 + 90ms 防抖：带宽留给停留位置
 *  - 首帧同步取内存缓存（peekThumb），避免"先闪灰块再出图"
 */
class DeviceViewModel(app: Application) : AndroidViewModel(app) {

    private val sonnyApp = app as SonnyApp
    private val repo = DeviceRepository(app)
    private val prefs = app.getSharedPreferences("sonnyapp", 0)

    /** 导入队列（Application 级，跨页面存活）。 */
    val imports = sonnyApp.imports

    /** 预览加载器（LRG 内存缓存 + 原图磁盘缓存）。 */
    val previewLoader by lazy {
        com.sonnyapp.preview.PreviewLoader(repo, app.cacheDir)
    }

    /**
     * 流式拉取原图到预览缓存，边拉边报进度；完成后把文件路径回调给 UI。
     * 在 ViewModel 作用域里跑：切页自动取消（LaunchedEffect 同 key 也会取消上一次）。
     */
    fun fetchOriginal(
        item: com.sonnyapp.core.dlna.DlnaItem,
        onProgress: (Long) -> Unit,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val f = previewLoader.fetchOriginal(item) { onProgress(it) }
            onDone(f?.absolutePath)
        }
    }

    private val _status = MutableStateFlow("未连接")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 全部条目（未筛选，供统计用）。 */
    private val _allFiles = MutableStateFlow<List<DlnaItem>>(emptyList())
    val allFiles: StateFlow<List<DlnaItem>> = _allFiles.asStateFlow()

    /** 按日期分组后的列表。 */
    private val _groups = MutableStateFlow<List<DayGroup>>(emptyList())
    val groups: StateFlow<List<DayGroup>> = _groups.asStateFlow()

    private val _filter = MutableStateFlow(FilterMode.ORIGINALS_ONLY)
    val filter: StateFlow<FilterMode> = _filter.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** 已导入（在本地相册里）的条目 id 集合 —— 网格 ✓ 徽标。 */
    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    /** 导入任务进度，key = 条目 id —— 网格进度环。 */
    val importProgress: StateFlow<Map<String, ImportTask>> = imports.tasks
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _wifi = MutableStateFlow(com.sonnyapp.camera.WifiState())
    val wifiState: StateFlow<com.sonnyapp.camera.WifiState> = _wifi.asStateFlow()

    val repository: DeviceRepository get() = repo

    /** 已连接相机的 DLNA 名称（未连接为空串）。 */
    val deviceName: String get() = repo.friendlyName

    init {
        // 导入任务变化时刷新徽标（完成 -> 打勾）
        viewModelScope.launch {
            imports.tasks.collect { refreshDownloaded() }
        }
    }

    /**
     * 进入连接页时自动判断：当前 Wi-Fi 为相机热点则直接连接，无需手动操作。
     * 否则仅刷新状态，由界面引导前往系统设置。
     */
    fun autoConnect() {
        if (_connected.value || _busy.value) return
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) { repo.wifiState() }
            _wifi.value = st
            if (st.looksLikeCamera) connectViaSystemWifi()
        }
    }

    /** 只刷新 Wi-Fi 状态，不连接。 */
    fun refreshWifiState() {
        viewModelScope.launch {
            _wifi.value = withContext(Dispatchers.IO) { repo.wifiState() }
        }
    }

    private val _thumbStats = MutableStateFlow("")
    val thumbStats: StateFlow<String> = _thumbStats.asStateFlow()

    private val thumbGate = Semaphore(10)

    /** 缩略图内存缓存，按字节数限制（24 MB）。 */
    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val thumbDir = java.io.File(app.cacheDir, "thumbs")

    @Volatile private var hitsMem = 0L
    @Volatile private var hitsDisk = 0L
    @Volatile private var hitsNet = 0L
    @Volatile private var lastNetMs = 0L

    val savedSsid: String get() = prefs.getString("ssid", "") ?: ""
    val savedPass: String get() = prefs.getString("pass", "") ?: ""

    // ------------------------------------------------------------ 连接

    /** 默认路径：相机热点已在系统设置中连接，App 直接接管该 Wi-Fi。 */
    fun connectViaSystemWifi() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "正在检查已连接的 Wi-Fi …"
            val err = repo.connectViaSystemWifi()
            if (err != null) {
                _status.value = err
                _connected.value = false
                _busy.value = false
                return@launch
            }
            onConnected()
        }
    }

    /** 手动路径：在 App 里输入 SSID/密码（高级备用）。 */
    fun connect(ssid: String, pass: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "正在连接 " + ssid + " …"
            prefs.edit().putString("ssid", ssid).putString("pass", pass).apply()
            val err = repo.connect(ssid, pass.ifEmpty { null })
            if (err != null) {
                _status.value = err
                _connected.value = false
                _busy.value = false
                return@launch
            }
            onConnected()
        }
    }

    private fun onConnected() {
        _connected.value = true
        _status.value = "已连接 " + repo.friendlyName
        imports.attachRepository(repo)
        // 先清忙再调 loadAll（loadAll 开头是"忙就返回"，顺序反了会静默什么都不做）
        _busy.value = false
        loadAll()
    }

    fun disconnect() {
        imports.detachRepository()
        viewModelScope.launch {
            repo.disconnect()
            _connected.value = false
            _allFiles.value = emptyList()
            _groups.value = emptyList()
            _selected.value = emptySet()
            _downloadedIds.value = emptySet()
            thumbCache.evictAll()
            _status.value = "已断开"
        }
    }

    // ------------------------------------------------------------ 读取

    fun loadAll() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val all = ArrayList<DlnaItem>()
                var containers: List<DlnaItem> = emptyList()
                var id = "0"
                var depth = 0
                while (depth < 4) {
                    val r = repo.browseAll(id)
                    all.addAll(r.files)
                    containers = r.containers
                    if (containers.size != 1 || r.files.isNotEmpty()) break
                    id = containers[0].id
                    depth++
                }
                // 并行读取日期文件夹：相机每次 Browse 要 200~500ms，6 路并发约 1 秒。
                val total = containers.size
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                val gate = Semaphore(6)
                val lists = coroutineScope {
                    containers.map { c ->
                        async(Dispatchers.IO) {
                            val files = gate.withPermit { repo.browseAll(c.id).files }
                            _status.value =
                                "读取目录 " + done.incrementAndGet() + " / " + total + " …"
                            files
                        }
                    }.awaitAll()
                }
                for (l in lists) all.addAll(l)
                val sorted = all.sortedWith(compareByDescending { it.date })
                _allFiles.value = sorted
                regroup()
            } catch (e: Exception) {
                _status.value = "读取失败：" + (e.message ?: "")
            } finally {
                _busy.value = false
            }
        }
    }

    fun setFilter(mode: FilterMode) {
        _filter.value = mode
        regroup()
    }

    /** 按筛选条件重建「按拍摄日期分组」的列表。 */
    private fun regroup() {
        val src = when (_filter.value) {
            FilterMode.ORIGINALS_ONLY -> _allFiles.value.filter { it.canDownloadOriginal }
            FilterMode.ALL -> _allFiles.value
        }
        val byDay = LinkedHashMap<String, MutableList<DlnaItem>>()
        for (item in src) {
            val day = Dates.dayKey(item.date)
            byDay.getOrPut(day) { ArrayList() }.add(item)
        }
        _groups.value = byDay.entries
            .sortedByDescending { it.key }
            .map { DayGroup(it.key, Dates.prettyDay(it.key), it.value) }
        refreshDownloaded()
        val orig = _allFiles.value.count { it.canDownloadOriginal }
        _status.value = "已显示 " + src.size + " 张 · 全部 " + _allFiles.value.size +
            " 张（可下原图 " + orig + "）"
    }

    private fun refreshDownloaded() {
        val idx = imports.downloadedIndex
        _downloadedIds.value = _allFiles.value
            .filter { idx.contains(it.title, indexKeySize(it)) }
            .map { it.id }
            .toSet()
    }

    /** 索引键里的大小：原图用真实大小；RAW 无原图用 -1（与 ImportManager 一致）。 */
    fun indexKeySize(item: DlnaItem): Long =
        if (item.originalSize > 0) item.originalSize else -1L

    // ------------------------------------------------------------ 选择

    fun toggle(id: String) {
        val cur = _selected.value
        _selected.value = if (cur.contains(id)) cur - id else cur + id
    }

    fun selectAll() {
        _selected.value = _groups.value.flatMap { it.items }.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** 当前选中的条目（提交导入用）。 */
    fun selectedItems(): List<DlnaItem> {
        val ids = _selected.value
        return _groups.value.flatMap { it.items }.filter { ids.contains(it.id) }
    }

    /**
     * 提交选中项导入。返回进入队列的数量；重复的会被 ImportManager 去重。
     */
    fun enqueueSelected(): Int {
        val n = imports.enqueue(selectedItems())
        if (n > 0) _selected.value = emptySet()
        return n
    }

    /** 单张导入（预览页用）。 */
    fun enqueueOne(item: DlnaItem): Int = imports.enqueue(listOf(item))

    // ------------------------------------------------------------ 诊断

    fun buildDiag(): String {
        val w = _wifi.value
        val all = _allFiles.value
        val extras = listOf(
            "状态" to _status.value,
            "DLNA 设备" to (if (repo.friendlyName.isEmpty()) "(未知)" else repo.friendlyName),
            "Wi-Fi SSID" to (w.ssid ?: if (w.connected) "(系统未提供)" else "(未连接)"),
            "判为相机热点" to if (w.looksLikeCamera) "是" else "否",
            "照片总数" to all.size.toString(),
            "可下原图" to all.count { it.canDownloadOriginal }.toString(),
            "当前显示" to _groups.value.sumOf { it.items.size }.toString(),
            "筛选模式" to _filter.value.name,
            "已导入" to imports.downloadedIndex.size().toString(),
            "缩略图缓存" to thumbStats(),
            "保存位置" to (imports.saveTreeUri.value?.let { "自选目录" } ?: "系统相册 Pictures/SonnyLink"),
            "下载并发" to imports.concurrency.value.toString(),
        )
        return Diagnostics.build(getApplication(), repo.logText(), extras)
    }

    // ------------------------------------------------------------ 缩略图

    /** 同步查内存缓存。UI 第一帧就用它拿图，避免"先闪灰块再出图"。 */
    fun peekThumb(id: String): Bitmap? = thumbCache.get(id)

    /**
     * 取缩略图。三级缓存：内存 -> 磁盘 -> 网络。
     * 磁盘缓存是关键 —— 相机 Wi-Fi 只有 1~3 MB/s，同一个位置重拉要几秒。
     */
    suspend fun loadThumb(item: DlnaItem): Bitmap? {
        thumbCache.get(item.id)?.let {
            hitsMem++
            return it
        }
        return thumbGate.withPermit {
            thumbCache.get(item.id)?.let { return@withPermit it }

            val f = thumbFile(item.id)
            if (f.exists() && f.length() > 0) {
                val cached = withContext(Dispatchers.IO) {
                    try {
                        val o = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        BitmapFactory.decodeFile(f.absolutePath, o)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (cached != null) {
                    thumbCache.put(item.id, cached)
                    hitsDisk++
                    return@withPermit cached
                }
            }

            val url = item.thumbnailUrl ?: item.previewUrl ?: return@withPermit null
            val t0 = System.currentTimeMillis()
            val bmp = withContext(Dispatchers.IO) { decodeThumb(url) }
            lastNetMs = System.currentTimeMillis() - t0
            if (bmp != null) {
                thumbCache.put(item.id, bmp)
                hitsNet++
                withContext(Dispatchers.IO) {
                    try {
                        java.io.FileOutputStream(f).use {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 82, it)
                        }
                        trimThumbDir()
                    } catch (e: Exception) {
                        // 写缓存失败不影响显示
                    }
                }
            }
            bmp
        }
    }

    /** 缩略图缓存文件：用 id 的 hash 当文件名，避免非法字符。 */
    private fun thumbFile(id: String): java.io.File {
        if (!thumbDir.exists()) thumbDir.mkdirs()
        return java.io.File(thumbDir, Integer.toHexString(id.hashCode()) + ".jpg")
    }

    /**
     * 磁盘缓存裁剪到 64MB（按最后修改时间从旧到新淘汰）。
     *
     * 上游版本没有上限 —— 缩略图会永久留在 cacheDir，虽然系统紧张时会整体清理，
     * 但正常使用的机器上就是「只涨不降」。写入后顺手裁剪，成本一次 ls + 排序。
     */
    private fun trimThumbDir(maxBytes: Long = 64L * 1024 * 1024) {
        val files = thumbDir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val byAge = files.sortedBy { it.lastModified() }
        for (f in byAge) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    /** 缩略图统计，用于诊断「到图慢」还是「渲染慢」。 */
    fun thumbStats(): String {
        val disk = try { thumbDir.list()?.size ?: 0 } catch (e: Exception) { 0 }
        return "缩略图 内存 " + hitsMem + " · 磁盘 " + hitsDisk + " · 网络 " + hitsNet +
            " · 上次网络 " + lastNetMs + "ms · 磁盘已有 " + disk + " 张"
    }

    /**
     * 解码缩略图。必须先探明尺寸、再按 inSampleSize 降采样 ——
     * 直接 decodeStream 会按原始尺寸解成 ARGB_8888（LRG 1616x1080 一张 7MB），
     * 十几个格子同时解会打满内存带宽，表现为"滑动卡顿、像没有高刷"。
     */
    private suspend fun decodeThumb(url: String): Bitmap? {
        return try {
            val bytes = repo.openStream(url)?.use { it.readBytes() } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, THUMB_TARGET_PX)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }

    /** 取能让两边都不小于 target 的最大 2 的幂。 */
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

    override fun onCleared() {
        repo.disconnect()
        super.onCleared()
    }

    companion object {
        /** 缩略图解码目标边长（像素）。网格格子约 116dp ≈ 320px，256 足够。 */
        private const val THUMB_TARGET_PX = 256

        fun humanSize(bytes: Long): String = when {
            bytes <= 0 -> "?"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        }
    }
}
