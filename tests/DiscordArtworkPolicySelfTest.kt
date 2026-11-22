package app.geckopresence.host.core

fun main() {
    val id = "123456789012345678"
    val token = "A".repeat(68)
    val canonical = "https://discord.com/api/webhooks/$id/$token"

    // Webhook URL normalization
    check(DiscordArtworkPolicy.normalizeWebhookUrl(canonical) == canonical)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("  $canonical  ") == canonical)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("https://discordapp.com/api/webhooks/$id/$token") == canonical)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("https://ptb.discord.com/api/v10/webhooks/$id/$token") == canonical)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("http://discord.com/api/webhooks/$id/$token") == null)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("https://discord.com.evil.example/api/webhooks/$id/$token") == null)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("https://user@discord.com/api/webhooks/$id/$token") == null)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("https://discord.com:8443/api/webhooks/$id/$token") == null)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("$canonical?thread_id=1") == null)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("$canonical#x") == null)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("$canonical/messages/1") == null)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("https://discord.com/api/webhooks/abc/$token") == null)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("https://discord.com/api/webhooks/$id/short") == null)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("") == null)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("$canonical\n") == canonical)
    check(DiscordArtworkPolicy.normalizeWebhookUrl("https://discord.com/api/webhooks/$id/${"A".repeat(30)} x") == null)

    check(DiscordArtworkPolicy.webhookExecuteUrl(canonical) == "$canonical?wait=true")
    check(DiscordArtworkPolicy.webhookMessageUrl(canonical, "987654321098765432") == "$canonical/messages/987654321098765432")
    check(DiscordArtworkPolicy.webhookMessageUrl(canonical, "../x") == null)

    // Attachment CDN URL
    val cdn = "https://cdn.discordapp.com/attachments/111111111111111111/222222222222222222/abc.png?ex=1&is=2&hm=3"
    check(DiscordArtworkPolicy.isSafeAttachmentCdnUrl(cdn))
    check(DiscordArtworkPolicy.isSafeAttachmentCdnUrl(cdn.replace("cdn.discordapp.com", "media.discordapp.net")))
    check(!DiscordArtworkPolicy.isSafeAttachmentCdnUrl(cdn.replace("https", "http")))
    check(!DiscordArtworkPolicy.isSafeAttachmentCdnUrl(cdn.replace("cdn.discordapp.com", "evil.example")))
    check(!DiscordArtworkPolicy.isSafeAttachmentCdnUrl("https://cdn.discordapp.com.evil.example/attachments/1/2/a.png"))
    check(!DiscordArtworkPolicy.isSafeAttachmentCdnUrl("https://cdn.discordapp.com/avatars/1/a.png"))
    check(!DiscordArtworkPolicy.isSafeAttachmentCdnUrl("https://user@cdn.discordapp.com/attachments/1/2/a.png"))
    check(!DiscordArtworkPolicy.isSafeAttachmentCdnUrl(cdn + "#frag"))

    // Media-proxy reference
    check(DiscordArtworkPolicy.toMediaProxyReference("external/abc123/https/cdn.discordapp.com/attachments/1/2/a.png") ==
        "mp:external/abc123/https/cdn.discordapp.com/attachments/1/2/a.png")
    check(DiscordArtworkPolicy.toMediaProxyReference("attachments/1/2/a.png") == null)
    check(DiscordArtworkPolicy.toMediaProxyReference("/external/abc123/https/cdn.discordapp.com/attachments/1/2/a.png") ==
        "mp:external/abc123/https/cdn.discordapp.com/attachments/1/2/a.png")
    check(DiscordArtworkPolicy.toMediaProxyReference("external/" + "a".repeat(350)) ==
        "mp:external/" + "a".repeat(350))
    check(DiscordArtworkPolicy.toMediaProxyReference("external/" + "a".repeat(513)) == null)
    check(DiscordArtworkPolicy.toMediaProxyReference("external/a b") == null)

    // Signed attachment URLs retain their CDN path and signature in mp: form.
    val signedAttachment =
        "https://cdn.discordapp.com/attachments/111111111111111111/222222222222222222/cover.png?ex=1&is=2&hm=abc"
    val signedReference =
        "mp:attachments/111111111111111111/222222222222222222/cover.png?ex=1&is=2&hm=abc"
    check(DiscordArtworkPolicy.toMediaProxyReference(signedAttachment) == signedReference)
    check(DiscordArtworkPolicy.toMediaProxyReference(signedReference) == signedReference)
    check(DiscordArtworkPolicy.toMediaProxyReference(
        signedAttachment.replace("cdn.discordapp.com", "media.discordapp.net")
    ) == signedReference)
    check(DiscordArtworkPolicy.toMediaProxyReference(
        signedAttachment.replace("cdn.discordapp.com", "evil.example")
    ) == null)

    // Local gallery references
    val galleryId = "01234567-89ab-cdef-0123-456789abcdef"
    check(DiscordArtworkPolicy.localReference(galleryId) == "gecko:$galleryId")
    check(DiscordArtworkPolicy.localId("gecko:$galleryId") == galleryId)
    check(DiscordArtworkPolicy.localId("GECKO:$galleryId") == galleryId)
    check(DiscordArtworkPolicy.localId("gecko:../../etc") == null)
    check(DiscordArtworkPolicy.localId(galleryId) == null)
    check(DiscordArtworkPolicy.localReference("nope") == null)
    check(PresenceActivity(name = "x", largeImage = PresenceAsset("gecko:$galleryId")).validate().isEmpty())

    // Freshness / TTL
    val now = 1_800_000_000_000L
    check(DiscordArtworkPolicy.isFresh(now - 1_000L, now))
    check(!DiscordArtworkPolicy.isFresh(now - DiscordArtworkPolicy.MP_REFERENCE_TTL_MS, now))
    check(!DiscordArtworkPolicy.isFresh(0L, now))
    check(!DiscordArtworkPolicy.isFresh(now + 1L, now))

    println("DiscordArtworkPolicySelfTest: webhook, CDN, media-proxy, local-reference and TTL cases passed")
}
