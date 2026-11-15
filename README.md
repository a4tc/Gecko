# Gecko Presence — Android application

Gecko Presence is a native Android application for local activity detection, per-app presence profiles, and Discord Rich Presence publishing. Version **0.6.6** replaces the official Discord Social SDK/JNI adapter with a Kotlin/Ktor WebSocket publisher and connects the locally encrypted Discord session vault to that publisher.

## Runtime modules

- `PresenceEngineService`: foreground source lifecycle, presence arbitration, candidate expiry, and Gateway publisher coordination.
- `DiscordGatewayPublisher`: single WebSocket owner; Hello/Identify, heartbeat/ACK, Ready/Resume, reconnect backoff, clear activity when the active winner disappears, and rate-limited update coalescing. An explicit disconnect closes the socket without sending a forced online status.
- `DiscordGatewayProtocol`: independently tested wire payload builders for Identify, Heartbeat, Resume, and Presence Update.
- `DiscordCredentialVault`: local AES-256-GCM encryption using a non-exportable Android Keystore key; plaintext is loaded only when the user explicitly connects.
- `DiscordLoginActivity`: isolated first-party Discord web-session import. This is **not OAuth2** and is not an official Discord authentication interface.
- `UsageAppTracker`: selected foreground packages from Usage Access; no UI scraping or usage-history persistence.
- `MediaSessionPresenceTracker`: active media sessions through the user-enabled Notification Listener; notification contents are not read.
- `BrowserAccessibilityService`: selected-browser address-bar host detection only; no page DOM or body text extraction.
- `PresencePriorityPolicy`: user-adjustable automatic source priorities; manual presence is fixed at 100.
- `PresencePresetStore`: local-only saved presets, including the advanced manual Studio fields.
- `AppPresenceProfileStore`: package-keyed profiles with activity type, name, details, state, stream URL, large/small artwork, buttons, timestamps, priority override, enable switch, and optional account-status override.
- `ArtworkGalleryStore`: bounded app-private image gallery with SHA-256 deduplication and tamper checks. Local images are never uploaded automatically.
- `ImgurAnonymousUploader`: optional user-configured Imgur Client-ID upload; only explicitly confirmed uploads leave the device and become publicly reachable.

## Gateway compatibility note

The publisher uses Ktor 2.3.13 and follows the documented Gateway v10 message lifecycle where applicable. The user-account Identify payload fields follow the public Kizzy implementation because Discord's public Gateway documentation is written for bot/application clients and does not document this user-session flow. `capabilities` and camelCase `largeThreshold` are compatibility-sensitive, undocumented client-session fields. Discord may change or reject them. The use of a normal user account through a Gateway client may violate Discord's rules and can expose the account to enforcement. This project does not claim official Discord support or guaranteed operation.

The session import is a local WebView-based bearer-session import, not OAuth2. It only runs after the user signs in and explicitly presses Import. The session is encrypted locally and never sent to Gecko servers. Do not share the imported session credential or build with untrusted parties.

## Permissions

All automation sources default off. The `secure` flavor excludes Usage Access, Notification Listener, and Accessibility service declarations. The `automation` flavor adds those optional capabilities. Browser detection needs Accessibility's window-content capability because Android exposes the address field through `AccessibilityNodeInfo`; the implementation restricts reads to selected browser packages and known address-bar resource IDs.

## Local tests

```sh
bash scripts/test-core.sh
bash scripts/verify-source.sh
bash scripts/security-audit.sh
bash scripts/test-imgur-upload-dynamic.sh
```

Full Android build (when the Android toolchain is installed):

```sh
gradle --no-daemon :app:testSecureDebugUnitTest :app:testAutomationDebugUnitTest
gradle --no-daemon :app:assembleSecureDebug :app:assembleAutomationDebug
```

The project uses JDK 17, Gradle 8.10.2, Android SDK Platform 35, AGP 8.8.2, and Kotlin 2.0.21. The source archive does not include `gradle-wrapper.jar`; use an installed Gradle 8.10.2 (as in the commands above) or the included GitHub Actions workflow. Ktor is pinned to 2.3.13 to remain on the Kotlin 2.0-compatible 2.x line; the Ktor 3.3+ line has moved its documented baseline to Kotlin 2.2 and later. The targeted local dynamic tests exercise the uploader against a loopback HTTP server and fuzz the pure protocol/media policies; they are not a full device penetration test. See [docs/SECURITY-PENTEST-0.6.6.md](docs/SECURITY-PENTEST-0.6.6.md) for findings and limitations.

## Known limits

- Real Discord session login, Gateway Identify, Resume, and publishing require an on-device test with the user's own account. No user credential is embedded in this source package.
- The exact user-session Identify flow is undocumented and can change independently of the documented bot Gateway protocol.
- Manual Studio and per-app profiles expose all six activity types, Twitch/YouTube stream URL, large/small artwork with tooltips, up to two HTTPS buttons, optional start/end timestamps, and account status. Per-app profiles additionally support priority overrides and enable/disable.
- Gallery imports are private/local. To make an image visible to Discord, configure your own Imgur Client-ID in Settings and explicitly approve public upload. Gecko does not need your Imgur password or Client Secret. Imgur anonymous uploads are not private; the resulting URL is public to anyone who has it.
- The publisher has inbound frame bounds and a HELLO timeout. Real Discord Gateway acceptance and rich-presence rendering remain device-test requirements.
- Rooted-device and runtime-instrumentation resistance is not guaranteed by Android Keystore encryption alone.
