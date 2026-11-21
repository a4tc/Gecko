package app.geckopresence.host.core

import javax.crypto.spec.SecretKeySpec

fun main() {
    check(ArtworkUploadPolicy.normalizeWorkerEndpoint("https://my-worker.example.workers.dev") ==
        "https://my-worker.example.workers.dev/v1/upload")
    check(ArtworkUploadPolicy.normalizeWorkerEndpoint("https://my-worker.example.workers.dev/v1/upload") ==
        "https://my-worker.example.workers.dev/v1/upload")
    check(ArtworkUploadPolicy.normalizeWorkerEndpoint("http://worker.example") == null)
    check(ArtworkUploadPolicy.normalizeWorkerEndpoint("https://user:pass@worker.example") == null)
    check(ArtworkUploadPolicy.normalizeWorkerEndpoint("https://worker.example/other") == null)
    check(ArtworkUploadPolicy.normalizeWorkerEndpoint("https://worker.example/v1/upload?token=leak") == null)
    check(ArtworkUploadPolicy.normalizeWorkerEndpoint("https://localhost") == null)
    check(ArtworkUploadPolicy.normalizeWorkerEndpoint("https://worker.local") == null)
    check(ArtworkUploadPolicy.normalizeWorkerEndpoint("https://127.0.0.1") == null)
    check(ArtworkUploadPolicy.normalizeWorkerEndpoint("https://192.168.1.20") == null)
    check(ArtworkUploadPolicy.normalizeWorkerEndpoint("https://123.456.789.10") == null)

    check(ArtworkUploadPolicy.normalizeAssetBaseUrl("https://assets.example.com/") == "https://assets.example.com")
    check(ArtworkUploadPolicy.normalizeAssetBaseUrl("http://assets.example.com") == null)
    check(ArtworkUploadPolicy.normalizeAssetBaseUrl("https://assets.example.com/path") == null)
    check(ArtworkUploadPolicy.normalizeAssetBaseUrl("https://assets.example.com?key=x") == null)
    check(ArtworkUploadPolicy.normalizeAssetBaseUrl("https://assets.example.com:8443") == null)
    check(ArtworkUploadPolicy.normalizeAssetBaseUrl("https://127.0.0.1") == null)
    check(ArtworkUploadPolicy.normalizeAssetBaseUrl("https://10.0.0.2") == null)

    val hash = "a".repeat(64)
    val asset = "https://assets.example.com/artwork/$hash.webp"
    check(ArtworkUploadPolicy.isSafeCloudflareAssetUrl(asset))
    check(ArtworkUploadPolicy.isSafeCloudflareAssetUrl(asset, "https://assets.example.com"))
    check(ArtworkUploadPolicy.isExpectedCloudflareAssetUrl(asset, "https://assets.example.com", hash, "webp"))
    check(ArtworkUploadPolicy.isExpectedCloudflareAssetUrl(asset, hash))
    check(!ArtworkUploadPolicy.isExpectedCloudflareAssetUrl(asset, "b".repeat(64)))
    check(!ArtworkUploadPolicy.isExpectedCloudflareAssetUrl(asset, "https://assets.example.com", "b".repeat(64), "webp"))
    check(!ArtworkUploadPolicy.isExpectedCloudflareAssetUrl(asset, "https://other.example.com", hash, "webp"))
    check(!ArtworkUploadPolicy.isSafeCloudflareAssetUrl("http://assets.example.com/artwork/$hash.webp", "https://assets.example.com"))
    check(!ArtworkUploadPolicy.isSafeCloudflareAssetUrl("https://user@assets.example.com/artwork/$hash.webp", "https://assets.example.com"))
    check(!ArtworkUploadPolicy.isSafeCloudflareAssetUrl("https://assets.example.com:8443/artwork/$hash.webp", "https://assets.example.com"))
    check(!ArtworkUploadPolicy.isSafeCloudflareAssetUrl("https://assets.example.com/artwork/$hash.webp?x=1", "https://assets.example.com"))
    check(!ArtworkUploadPolicy.isSafeCloudflareAssetUrl("https://127.0.0.1/artwork/$hash.webp"))
    check(!ArtworkUploadPolicy.isSafeCloudflareAssetUrl("https://assets.example.com.evil.example/artwork/$hash.webp", "https://assets.example.com"))
    check(!ArtworkUploadPolicy.isSafeCloudflareAssetUrl("https://assets.example.com/artwork/../$hash.webp", "https://assets.example.com"))

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

    println("ArtworkUploadSecuritySelfTest: Worker endpoint, asset URL, token and AES-GCM checks passed")
}
