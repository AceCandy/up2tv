package dev.up2tv

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

data class TransferState(
    val message: String = "等待上传 APK",
    val received: Long = 0,
    val total: Long = 0,
    val busy: Boolean = false,
    val ready: Boolean = false,
)

data class StoredPackage(val file: File, val name: String)

/** 一次接收会话：凭证、连接和临时文件均随会话结束失效。 */
class UploadServer(
    private val address: String,
    private val directory: File,
    private val assets: (String) -> ByteArray,
    private val validateApk: (File) -> String?,
    private val changed: (TransferState) -> Unit,
    private val freeBytes: () -> Long = { directory.usableSpace },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : NanoHTTPD(address, 0) {
    companion object {
        const val MAX_BYTES = 1024L * 1024 * 1024
        const val RESERVE_BYTES = 32L * 1024 * 1024
        const val IDLE_TIMEOUT = 30_000
        const val MAX_DURATION = 30L * 60 * 1000

        fun listPackages(directory: File): List<StoredPackage> = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val metadata = File(directory, "${file.name}.name")
                val name = runCatching { metadata.readText(Charsets.UTF_8) }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: file.name
                StoredPackage(file, name)
            }.orEmpty()
    }

    private val random = SecureRandom()
    val token: String = ByteArray(24).also(random::nextBytes)
        .joinToString("") { "%02x".format(it) }
    val pin: String = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
    val url: String get() = "http://$address:$listeningPort"
    @Volatile private var closed = false
    @Volatile private var receiving = false
    @Volatile var state = TransferState(ready = listPackages(directory).isNotEmpty())
        private set
    private var failedPins = 0
    private var lockedUntil = 0L

    init {
        require(directory.isDirectory)
        setAsyncRunner(object : DefaultAsyncRunner() {
            @Synchronized override fun exec(handler: ClientHandler) {
                if (running.size >= 8) handler.close() else super.exec(handler)
            }
        })
    }

    override fun serve(session: IHTTPSession): Response {
        if (closed) return reply(Response.Status.SERVICE_UNAVAILABLE, "接收已关闭，请在电视上重新开启。")
        val host = "$address:$listeningPort"
        val origin = session.headers["origin"]
        if (session.headers["host"] != host || (origin != null && origin != "http://$host")) {
            return reply(Response.Status.FORBIDDEN, "请使用电视显示的地址访问。")
        }
        if (session.method == Method.GET) {
            val asset = when (session.uri) {
                "/" -> "index.html" to "text/html; charset=utf-8"
                "/app.js" -> "app.js" to "application/javascript; charset=utf-8"
                "/style.css" -> "style.css" to "text/css; charset=utf-8"
                else -> return reply(Response.Status.NOT_FOUND, "页面不存在。")
            }
            return try {
                val bytes = assets(asset.first)
                secure(newFixedLengthResponse(Response.Status.OK, asset.second, bytes.inputStream(), bytes.size.toLong()))
            } catch (_: IOException) {
                reply(Response.Status.INTERNAL_ERROR, "页面加载失败，请重新开启接收。")
            }
        }
        if (session.method != Method.POST) {
            return reply(Response.Status.METHOD_NOT_ALLOWED, "不支持此操作。")
        }
        if (session.headers.containsKey("transfer-encoding")) {
            return reply(Response.Status.BAD_REQUEST, "请使用支持固定文件大小的浏览器上传。")
        }
        if (session.uri == "/pair") return pair(session)
        if (session.uri != "/upload") return reply(Response.Status.NOT_FOUND, "操作不存在。")
        if (!MessageDigest.isEqual(token.toByteArray(), session.headers["x-up2tv-token"].orEmpty().toByteArray())) {
            return reply(Response.Status.UNAUTHORIZED, "连接已失效，请重新扫码或输入电视上的连接码。")
        }
        return receive(session)
    }

    @Synchronized private fun pair(session: IHTTPSession): Response {
        if (clock() < lockedUntil) return reply(Response.Status.FORBIDDEN, "尝试过于频繁，请 60 秒后再试。")
        val supplied = session.headers["x-up2tv-pin"].orEmpty().toByteArray()
        if (!MessageDigest.isEqual(pin.toByteArray(), supplied)) {
            failedPins++
            if (failedPins >= 5) {
                lockedUntil = clock() + 60_000
                failedPins = 0
            }
            return reply(Response.Status.UNAUTHORIZED, "连接码不正确，请查看电视屏幕。")
        }
        failedPins = 0
        return reply(Response.Status.OK, token)
    }

    private fun receive(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return reply(Response.Status.LENGTH_REQUIRED, "无法读取文件大小，请更换浏览器。")
        if (length <= 0 || length > MAX_BYTES) {
            return reply(Response.Status.PAYLOAD_TOO_LARGE, "请选择非空且不超过 1 GiB 的 APK。")
        }
        val name = try {
            URLDecoder.decode(session.headers["x-file-name"].orEmpty(), "UTF-8")
        } catch (_: IllegalArgumentException) {
            return reply(Response.Status.BAD_REQUEST, "文件名无效。")
        }
        if (!name.endsWith(".apk", ignoreCase = true) || name.length > 255 ||
            name.any { it.code < 32 || it == '/' || it == '\\' } ||
            session.headers["content-type"] != "application/octet-stream"
        ) {
            return reply(Response.Status.BAD_REQUEST, "仅支持单个 APK 文件，不支持 XAPK 或 APKM。")
        }
        synchronized(this) {
            if (closed) return reply(Response.Status.SERVICE_UNAVAILABLE, "接收已关闭。")
            if (receiving) {
                return reply(Response.Status.CONFLICT, "电视上已有上传任务，请等待当前上传完成。")
            }
            if (freeBytes() < length + RESERVE_BYTES) {
                return reply(Response.Status.BAD_REQUEST, "电视剩余空间不足，请清理后重试。")
            }
            receiving = true
            update(TransferState("正在接收 $name", total = length, busy = true))
        }
        var partial: File? = null
        var metadataTemp: File? = null
        try {
            val started = clock()
            var lastUpdate = started
            var received = 0L
            partial = File.createTempFile("upload-", ".part", directory)
            partial!!.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (received < length) {
                    if (closed) throw IOException("接收已关闭")
                    if (clock() - started > MAX_DURATION) throw SocketTimeoutException()
                    val count = session.inputStream.read(buffer, 0, minOf(buffer.size.toLong(), length - received).toInt())
                    if (count < 0) throw IOException("上传中断")
                    if (freeBytes() < count + RESERVE_BYTES) throw IOException("电视剩余空间不足")
                    output.write(buffer, 0, count)
                    received += count
                    if (clock() - lastUpdate >= 150 || received == length) {
                        update(TransferState("正在接收 $name", received, length, busy = true))
                        lastUpdate = clock()
                    }
                }
            }
            val description = validateApk(partial!!)
                ?: throw IOException("不是有效的 APK，或安装包格式不受此电视支持")
            val packageFile = File(directory, "${partial!!.name.removeSuffix(".part")}.apk")
            val metadata = File(directory, "${packageFile.name}.name")
            metadataTemp = File(directory, "${metadata.name}.part")
            metadataTemp!!.writeText(name, Charsets.UTF_8)
            synchronized(this) {
                if (closed) throw IOException("接收已关闭")
                if (!partial!!.renameTo(packageFile)) throw IOException("无法保存安装包，请清理空间后重试")
                if (!metadataTemp!!.renameTo(metadata)) {
                    packageFile.delete()
                    throw IOException("无法保存安装包，请清理空间后重试")
                }
                update(TransferState(description, length, length, ready = true))
            }
            return reply(Response.Status.OK, "上传完成，请在电视上确认安装。")
        } catch (_: SocketTimeoutException) {
            update(TransferState("上传超时，请保持网页在前台并重试。"))
            return reply(Response.Status.REQUEST_TIMEOUT, "上传超时，请保持网页在前台并重试。")
        } catch (error: IOException) {
            val message = error.message?.takeIf { it in listOf(
                "接收已关闭", "上传中断", "电视剩余空间不足",
                "不是有效的 APK，或安装包格式不受此电视支持", "无法保存安装包，请清理空间后重试",
            ) } ?: "传输失败，请检查网络和电视空间后重试。"
            update(TransferState(message))
            return reply(Response.Status.BAD_REQUEST, message)
        } finally {
            partial?.delete()
            metadataTemp?.delete()
            receiving = false
        }
    }

    fun packages(): List<StoredPackage> = listPackages(directory)

    @Synchronized fun deletePackage(file: File): Boolean {
        if (receiving) return false
        val sameDirectory = runCatching { file.canonicalFile.parentFile == directory.canonicalFile }.getOrDefault(false)
        if (!sameDirectory || !file.isFile || !file.extension.equals("apk", ignoreCase = true)) return false
        if (!file.delete()) return false
        File(directory, "${file.name}.name").delete()
        val remaining = listPackages(directory)
        update(TransferState(if (remaining.isEmpty()) "等待上传 APK" else "已保存 ${remaining.size} 个安装包", ready = remaining.isNotEmpty()))
        return true
    }

    private fun update(next: TransferState) {
        state = next
        changed(next)
    }

    override fun stop() {
        synchronized(this) { closed = true }
        // 2.3.1 的 stop 会关闭活动 socket，使阻塞中的上传读操作退出。
        super.stop()
    }

    private fun reply(status: Response.Status, text: String) =
        secure(newFixedLengthResponse(status, "text/plain; charset=utf-8", text))

    private fun secure(response: Response): Response = response.apply {
        closeConnection(true)
        addHeader("Cache-Control", "no-store")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
    }
}
