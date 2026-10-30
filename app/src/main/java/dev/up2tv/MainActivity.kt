package dev.up2tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var address: TextView
    private lateinit var pin: TextView
    private lateinit var qr: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var toggle: Button
    private lateinit var packageList: LinearLayout
    private lateinit var packageCount: TextView
    private lateinit var connectivity: ConnectivityManager
    private val directory get() = File(filesDir, "packages")
    private var server: UploadServer? = null
    private var listeningAddress: String? = null
    private var awaitingPermission = false
    private var pendingInstall: File? = null
    private var renderedPackageBusy: Boolean? = null
    private var registered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) = checkNetwork()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = checkNetwork()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        address = findViewById(R.id.address)
        pin = findViewById(R.id.pin)
        qr = findViewById(R.id.qr)
        progress = findViewById(R.id.progress)
        toggle = findViewById(R.id.toggle)
        packageList = findViewById(R.id.package_list)
        packageCount = findViewById(R.id.package_count)
        connectivity = getSystemService(ConnectivityManager::class.java)
        if (!directory.isDirectory && !directory.mkdirs()) {
            status.text = "无法创建接收目录，请检查电视存储空间。"
            toggle.isEnabled = false
        }
        directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        val savedPackages = UploadServer.listPackages(directory)
        if (savedPackages.isNotEmpty()) {
            status.text = "已保存 ${savedPackages.size} 个安装包"
            detail.text = "可继续上传，或选择安装 / 删除"
        }
        renderPackages()
        toggle.setOnClickListener {
            if (server == null) startReceiving() else confirmStop { stopReceiving() }
        }
        toggle.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
            registered = true
        } catch (_: RuntimeException) {
            // 部分固件不提供网络回调；开启接收时仍会检查实际地址。
        }
        if (server == null && toggle.isEnabled) startReceiving()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingPermission) {
            awaitingPermission = false
            val file = pendingInstall
            pendingInstall = null
            if (file != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) launchInstaller(file)
            else status.text = "尚未允许安装。可在系统设置中授权 Up2TV 后重试。"
        }
    }

    override fun onStop() {
        stopReceiving()
        if (registered) {
            connectivity.unregisterNetworkCallback(networkCallback)
            registered = false
        }
        super.onStop()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = confirmStop { super.onBackPressed() }

    private fun confirmStop(action: () -> Unit) {
        if (server?.state?.busy != true) {
            action()
            return
        }
        AlertDialog.Builder(this).setTitle("停止正在进行的上传？")
            .setMessage("未完成的文件会被清理，下次需要重新上传。")
            .setNegativeButton("继续上传", null)
            .setPositiveButton("停止") { _, _ -> action() }.show()
    }

    private fun localAddress(): String? {
        val network = connectivity.activeNetwork ?: return null
        return connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }

    private fun checkNetwork() = runOnUiThread {
        if (server != null && localAddress() != listeningAddress) {
            stopReceiving()
            status.text = "网络已变化，请检查连接后重新开启接收。"
        }
    }

    private fun startReceiving() {
        val ip = localAddress()
        if (ip == null) {
            address.text = "网络未连接"
            qr.contentDescription = "网络未连接，无法显示二维码"
            status.text = "未找到局域网 IPv4 地址。请连接 Wi-Fi 或网线后重试。"
            return
        }
        lateinit var active: UploadServer
        active = UploadServer(ip, directory, { assets.open(it).use { stream -> stream.readBytes() } },
            ::describeApk, { next ->
                runOnUiThread { if (server === active) render(next) }
            })
        try {
            active.start(UploadServer.IDLE_TIMEOUT, true)
            server = active
            listeningAddress = ip
            val matrix = QRCodeWriter().encode("${active.url}/#${active.token}", BarcodeFormat.QR_CODE,
                640, 640, mapOf(EncodeHintType.MARGIN to 4))
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            qr.setImageBitmap(Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888))
            qr.contentDescription = "扫描二维码打开上传网页"
            address.text = active.url
            pin.text = "连接码  ${active.pin}"
            toggle.text = "关闭接收"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            render(active.state)
        } catch (_: IOException) {
            active.stop()
            server = null
            address.text = "接收未开启"
            status.text = "无法开启接收，请检查网络后重试。"
        }
    }

    private fun stopReceiving() {
        val active = server ?: return
        server = null
        active.stop()
        listeningAddress = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        qr.setImageResource(R.drawable.ic_launcher)
        qr.contentDescription = "接收已关闭"
        address.text = "接收已关闭，旧链接已失效"
        pin.text = ""
        toggle.text = "开启接收"
        progress.visibility = View.GONE
        if (active.state.busy) {
            status.text = "上传已停止，请重新开启接收后再试。"
            detail.text = ""
        } else status.text = "接收已关闭。"
        renderPackages()
    }

    private fun render(state: TransferState) {
        status.text = state.message
        progress.visibility = if (state.total > 0) View.VISIBLE else View.GONE
        progress.progress = if (state.total > 0) (state.received * 100 / state.total).toInt() else 0
        detail.text = when {
            state.busy -> "${size(state.received)} / ${size(state.total)} · 请保持上传网页在前台"
            else -> "可连续上传 · 单个 APK 最大 1 GiB"
        }
        renderPackages(state.busy)
    }

    private fun renderPackages(busy: Boolean = server?.state?.busy == true, force: Boolean = false) {
        if (!force && renderedPackageBusy == busy) return
        renderedPackageBusy = busy
        val focusedFile = currentFocus?.tag as? File
        val focusedAction = currentFocus?.id
        packageList.removeAllViews()
        val packages = server?.packages() ?: UploadServer.listPackages(directory)
        packageCount.text = "${packages.size} 个"
        if (packages.isEmpty()) {
            packageList.addView(TextView(this).apply {
                text = "还没有文件\n手机扫码上传，文件会保存在这里。"
                textSize = 17f
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT)
                setTextColor(resources.getColor(R.color.secondary, theme))
            })
            if (focusedFile != null) toggle.requestFocus()
            return
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        packages.forEach { stored ->
            val row = layoutInflater.inflate(R.layout.package_row, packageList, false)
            val description = describeApk(stored.file)
            row.findViewById<TextView>(R.id.package_title).text = description ?: "无法识别的 APK"
            row.findViewById<TextView>(R.id.package_name).text = stored.name
            val receivedAt = dateFormat.format(Date(stored.file.lastModified()))
            row.findViewById<TextView>(R.id.package_details).text =
                "${size(stored.file.length())} · $receivedAt 接收"
            row.findViewById<Button>(R.id.install).apply {
                tag = stored.file
                contentDescription = "安装 ${stored.name}"
                isEnabled = !busy && description != null
                setOnClickListener { requestInstall(stored.file) }
            }
            row.findViewById<Button>(R.id.delete).apply {
                tag = stored.file
                contentDescription = "删除 ${stored.name}"
                isEnabled = !busy
                setOnClickListener { confirmDelete(stored.file) }
            }
            packageList.addView(row)
        }
        if (focusedFile != null) {
            val index = packages.indexOfFirst { it.file == focusedFile }.coerceAtLeast(0)
            val action = packageList.getChildAt(index).findViewById<Button>(focusedAction ?: R.id.install)
            if (!busy && action.isEnabled) action.requestFocus() else toggle.requestFocus()
        }
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this).setTitle("删除安装包？")
            .setMessage("只删除接收到的 APK，不会卸载已安装的应用。")
            .setNegativeButton("保留", null)
            .setPositiveButton("删除") { _, _ ->
                val success = server?.deletePackage(file) ?: deletePackage(file)
                status.text = if (success) "安装包已删除。" else "暂时无法删除，请等待上传结束后重试。"
                renderPackages(force = true)
            }.show()
    }

    private fun deletePackage(file: File): Boolean {
        if (!file.isFile || !file.delete()) return !file.exists()
        File(directory, "${file.name}.name").delete()
        return true
    }

    @Suppress("DEPRECATION")
    private fun describeApk(file: File): String? = try {
        val info = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        info?.applicationInfo?.let {
            it.sourceDir = file.absolutePath
            it.publicSourceDir = file.absolutePath
            "${it.loadLabel(packageManager)}  ${info.versionName.orEmpty()}"
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun requestInstall(file: File) {
        if (!file.isFile) return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle("允许 Up2TV 安装应用")
                .setMessage("需要在系统设置中允许来自 Up2TV 的安装。开启后仍由你确认每次安装。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往设置") { _, _ ->
                    try {
                        pendingInstall = file
                        awaitingPermission = true
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (_: ActivityNotFoundException) {
                        awaitingPermission = false
                        status.text = "此电视没有标准授权页面。请在系统安全设置中允许第三方安装后重试。"
                    } catch (_: SecurityException) {
                        awaitingPermission = false
                        status.text = "系统禁止打开安装授权设置，请检查电视的安全策略。"
                    }
                }.show()
        } else launchInstaller(file)
    }

    private fun launchInstaller(file: File) {
        if (!file.isFile) return
        stopReceiving()
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("APK", uri)
            })
            status.text = "已打开系统安装界面。确认结果后，可删除安装包。"
        } catch (_: ActivityNotFoundException) {
            status.text = "系统未提供可用的 APK 安装器，无法通过此应用安装。"
        } catch (_: SecurityException) {
            status.text = "安装被系统拒绝，请检查安装权限或电视固件限制。"
        }
    }

    private fun size(bytes: Long) = String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
}
