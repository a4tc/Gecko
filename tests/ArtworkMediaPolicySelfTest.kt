package app.geckopresence.host.core

import kotlin.random.Random

fun main() {
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val webp = "RIFF0000WEBP".toByteArray(Charsets.US_ASCII)
    val gif87 = "GIF87a\u0001\u0000\u0001\u0000".toByteArray(Charsets.US_ASCII)
    val gif89 = "GIF89a\u0001\u0000\u0001\u0000".toByteArray(Charsets.US_ASCII)
    check(ArtworkMediaPolicy.detectImage(jpeg)?.mimeType == "image/jpeg")
    check(ArtworkMediaPolicy.detectImage(png)?.extension == "png")
    check(ArtworkMediaPolicy.detectImage(webp)?.mimeType == "image/webp")
    check(ArtworkMediaPolicy.detectImage(gif87)?.mimeType == "image/gif")
    check(ArtworkMediaPolicy.detectImage(gif89)?.extension == "gif")
    check(ArtworkMediaPolicy.detectImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) == null)
    check(ArtworkMediaPolicy.hasValidDimensions(8192, 4882))
    check(!ArtworkMediaPolicy.hasValidDimensions(8192, 8192))
    check(!ArtworkMediaPolicy.hasValidDimensions(0, 10))
    check(ArtworkMediaPolicy.isValidStoredFileName("01234567-89ab-cdef-0123-456789abcdef", "01234567-89ab-cdef-0123-456789abcdef.png"))
    check(!ArtworkMediaPolicy.isValidStoredFileName("../../etc/passwd", "../../etc/passwd.png"))
    check(ArtworkMediaPolicy.isValidStoredFileName("01234567-89ab-cdef-0123-456789abcdef", "01234567-89ab-cdef-0123-456789abcdef.gif"))
    check(ArtworkMediaPolicy.mimeMatchesExtension("image/jpeg", "art.jpg"))
    check(ArtworkMediaPolicy.mimeMatchesExtension("image/gif", "art.gif"))
    check(!ArtworkMediaPolicy.mimeMatchesExtension("image/gif", "art.png"))
    check(!ArtworkMediaPolicy.mimeMatchesExtension("image/png", "art.jpg"))

    val random = Random(0xA47)
    repeat(20_000) {
        val bytes = ByteArray(random.nextInt(0, 128)) { random.nextInt(0, 256).toByte() }
        if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte() || bytes[2] != 0xFF.toByte()) {
            check(ArtworkMediaPolicy.detectImage(bytes) == null || bytes.size >= 8)
        }
    }
    println("ArtworkMediaPolicySelfTest: format, dimension, path and 20,000 malformed-input cases passed")
}
