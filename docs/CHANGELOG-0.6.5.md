# Gecko Presence 0.6.5

## Gateway transport

- Added `DiscordGatewayProtocol` for Identify, Heartbeat, Resume, and Presence Update payload generation.
- Added Ktor 2.3.13 CIO WebSocket publisher with Hello/Ready/Resumed handling, heartbeat jitter/ACK tracking, invalid-session recovery, safe resume URL validation, reconnect backoff, and explicit disconnect handling. A user disconnect closes the local socket without sending an empty activity or forcing the account status back to online.
- Added update coalescing and a conservative minimum interval to stay below Discord's documented five-game-status-updates-per-20-seconds limit.
- Connected the existing Android Keystore vault to explicit Developer Console connect/disconnect actions. The local presence engine does not auto-connect on process start.
- Mapped media-session sources to Listening and other current sources to Playing; timestamps are converted from seconds to Gateway milliseconds.
- Removed Discord Social SDK AAR, JNI/C++, CMake, and native bootstrap code from the Android application module.
- Updated dashboard, developer console, diagnostics, build identity, and source contract checks to report Gateway rather than SDK state.

## Compatibility and security

- Ktor remains on the 2.3.x branch because the app's Kotlin Gradle plugin is 2.0.21; 2.3.13 is the latest 2.x patch listed by Ktor's release notes. Ktor 3.3+ requires newer Kotlin baselines.
- User-session Identify compatibility fields follow the Kizzy source pattern and are explicitly documented as undocumented/unsupported client-session behavior.
- Gateway payload byte size, supported HTTPS URL types, activity field lengths, maximum two buttons, timestamps, and activity state are validated before publication.
- No token, frame contents, Gateway URL query, server close reason, or exception message is written to application logs.

## Studio and local presets

- Added Studio controls for all six activity types, account status, Twitch/YouTube streaming URL, large/small image reference and tooltip, up to two HTTPS buttons, and optional start/end timestamps.
- Manual activity fields are validated as a complete `PresenceActivity` before they are persisted and applied.
- Saved Library presets now retain the advanced Studio fields in a bounded local JSON object.
- Studio's preview updates its activity type and selected account status. It remains a local preview, not proof of remote rendering.
- Per-app profiles still customize label, details, and state only; advanced per-app overrides are not implemented yet.

## Still pending / unverified

- Live Discord login/import and user-session Identify/Resume on Samsung A04 Android 14.
- Actual server acceptance and client rendering for every activity type, artwork URL, buttons, and timestamp combination.
- Image upload/asset registration pipeline. Artwork references may use public media URLs; gallery uploads are not performed automatically.
- Full Android Gradle build, APK signing, R8 verification, dynamic pentest, and Keystore hardware-backed attestation.

## Additional hardening pass

- INVALID_SESSION now waits a randomized one-to-five seconds for both resumable and non-resumable sessions.
- Credential ciphertext envelopes are size-bounded before Base64 decoding.
- Gateway outbound payload validation measures UTF-8 bytes; oversized Rich Presence payloads produce a clear local error.
- Authentication close code 4004 removes the stale encrypted credential from Android Keystore-backed storage.
- Failed connection-loop handshakes close the half-open WebSocket before retrying.
- Studio preview now reflects the selected activity type and account status; URL fields use URI keyboard input without sentence capitalization.
- Studio preview and guidance now follow Discord's Gateway type-specific Activity formatting, rather than implying that `activity.name` always becomes the visible first line.
- Added `docs/SECURITY-PENTEST-0.6.5.md`, explicitly separating source-level verification from dynamic device penetration testing.


## Follow-up correctness refinements

- Studio now supports optional custom start and end timestamps (Unix seconds), persists them through presets/service restart, and permits blank Details when the selected activity type allows it.
- Studio preview follows the official Gateway Activity type format: Playing/Listening/Watching/Competing use `name`, Streaming uses `details`, and Custom Status uses `state`.
- Per-app profile guidance no longer implies that `activity.name` always becomes the visible first line.
- Added pure Kotlin regression tests for all six type-specific preview formats.
