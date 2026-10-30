package dev.up2tv

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class UploadServerTest {
    private lateinit var directory: File
    private lateinit var server: UploadServer
    private val now = AtomicLong(0)
    private var available = Long.MAX_VALUE
    private var valid = true
    private val apk = ByteArray(180_000) { (it % 251).toByte() }

    @Before fun setUp() {
        directory = Files.createTempDirectory("up2tv-test-").toFile()
        server = newServer()
        server.start(1000, true)
    }

    private fun newServer() = UploadServer(
        "127.0.0.1", directory, { "<html>上传</html>".toByteArray() },
        { if (valid) "示例应用 1.0" else null }, {},
        { available }, { now.get() },
    )

    @After fun tearDown() {
        server.stop()
        await { directory.listFiles()!!.none { it.extension == "part" } }
        directory.deleteRecursively()
    }

    private fun request(
        path: String = "/upload", body: ByteArray = apk, token: String = server.token,
        headers: Map<String, String> = emptyMap(), method: String = "POST",
    ): Pair<Int, String> {
        val connection = URL(server.url + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 3000
        connection.requestMethod = method
        connection.setRequestProperty("X-Up2TV-Token", token)
        connection.setRequestProperty("X-File-Name", "example.apk")
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (method == "POST") {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        return try {
            val code = connection.responseCode
            val text = (if (code >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } finally {
            connection.disconnect()
        }
    }

    private fun raw(headers: String, body: ByteArray = byteArrayOf()): String =
        Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("POST /upload HTTP/1.1\r\nHost: 127.0.0.1:${server.listeningPort}\r\n" +
                "X-Up2TV-Token: ${server.token}\r\nX-File-Name: test.apk\r\n" +
                "Content-Type: application/octet-stream\r\n$headers\r\n\r\n").toByteArray() + body)
            socket.getInputStream().bufferedReader().readText()
        }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue("等待条件超时", condition())
    }

    @Test fun uploadIsStreamedWithoutChangingBytes() {
        assertEquals(200, request().first)
        val first = server.packages().single()
        assertArrayEquals(apk, first.file.readBytes())
        assertTrue(server.state.ready)
        assertEquals(apk.size.toLong(), server.state.received)
        assertEquals(200, request(body = byteArrayOf(1, 2, 3)).first)
        assertEquals(2, server.packages().size)
        assertTrue(server.deletePackage(first.file))
        assertEquals(1, server.packages().size)
        assertEquals(200, request().first)
    }

    @Test fun unauthorizedUploadDoesNotCreateFiles() {
        assertEquals(401, request(body = byteArrayOf(1), token = "wrong").first)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun pinPairsAndRateLimitExpires() {
        repeat(5) {
            assertEquals(401, request("/pair", byteArrayOf(), headers = mapOf("X-Up2TV-Pin" to "wrong")).first)
        }
        assertEquals(403, request("/pair", byteArrayOf(), headers = mapOf("X-Up2TV-Pin" to server.pin)).first)
        now.set(60_001)
        assertEquals(200 to server.token, request("/pair", byteArrayOf(), headers = mapOf("X-Up2TV-Pin" to server.pin)))
    }

    @Test fun invalidArchiveIsRemovedAndCanRetry() {
        valid = false
        assertEquals(400, request().first)
        assertTrue(directory.listFiles()!!.isEmpty())
        valid = true
        assertEquals(200, request().first)
    }

    @Test fun invalidNamesAndTypesAreRejected() {
        for (name in listOf("a.xapk", "a.apkm", "..%2Fa.apk", "%0Aa.apk", "%ZZ.apk")) {
            assertEquals(400, request(body = byteArrayOf(1), headers = mapOf("X-File-Name" to name)).first)
        }
        assertEquals(400, request(body = byteArrayOf(1), headers = mapOf("Content-Type" to "text/plain")).first)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun missingInvalidAndExcessiveLengthAreRejected() {
        assertTrue(raw("").startsWith("HTTP/1.1 411"))
        assertTrue(raw("Content-Length: invalid").startsWith("HTTP/1.1 411"))
        assertTrue(raw("Content-Length: -1").startsWith("HTTP/1.1 413"))
        assertTrue(raw("Content-Length: 0").startsWith("HTTP/1.1 413"))
        assertTrue(raw("Content-Length: ${UploadServer.MAX_BYTES + 1}").startsWith("HTTP/1.1 413"))
        assertTrue(raw("Transfer-Encoding: chunked").startsWith("HTTP/1.1 400"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun insufficientSpaceDoesNotStartUpload() {
        available = apk.size + UploadServer.RESERVE_BYTES - 1
        assertEquals(400, request().first)
        assertFalse(server.state.busy)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun disconnectRemovesPartialFile() {
        Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.getOutputStream().write(("POST /upload HTTP/1.1\r\nHost: 127.0.0.1:${server.listeningPort}\r\n" +
                "X-Up2TV-Token: ${server.token}\r\nX-File-Name: a.apk\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Length: 100000\r\n\r\npartial").toByteArray())
            await { server.state.busy }
        }
        await { !server.state.busy && directory.listFiles()!!.isEmpty() }
        assertEquals(200, request().first)
    }

    @Test fun stalledUploadTimesOut() {
        assertTrue(raw("Content-Length: 100", byteArrayOf(1)).startsWith("HTTP/1.1 408"))
        assertFalse(server.state.busy)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun stopClosesActiveUploadAndRevokesOldToken() {
        Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.getOutputStream().write(("POST /upload HTTP/1.1\r\nHost: 127.0.0.1:${server.listeningPort}\r\n" +
                "X-Up2TV-Token: ${server.token}\r\nX-File-Name: a.apk\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Length: 100000\r\n\r\npartial").toByteArray())
            await { server.state.busy }
            val oldToken = server.token
            server.stop()
            await { directory.listFiles()!!.isEmpty() }
            server = newServer()
            server.start(1000, true)
            assertNotEquals(oldToken, server.token)
            assertEquals(401, request(body = byteArrayOf(1), token = oldToken).first)
            assertEquals(200, request().first)
        }
    }

    @Test fun concurrentUploadsCannotOverwriteOneAnother() {
        val inValidation = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.stop()
        server = UploadServer("127.0.0.1", directory, { byteArrayOf() }, {
            inValidation.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            "有效 APK"
        }, {})
        server.start(1000, true)
        var result: Pair<Int, String>? = null
        val thread = Thread { result = request() }.apply { start() }
        try {
            assertTrue(inValidation.await(3, TimeUnit.SECONDS))
            assertEquals(409, request(body = byteArrayOf(1)).first)
            assertFalse(server.deletePackage(File(directory, "missing.apk")))
        } finally {
            release.countDown()
            thread.join(3000)
        }
        assertEquals(200, result?.first)
        assertArrayEquals(apk, server.packages().single().file.readBytes())
    }

    @Test fun stopDuringValidationDoesNotBlockOrPublishAnOldUpload() {
        val validating = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.stop()
        server = UploadServer("127.0.0.1", directory, { byteArrayOf() }, {
            validating.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            "旧会话的安装包"
        }, {})
        server.start(1000, true)
        val upload = Thread { runCatching { request(body = byteArrayOf(1, 2, 3)) } }.apply { start() }
        try {
            assertTrue(validating.await(3, TimeUnit.SECONDS))
            val started = System.nanoTime()
            server.stop()
            assertTrue("关闭不应等待安装包解析", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))
            server = newServer()
            server.start(1000, true)
            assertEquals(200, request().first)
        } finally {
            release.countDown()
            upload.join(3000)
        }
        assertArrayEquals(apk, server.packages().single().file.readBytes())
        await { directory.listFiles()!!.none { it.extension == "part" } }
    }

    @Test fun packagesSurviveServerRestartAndCanBeDeletedIndividually() {
        assertEquals(200, request(body = byteArrayOf(1, 2, 3)).first)
        val stored = server.packages().single()
        val name = stored.name
        server.stop()
        server = newServer()
        server.start(1000, true)
        assertEquals(name, server.packages().single().name)
        assertTrue(server.deletePackage(stored.file))
        assertTrue(server.packages().isEmpty())
    }

    @Test fun packagesAreListedNewestFirst() {
        assertEquals(200, request(body = byteArrayOf(1)).first)
        val older = server.packages().single().file
        assertEquals(200, request(body = byteArrayOf(2)).first)
        val newer = server.packages().first { it.file != older }.file
        assertTrue(older.setLastModified(1_000))
        assertTrue(newer.setLastModified(2_000))
        assertEquals(newer, server.packages().first().file)
    }

    @Test fun crossOriginAndReboundHostsAreRejected() {
        val response = Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: attacker.invalid\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
        assertTrue(response.startsWith("HTTP/1.1 403"))
        val originResponse = Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("POST /pair HTTP/1.1\r\nHost: 127.0.0.1:${server.listeningPort}\r\n" +
                "Origin: http://attacker.invalid\r\nX-Up2TV-Pin: ${server.pin}\r\nContent-Length: 0\r\n\r\n").toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
        assertTrue(originResponse.startsWith("HTTP/1.1 403"))
    }

    @Test fun pageHasSecurityHeadersAndAssetsAreAllowlisted() {
        val connection = URL(server.url).openConnection() as HttpURLConnection
        try {
            assertEquals(200, connection.responseCode)
            assertEquals("no-store", connection.getHeaderField("Cache-Control"))
            assertEquals("nosniff", connection.getHeaderField("X-Content-Type-Options"))
            assertNotNull(connection.getHeaderField("Content-Security-Policy"))
            assertFalse(connection.inputStream.bufferedReader().readText().contains(server.token))
        } finally {
            connection.disconnect()
        }
        assertEquals(404, request("/ready.apk", method = "GET").first)
        assertEquals(404, request("/../packages/ready.apk", method = "GET").first)
    }
}
