# Artwork Gallery and automatic hosting

1. Pick an image in Studio or an app profile. Gecko validates JPEG/PNG/WebP/GIF signatures and dimensions, enforces a 25,000,000-byte file cap, and stores the image privately.
2. Studio stores a stable `gecko:<id>` reference. No ID is copied or pasted.
3. When the user applies that Gallery item to presence, Gecko uploads it through the user's Discord webhook, validates the signed CDN attachment URL, and converts it to the `mp:` media-proxy reference sent in the activity.
4. Cache entries are keyed by content SHA-256 plus a one-way hash of webhook identity. Discord attachment links are signed and expire, so an old reference is rebuilt on demand. The cache is capped at 200 entries and webhook-scoped eviction avoids deleting messages from another webhook.
5. Automatic media covers and app icons use the same webhook delivery path only after the user explicitly enables Automatic Artwork; it is OFF by default.

One-time setup: Server Settings > Integrations > Webhooks > New Webhook > Copy Webhook URL, then paste it under Settings > Discord Artwork and press SEND TEST IMAGE.

Limits: artwork reachable through a Discord media link can be viewed by anyone who obtains that link. Deleting a Gallery entry removes only the local copy. Whether Discord keeps a registered media-proxy copy after the source attachment expires is not documented; Gecko therefore refreshes references on a TTL instead of assuming permanence.
