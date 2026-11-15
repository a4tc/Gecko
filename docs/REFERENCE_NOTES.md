# Reference notes

Current 0.6.6+ implementation references:

- Discord Gateway v10 public documentation for Hello, Heartbeat, Identify, Resume, Presence Update, and close-code behavior. The public docs primarily describe application/bot clients; Gecko's user-session Identify compatibility fields are not documented by Discord.
- Public Kizzy Android source is used only as a behavioral reference for the observed user-session Gateway shape and local Discord web-session import pattern. Kizzy's GPL-3.0 source is not copied into Gecko.
- Ktor 2.3.13 client WebSockets documentation and API surface are used for the Kotlin WebSocket transport. This version remains on the 2.x line to avoid an unverified Kotlin toolchain migration from Kotlin Gradle Plugin 2.0.21.
- Android Developers documentation for WebView data-directory isolation and Android Keystore AES/GCM key management informs the local session vault.

Gecko 0.6.5 removed the Discord Social SDK AAR, JNI bridge, CMake project, and native Discord SDK bootstrap. The transport is Kotlin/Ktor only.


Artwork hosting notes (0.6.6):

- Imgur API v3 documents anonymous uploads using `Authorization: Client-ID {client_id}` after registering an application. Gecko sends only the selected image file and that public client identifier; it does not request Imgur OAuth access or account credentials.
- Android's `ACTION_OPEN_DOCUMENT` picker grants a temporary URI read permission. Gecko copies the bounded image bytes into app-private storage immediately rather than persisting an external URI grant.
- `ArtworkMediaPolicy` owns magic-byte, dimension, file-name, SHA-256, Client-ID, and returned-host validation.
- `scripts/test-imgur-upload-dynamic.sh` uses a loopback HTTP server and an isolated JSON test stub to exercise the actual uploader's multipart and network boundary. It does not contact Imgur.
