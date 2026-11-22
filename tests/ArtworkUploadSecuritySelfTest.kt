package app.geckopresence.host.core

import javax.crypto.spec.SecretKeySpec

fun main() {
    val token = "a".repeat(64)
    check(ArtworkUploadTokenPolicy.normalize(token) == token)
    check(ArtworkUploadTokenPolicy.normalize("short") == null)
    check(ArtworkUploadTokenPolicy.normalize("${"a".repeat(40)} with-space") == null)
    check(ArtworkUploadTokenPolicy.normalize("${"a".repeat(40)}\n") == "a".repeat(40))
    check(ArtworkUploadTokenPolicy.normalize("a".repeat(257)) == null)

    val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    val encrypted = ArtworkUploadTokenCodec.encrypt(token, key)
    check(token !in encrypted)
    check(ArtworkUploadTokenCodec.decrypt(encrypted, key) == token)
    check(encrypted != ArtworkUploadTokenCodec.encrypt(token, key)) { "AES-GCM encryption must use a fresh nonce" }
    val wrongKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    check(runCatching { ArtworkUploadTokenCodec.decrypt(encrypted, wrongKey) }.isFailure)
    check(runCatching { ArtworkUploadTokenCodec.decrypt("v1.invalid", key) }.isFailure)

    println("ArtworkUploadSecuritySelfTest: artwork credential policy and AES-GCM checks passed")
}
