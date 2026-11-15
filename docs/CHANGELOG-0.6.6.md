# Gecko Presence 0.6.6

## Advanced per-app profiles

- Expanded package-scoped profiles from name/details/state to the full transport-neutral activity model.
- Added per-app activity type, stream URL, large/small artwork references and tooltips, up to two buttons, optional start/end timestamps, priority override, enable switch, and optional account-status override.
- Added profile validation for text lengths, URL safety, type-specific requirements, button count, timestamps, priority range, and account-status values.
- Added versioned JSON profile persistence with bounded reads and backward-compatible defaults for profiles saved by earlier versions.
- App-specific account status is applied only while that package wins source arbitration; otherwise the global account status remains in effect.

## Artwork gallery and explicit upload

- Added private local artwork gallery with 8 MiB per-image, 40-image, and 64 MiB aggregate limits.
- Supports JPEG, PNG, and WebP only; checks file signatures, dimensions (maximum 40 megapixels), canonical file paths, file size, and SHA-256 integrity.
- Duplicate images are detected by SHA-256.
- Added Imgur anonymous image upload using the user's own registered public Client-ID. No Imgur password, Client Secret, OAuth token, Discord credential, or browser cookie is sent.
- Upload is explicit and confirmation-gated. UI explains that uploaded images are publicly reachable. Local files remain private until upload.
- Upload client uses a fixed HTTPS production endpoint, no redirects, bounded timeouts, bounded response size, and an `i.imgur.com` returned-host allow-list.
- Added Gallery UI actions to apply uploaded images to large/small artwork fields and delete local copies. Deleting a local copy does not delete its remote Imgur image.

## Gateway publisher hardening

- Configured Ktor WebSockets and each session with a 64 KiB inbound frame cap.
- Added a 15-second WebSocket connect/upgrade timeout and a separate 15-second HELLO handshake timeout; a half-open connection is closed and retried without applying an idle timeout to the established socket.
- Kept UTF-8 frame-size checks, 4,096-byte outbound payload validation, send mutex, conflated/coalesced updates, heartbeat ACK tracking, reconnect backoff, and session invalidation policy.
- Gateway frame bodies, tokens, URLs, and close reasons remain excluded from logs.

## Targeted dynamic security tests

- Added deterministic property/fuzz tests: 25,000 Rich Presence payload cases, 10,000 invalid-session retry-window cases, and 20,000 malformed image-input cases.
- Added a loopback HTTP integration harness for the actual Imgur uploader: multipart structure, Client-ID-only authorization, cookie isolation, no redirect following, returned-host validation, response-size cap, invalid Client-ID/endpoint rejection, and oversized-file rejection.
- Static security assertions increased to 101. Source contract checks are at 98 assertions.
- CI now installs a checksum-pinned Kotlin 2.0.21 CLI for the standalone Kotlin fuzz and dynamic uploader harnesses before running Android Gradle tasks.

## Verification boundaries

- Pure Kotlin policy and protocol tests pass.
- Targeted local uploader integration test passes.
- New gallery/profile/uploader Kotlin files compile against temporary Android/JSON API stubs; Gateway publisher compiles against temporary Ktor/Android API stubs. These checks catch Kotlin syntax/type-shape errors but do not replace Gradle compilation against the actual Android SDK and pinned Ktor artifacts.
- Full Android Gradle build, APK install, Android unit tests, Samsung A04 testing, real Imgur upload, live Discord Gateway session, Frida/Objection runtime instrumentation, and MobSF APK analysis remain unverified.
