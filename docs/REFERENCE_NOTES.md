# Reference notes

Current 0.6.6+ implementation references:

- Discord Gateway v10 public documentation for Hello, Heartbeat, Identify, Resume, Presence Update, and close-code behavior. The public docs primarily describe bot/application clients; Gecko's user-session Identify compatibility fields are not documented by Discord.
- Public Kizzy Android source is used only as a behavioral reference for the observed user-session Gateway shape and local Discord web-session import pattern. Kizzy's GPL-3.0 source is not copied into Gecko.
- Ktor 2.3.13 client WebSockets documentation and API surface are used for the Kotlin WebSocket transport. This version remains on the 2.x line to avoid an unverified Kotlin toolchain migration from Kotlin Gradle Plugin 2.0.21.
- Android Developers documentation for WebView data-directory isolation and Android Keystore AES/GCM key management informs the local session vault.

Gecko 0.6.5 removed the Discord Social SDK AAR, JNI bridge, CMake project, and native Discord SDK bootstrap. The transport is Kotlin/Ktor only.

## Current artwork delivery boundary

- Gecko uploads Gallery, Studio and automatic artwork bytes to the user's own Discord webhook. The webhook URL remains in the dedicated encrypted credential vault; it is not stored in cache metadata or logs.
- The upload response supplies a signed Discord CDN attachment URL. Gecko converts that URL into the `mp:` media-proxy reference used by the presence activity. No Cloudflare Worker, R2 bucket, Imgur host, self-hosted image endpoint, or third-party image host is part of the current delivery path.
- The local automatic-artwork cache is keyed by the content SHA-256 plus a one-way hash of the normalized webhook identity, so a cache hit or eviction cannot reuse or delete an attachment belonging to a different webhook.
- Automatic artwork is OFF by default and requires an explicit in-app opt-in plus a saved webhook. Manually selected Gallery artwork is resolved when the user applies it to an activity.
- Gallery image bytes are capped at the 25,000,000-byte Discord upload ceiling; the total Gallery remains bounded by its existing item and aggregate-storage limits.
