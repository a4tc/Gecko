# Artwork Gallery and automatic hosting

1. Pick an image in Studio or an app profile. Gecko validates it (JPEG/PNG/WebP, magic bytes, 8 MiB, dimension limits) and stores it privately.
2. Studio stores a stable `gecko:<id>` reference. No ID is ever copied or pasted.
3. When presence is published, the engine resolves that reference: the image is posted to your own private Discord webhook, the resulting attachment link is registered with Discord's external-assets endpoint, and the returned `mp:` media-proxy reference is what Rich Presence uses.
4. References are cached by SHA-256 for 12 hours. Discord attachment links are signed and expire, so an older reference is rebuilt on demand and the obsolete webhook message is deleted. The cache is capped at 200 entries.
5. Media covers and app icons use the same pipeline automatically.

One-time setup: Server Settings > Integrations > Webhooks > New Webhook > Copy Webhook URL, then paste it under Settings > Discord Artwork and press SEND TEST IMAGE.

Limits: artwork reachable through a Discord media link can be viewed by anyone who obtains that link. Deleting a Gallery entry removes only the local copy. Whether Discord keeps a registered media-proxy copy after the source attachment expires is not documented; Gecko therefore refreshes references on a TTL instead of assuming permanence.
