package app.geckopresence.host

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val uploads = AtomicInteger(0)
    val redirects = AtomicInteger(0)
    val capturedAuth = arrayOfNulls<String>(1)
    val capturedCookie = arrayOfNulls<String>(1)
    val capturedBody = arrayOfNulls<ByteArray>(1)
    server.createContext("/3/image") { exchange ->
        uploads.incrementAndGet()
        capturedAuth[0] = exchange.requestHeaders.getFirst("Authorization")
        capturedCookie[0] = exchange.requestHeaders.getFirst("Cookie")
        capturedBody[0] = exchange.requestBody.use { it.readBytes() }
        val response = """{"success":true,"data":{"link":"https://i.imgur.com/gecko-test.png"}}""".toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }
    server.createContext("/redirect") { exchange ->
        exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/trap")
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }
    server.createContext("/trap") { exchange ->
        redirects.incrementAndGet()
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
    }
    server.createContext("/wrong-host") { exchange ->
        val response = """{"success":true,"data":{"link":"https://i.imgur.com.evil.example/image.png"}}""".toByteArray()
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }
    server.createContext("/large-response") { exchange ->
        val response = ByteArray(65 * 1024) { 'x'.code.toByte() }
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }
    server.start()
    val temp = File.createTempFile("gecko-art", ".png")
    val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(2048) { it.toByte() }
    temp.writeBytes(bytes)
    val base = "http://127.0.0.1:${server.address.port}"
    try {
        val success = ImgurAnonymousUploader.uploadForTest(temp, "0123456789abcdef", "$base/3/image")
        check(success == ImgurAnonymousUploader.Result.Success("https://i.imgur.com/gecko-test.png"))
        check(capturedAuth[0] == "Client-ID 0123456789abcdef")
        check(capturedCookie[0] == null) { "Uploader must not forward browser cookies" }
        check(capturedBody[0]!!.toString(Charsets.ISO_8859_1).contains("name=\"image\""))
        check(capturedBody[0]!!.size > bytes.size)
        check(uploads.get() == 1)

        check(ImgurAnonymousUploader.uploadForTest(temp, "bad", "$base/3/image") is ImgurAnonymousUploader.Result.Failure)
        check(ImgurAnonymousUploader.uploadForTest(temp, "0123456789abcdef", "http://evil.example/upload") is ImgurAnonymousUploader.Result.Failure)
        check(uploads.get() == 1)

        val redirect = ImgurAnonymousUploader.uploadForTest(temp, "0123456789abcdef", "$base/redirect")
        check(redirect is ImgurAnonymousUploader.Result.Failure)
        check(redirects.get() == 0) { "Uploader followed a redirect to a second host/path" }

        val wrongHost = ImgurAnonymousUploader.uploadForTest(temp, "0123456789abcdef", "$base/wrong-host")
        check(wrongHost is ImgurAnonymousUploader.Result.Failure)
        val largeResponse = ImgurAnonymousUploader.uploadForTest(temp, "0123456789abcdef", "$base/large-response")
        check(largeResponse is ImgurAnonymousUploader.Result.Failure)

        val oversized = File.createTempFile("gecko-large", ".png").apply { writeBytes(ByteArray(8 * 1024 * 1024 + 1)) }
        check(ImgurAnonymousUploader.uploadForTest(oversized, "0123456789abcdef", "$base/3/image") is ImgurAnonymousUploader.Result.Failure)
        oversized.delete()
        check(uploads.get() == 1)
        println("ImgurUploaderDynamicSelfTest: multipart upload, Client-ID-only auth, cookie isolation, redirect refusal, returned-host allow-list, response cap and input limits passed")
    } finally {
        temp.delete()
        server.stop(0)
    }
}
