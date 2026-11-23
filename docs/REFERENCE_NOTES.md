# Reference notes

Current 0.6.6+ implementation references:

- Discord Gateway v10 public documentation for Hello, Heartbeat, Identify, Resume, Presence Update, and close-code behavior. The public docs primarily describe bot/application clients; Gecko's user-session Identify compatibility fields are not documented by Discord.
- Public Kizzy Android source is used only as a behavioral reference for the observed user-session Gateway shape and local Discord web-session import pattern. Kizzy's GPL-3.0 source is not copied into Gecko.
- Ktor 2.3.13 client WebSockets documentation and API surface are used for the Kotlin WebSocket transport. This version remains on the 2.x line to avoid an unverified Kotlin toolchain migration from Kotlin Gradle Plugin 2.0.21.
- Android Developers documentation for WebView data-directory isolation and Android Keystore AES/GCM key management informs the local session vault.

Gecko 0.6.5 removed the Discord Social SDK AAR, JNI bridge, CMake project, and native Discord SDK bootstrap. The transport is Kotlin/Ktor only.

## Artwork hosting references (0.6.6)

- Cloudflare Workers documentation defines the HTTPS Worker request boundary; Cloudflare R2 bindings store the object bytes. The Worker uses a user's own `GECKO_UPLOAD_TOKEN` secret and their own bucket binding.
- R2 public delivery uses a user-configured custom domain. The URL is public to anyone who obtains it so Discord can retrieve the image; the Worker itself does not expose an object-list/read/delete API.
- Gecko's Android UI accepts the user's Worker endpoint, public asset origin and upload token. It stores only non-secret URLs in normal preferences and stores the token as AES-GCM ciphertext using a dedicated Android Keystore key and AAD domain.
- `ArtworkUploadPolicy` validates HTTPS Worker endpoints, public DNS hosts, asset URL paths and content-addressed hashes. `CloudflareArtworkUploader` enforces size, MIME/signature, response-size, response-hash and configured-origin checks and disables redirects.
- `GET /v1/verify` allows the app to authenticate the configured Worker without returning the secret. `POST /v1/upload` receives raw image bytes and stores the SHA-256-addressed object.
- Existing Imgur URL records remain readable for migration/backwards compatibility; new Android Gallery, Studio and automatic artwork uploads use the configured Cloudflare Worker.
