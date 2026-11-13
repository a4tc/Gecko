# Gecko Presence 0.6.2 — Delivery status

| Work package | Status | Evidence / remaining gate |
|---|---|---|
| Direct RPC transport migration | Implemented in source | OAuth/refresh-token path removed; native init readiness used. Requires real AAR/device verification. |
| Native Android foreground source | Implemented in source | UsageEvents + explicit allowlist + sensitive app rules; Samsung Android 14 device QA remains. |
| Native media-session source | Implemented in source | MediaSessionManager through opt-in NotificationListenerService; title sharing off by default. Device QA remains. |
| Native browser hostname source | Implemented in source | Optional AccessibilityService; address-bar ID matching only. Browser/OEM compatibility remains unverified. |
| Candidate priority and expiry | Implemented in source | Manual > media > Android app > website defaults; expiry and arbitration core tests. |
| Presence Studio | Implemented in source | Manual editor, local mock preview, apply/clear override, and package-keyed per-app profiles. |
| Local preset library | Implemented in source | Local JSON-backed presets with save/apply/delete. |
| Source priority automation | Implemented in source | Per-source priority controls; manual priority fixed at 100. |
| Native category navigation | Implemented in source | Dashboard, Sources, Studio, Automation, Library, Settings. Runtime screenshots and accessibility QA remain. |
| Extension/XPI runtime removal | Implemented in source | Legacy HTTP bridge, pairing store, extension export UI, and XPI CI steps removed. Research archive retained separately. |
| Discord SDK publishing | Conditional | Requires official AAR, Application ID, Discord app on device, and end-to-end verification. SDK callbacks are now pumped on the Android main looper. |
| Crash diagnostics | Implemented in source | Durable Java crash reports, previous-process `ApplicationExitInfo`, native tombstone capture where Android provides it, and in-app ZIP export. |
| Artwork/buttons/party fields | Not implemented | Do not show unsupported controls until SDK API mapping is verified. |

## Verification in this environment

- Pure Kotlin core policy tests pass, including per-app profile defaults, overrides, whitespace fallback, and bounded text. The Android/NDK build was not run in this environment.
- XML parsing, five-method JNI symbol parity, main-looper callback scheduling, crash-diagnostic integration, per-app profile wiring, signature-protected receivers, native-only runtime checks, and target-SDK-35 inset checks: passed by `GeckoPresenceHost/scripts/verify-source.sh`.
- Discord SDK field mapping checked against current official documentation; the registered application name is portal-controlled, and the app now maps source label + details into `Activity.details`.
- Full Android Gradle build and device/Discord validation remain release gates. The local Gradle wrapper could not download Gradle because network DNS resolution is unavailable; GitHub Actions must verify Kotlin/NDK compilation.
- Full APK build not available in this environment because Android SDK and Gradle distribution are not installed locally and external network resolution is unavailable.
- No device screenshots or live Discord result are available; visual and transport QA must not be claimed as complete.

## Release gates

1. GitHub Actions passes Android unit tests and assembles the APK.
2. Install on Android 14 and verify engine start/stop and foreground notification lifecycle.
3. Verify selected-app allowlist, sensitive-app rejection, and source arbitration.
4. Grant Notification Access and verify active media session detection with title sharing off, then on.
5. Grant Accessibility to the app and test Firefox, Tor Browser, Vivaldi, and other selected browsers individually. Confirm hostname-only behavior and that unsupported browser versions fail closed.
6. Configure the official AAR and Application ID, then verify a real Discord update and clear.
7. Test screen-off behavior, process recreation, battery restrictions, and source expiry on device.


## 0.6.2 Android SDK bootstrap follow-up

- Register `DiscordSocialSdkInit.setEngineActivity(Activity)` from the main Activity before the service can call native `Client` construction.
- Remove the wrapper library’s competing `JNI_OnLoad`; capture the VM via `JNIEnv::GetJavaVM()` in `nativeInit()` so ART can resolve the Discord SDK dependency’s JNI bootstrap hook.
- Gate direct RPC initialization on successful Activity registration.
- Clear the registered Activity when the host Activity is finishing.
- Keep preview builds independent of the optional proprietary AAR by resolving the bootstrap entry point reflectively.
- Required device validation: verify the native `Client` constructor no longer aborts on the WebSocketClient thread, then verify a Rich Presence update appears in Discord. The suspected `JNI_OnLoad` shadowing root cause still requires that real-device confirmation.
