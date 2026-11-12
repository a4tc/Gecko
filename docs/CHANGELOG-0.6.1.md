# Gecko Presence 0.6.1

## Added

- Per-selected-Android-app Studio profiles with activity label, details, and optional state.
- Private, local crash reports and Developer Console export, including Android `ApplicationExitInfo` metadata and native tombstone protobufs when available.
- Synchronous diagnostic checkpoints around native SDK library loading, JNI initialization, RPC updates, and shutdown.

## Changed

- Discord SDK callback processing now runs on the Android main looper through a bounded `Handler` pump instead of a detached native thread.
- Discord SDK entry points are serialized on the main looper.
- Source profile resolution preserves the selected-app allowlist and sensitive-app exclusions.

## Compatibility and limits

- The registered Discord application title remains controlled by the Discord Developer Portal. Per-app activity labels are rendered in the supported activity details line.
- Local source monitoring remains independent from Discord SDK initialization.
- Crash reports and tombstones remain in private app storage until the user copies, shares, or exports them. Gecko does not upload diagnostics.
- Android Gradle/NDK build and real-device Direct RPC behavior must be verified by GitHub Actions and on-device testing; source-level checks alone do not establish a successful APK build or prove the original crash's root cause.
