package com.sonnyapp.device

import android.content.Context
import android.net.Network
import android.util.Log
import com.sonnyapp.camera.CameraNetwork
import com.sonnyapp.camera.DlnaClient
import com.sonnyapp.camera.WifiState
import com.sonnyapp.core.dlna.DidlResult
import com.sonnyapp.core.dlna.DlnaItem
import com.sonnyapp.core.dlna.DlnaPaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * 设备仓库：采用系统已连的相机热点 -> DLNA 发现 -> 浏览 -> 下载字节流。
 *
 * 由上游 GalleryRepository 瘦身而来：下载落盘逻辑移交 ImportManager（队列化），
 * 这里只保留「会话能力」——发现、浏览、开流、下载一段字节。
 *
 * 相机一次只能跑一个模式，本仓库只关心「发送到智能手机」的 DLNA 模式。
 */
class DeviceRepository(private val context: Context) {

    private val net = CameraNetwork(context)
    private var client: DlnaClient? = null

    /** 逐行记录，供诊断日志展示。 */
    private val log = StringBuilder()

    private fun note(msg: String) {
        Log.i(TAG, msg)
        synchronized(log) { log.append(msg).append('\n') }
    }

    fun logText(): String = synchronized(log) { log.toString() }

    val network: Network? get() = net.network
    val isReady: Boolean get() = client?.isReady == true
    fun wifiState(): WifiState = net.wifiState()
    val friendlyName: String get() = client?.friendlyName ?: ""

    /**
     * 用**系统已经连上**的 Wi-Fi（用户在系统设置里连的相机热点）。
     * 不弹系统对话框，也不需要 SSID/密码。
     */
    suspend fun connectViaSystemWifi(): String? = withContext(Dispatchers.IO) {
        synchronized(log) { log.setLength(0) }
        note("[1/3] 采用系统已连接的 Wi-Fi")
        val n = net.adoptSystemWifi()
        if (n == null) {
            note("      没有可用的 Wi-Fi 连接")
            return@withContext "没有找到已连接的 Wi-Fi。\n请先在系统设置里连上相机的热点，再回来。"
        }
        note("      Network = " + n)
        afterNetwork(n)
    }

    /** 连热点 + DLNA 发现。返回错误信息，成功返回 null。 */
    suspend fun connect(ssid: String, passphrase: String?): String? = withContext(Dispatchers.IO) {
        synchronized(log) { log.setLength(0) }
        note("[1/3] 连接相机热点 " + ssid)
        try {
            net.connect(ssid, passphrase)
            net.acquireMulticastLock()
        } catch (e: Exception) {
            note("      失败: " + (e.message ?: ""))
            return@withContext "连接相机热点失败：" + (e.message ?: e.toString())
        }
        val n = net.network ?: return@withContext "相机网络句柄为空"
        afterNetwork(n)
    }

    /**
     * 手动输入设备描述地址直连（跳过 SSDP）。
     *
     * 场景：路由器/热点屏蔽组播导致 SSDP 发不出去，但 HTTP 可达；
     * 也用于无相机环境下用模拟服务器联调。
     */
    suspend fun connectManual(location: String): String? = withContext(Dispatchers.IO) {
        synchronized(log) { log.setLength(0) }
        note("[1/2] 手动直连 " + location)
        val n = net.adoptSystemWifi()
        if (n == null) {
            return@withContext "没有找到已连接的 Wi-Fi。\n请先连上相机的热点，或确认手机与相机在同一网络。"
        }
        note("[2/2] 抓取设备描述")
        val c = DlnaClient(n)
        val ok = try {
            c.discoverAtLocation(location)
        } catch (e: Exception) {
            note("      异常: " + (e.message ?: ""))
            false
        }
        if (!ok) {
            return@withContext "无法从该地址读取 DLNA 设备描述。\n" +
                "请检查地址（形如 http://192.168.122.1:64321/dd.xml）。"
        }
        note("      设备 = " + c.friendlyName)
        note("      控制地址 = " + c.controlUrl)
        client = c
        null
    }

    /** 网络就绪后做 DLNA 发现。返回错误信息，成功返回 null。 */
    private fun afterNetwork(n: Network): String? {
        note("[2/3] SSDP 发现 DLNA 媒体服务器")
        val c = DlnaClient(n)
        val ok = try {
            c.discover()
        } catch (e: Exception) {
            note("      异常: " + (e.message ?: ""))
            false
        }
        if (!ok) {
            note("      未发现媒体服务（相机可能不在「发送到智能手机」模式）")
            return "没有发现 DLNA 媒体服务。\n请确认相机停留在「发送到智能手机」界面。"
        }
        note("      设备 = " + c.friendlyName)
        note("      控制地址 = " + c.controlUrl)
        note("[3/3] 读取支持的格式")
        val protos = try { c.readProtocolInfo() } catch (e: Exception) { emptyList() }
        note("      格式数 = " + protos.size)
        client = c
        return null
    }

    /**
     * 完整读取一个容器，必要时自动翻页。
     *
     * 不翻页的后果是**静默丢图**：实测 a7R II 的日期文件夹 TotalMatches=220，
     * 单次请求只回 50 项。单页失败时保留已取到的部分并停止翻页，不抛异常。
     */
    suspend fun browseAll(
        objectId: String = "0",
        requestCount: Int = DlnaPaging.REQUEST_COUNT,
    ): DidlResult = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext DidlResult()
        var pages = 0
        val r = DlnaPaging.browseAll(requestCount) { start, count ->
            pages++
            try {
                c.browse(objectId, start, count)
            } catch (e: Exception) {
                note("Browse " + objectId + " 第 " + pages + " 页失败: " + (e.message ?: ""))
                DidlResult()
            }
        }
        note(
            "Browse " + objectId + " -> " + r.items.size + " 项 / 共 " + r.totalMatches +
                "（" + pages + " 页）"
        )
        r
    }

    /** 打开一个资源的字节流（缩略图/预览/下载共用）。调用方负责关闭。 */
    suspend fun openStream(url: String): InputStream? = withContext(Dispatchers.IO) {
        try {
            client?.openStream(url)
        } catch (e: Exception) {
            null
        }
    }

    /** 下载到输出流，带进度回调。返回写入字节数；失败返回 -1。 */
    suspend fun downloadTo(
        url: String,
        out: OutputStream,
        onProgress: (Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext -1L
        try {
            c.download(url, out) { done, _ -> onProgress(done) }
        } catch (e: Exception) {
            -1L
        }
    }

    fun disconnect() {
        client = null
        try { net.release() } catch (e: Exception) { }
    }

    companion object {
        private const val TAG = "DeviceRepository"
    }
}
