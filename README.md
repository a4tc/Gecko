# Gecko Presence — Android application

Gecko Presence is a native Android application for local activity detection, per-app presence profiles, and Discord Rich Presence publishing. Version **0.6.6** replaces the official Discord Social SDK/JNI adapter with a Kotlin/Ktor WebSocket publisher and connects the locally encrypted Discord session vault to that publisher.

## Runtime modules

- `PresenceEngineService`: foreground source lifecycle, presence arbitration, candidate expiry, and Gateway publisher coordination.
- `DiscordGatewayPublisher`: single WebSocket owner; Hello/Identify, heartbeat/ACK, Ready/Resume, reconnect backoff, clear activity when the active winner disappears, and rate-limited update coalescing. An explicit disconnect closes the socket without sending a forced online status.
- `DiscordGatewayProtocol`: independently tested wire payload builders for Identify, Heartbeat, Resume, and Presence Update.
- `DiscordCredentialVault`: local AES-256-GCM encryption using a non-exportable Android Keystore key; plaintext is loaded only when the user explicitly connects.
- `DiscordLoginActivity`: isolated first-party Discord web-session import. This is **not OAuth2** and is not an official Discord authentication interface.
- `UsageAppTracker`: selected foreground packages from Usage Access; no UI scraping or usage-history persistence.
- `MediaSessionPresenceTracker`: event-driven active media sessions through the user-enabled Notification Listener, with controller callbacks, a recovery poll, a missing-session grace period and no notification-content collection.
- `BrowserAccessibilityService`: selected-browser address-bar host detection only; no page DOM or body text extraction.
- `PresencePriorityPolicy`: user-adjustable automatic source priorities; manual presence is fixed at 100.
- `PresencePresetStore`: local-only saved presets, including the advanced manual Studio fields.
- `AppPresenceProfileStore`: package-keyed profiles with activity type, name, details, state, stream URL, large/small artwork, buttons, timestamps, priority override, enable switch, and optional account-status override.
- `ArtworkGalleryStore`: bounded app-private manual artwork gallery with SHA-256 deduplication and tamper checks.
- `ArtworkAutoPublisher`: media artwork URI/bitmap extraction, app-icon fallback and a separate bounded SHA-256 URL cache. Automatic uploads do not consume the manual Gallery quota; they are off by default and require standing public-upload consent.
- `ArtworkUploadConfigStore` and `ArtworkUploadCredentialVault`: per-device Worker configuration and an encrypted upload-token vault using a dedicated Android Keystore key.
- `CloudflareArtworkUploader`: native HTTPS upload and token-verification client for each user's own Cloudflare Worker + R2 bucket. It validates the content-addressed result before adding the URL to Rich Presence.
- `ImgurAnonymousUploader`: retained for legacy uploader tests only; new Android upload flows use the configured Cloudflare Worker.

## Gateway compatibility note

The publisher uses Ktor 2.3.13 and follows the Gateway v9 message lifecycle where applicable. The user-account Identify payload fields follow the public Kizzy implementation because Discord's public Gateway documentation is written for bot/application clients and does not document this user-session flow. `capabilities` and camelCase `largeThreshold` are compatibility-sensitive, undocumented client-session fields. Discord may change or reject them. The use of a normal user account through a Gateway client may violate Discord's rules and can expose the account to enforcement. This project does not claim official Discord support or guaranteed operation.

The Home runtime control combines engine startup and Gateway connection as Turn On; Turn Off stops both. Sources and Studio drafts can be configured while the runtime is off without starting a partial service. Once a manual Studio override is active, field edits are debounced and synchronized automatically.

The session import is a local WebView-based bearer-session import, not OAuth2. It only runs after the user signs in and explicitly presses Import. The session is encrypted locally and never sent to Gecko servers. Do not share the imported session credential or build with untrusted parties.

## Permissions

All automation sources default off. The `secure` flavor excludes Usage Access, Notification Listener, and Accessibility service declarations. The `automation` flavor adds those optional capabilities. Browser detection needs Accessibility's window-content capability because Android exposes the address field through `AccessibilityNodeInfo`; the implementation restricts reads to selected browser packages and known address-bar resource IDs.

## Local tests

```sh
bash scripts/test-core.sh
bash scripts/verify-source.sh
bash scripts/security-audit.sh
bash scripts/test-imgur-upload-dynamic.sh
cd gecko-cloudflare-artwork-worker && npm test && npm run typecheck
```

Full Android build (when the Android toolchain is installed):

```sh
gradle --no-daemon :app:testSecureDebugUnitTest :app:testAutomationDebugUnitTest
gradle --no-daemon :app:assembleSecureDebug :app:assembleAutomationDebug
```

The project uses JDK 17, Gradle 8.10.2, Android SDK Platform 35, AGP 8.8.2, and Kotlin 2.0.21. The source archive does not include `gradle-wrapper.jar`; use an installed Gradle 8.10.2 (as in the commands above) or the included GitHub Actions workflow. Ktor is pinned to 2.3.13 to remain on the Kotlin 2.0-compatible 2.x line; the Ktor 3.3+ line has moved its documented baseline to Kotlin 2.2 and later. The targeted local dynamic tests exercise the Imgur legacy uploader against a loopback HTTP server and fuzz the pure protocol/media policies; they are not a full device penetration test. See [docs/SECURITY-PENTEST-0.6.6.md](docs/SECURITY-PENTEST-0.6.6.md) for findings and limitations. The visual system is documented in [docs/GECKO-DESIGN-SYSTEM.md](docs/GECKO-DESIGN-SYSTEM.md), with Kizzy research notes in [docs/KIZZY-REFERENCE.md](docs/KIZZY-REFERENCE.md).

## Known limits

- Real Discord session login, Gateway Identify, Resume, and publishing require an on-device test with the user's own account. No user credential is embedded in this source package.
- The exact user-session Identify flow is undocumented and can change independently of the documented bot Gateway protocol.
- Manual Studio and per-app profiles expose all six activity types, Twitch/YouTube stream URL, large/small artwork with tooltips, up to two HTTPS buttons, optional start/end timestamps, and account status. Per-app profiles additionally support priority overrides and enable/disable.
- Gallery imports are private/local. To make artwork visible to Discord, configure your own Cloudflare Worker and R2 public asset domain in Settings. Individual uploads require per-image approval; detected media covers and selected-app icons can be automatically uploaded only after a separate standing-consent prompt. R2 URLs are public to anyone who has them. Removing local settings or revoking consent does not delete objects already uploaded to your R2 bucket.
- The Worker upload token is stored locally as encrypted ciphertext with a dedicated Android Keystore key; the app never asks for Cloudflare account credentials or embeds a shared upload secret.
- The publisher has inbound frame bounds and a HELLO timeout. Real Discord Gateway acceptance and rich-presence rendering remain device-test requirements.
- Rooted-device and runtime-instrumentation resistance is not guaranteed by Android Keystore encryption alone.
