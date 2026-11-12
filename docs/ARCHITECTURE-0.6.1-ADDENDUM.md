# Gecko Presence 0.6.1 — architecture addendum

This addendum describes the changes made on top of the 0.6.0 native architecture. The existing source arbitration and Direct RPC design remain in place.

## Discord Social SDK lifecycle

- `DiscordSdk` owns the native client lifecycle and serializes calls on Android's main looper.
- `RunCallbacks()` is invoked from a `Handler` callback every 50 ms while initialized. The former detached C++ worker thread is removed.
- Initialization remains user-initiated from Developer Console; starting local source monitoring does not load Discord's native library.
- Checkpoints are synchronously persisted immediately before library loading, before JNI initialization, and at native client construction milestones.
- This aligns callback pumping with Discord's documented application main-loop model. It is a lifecycle correction, not proof of the previously reported crash's root cause; actual-device RPC verification remains required.

## Per-app Studio profiles

`AppPresenceProfileStore` keeps one small profile per package in private `SharedPreferences`. A profile contains:

- `activityLabel`: source label displayed in the supported activity details line.
- `details`: app-specific activity description.
- `state`: optional secondary line.

`AppPresencePolicy` resolves the stored profile only after the package passes the existing selected-app allowlist and sensitive-app filters. Blank values fall back to the detected app label and app-specific default details. Text is normalized and bounded before becoming a `PresenceUpdate`.

The registered Discord application's name remains controlled by the Developer Portal. Gecko does not claim it can override Discord's app identity through the Android partial-activity transport.

## Crash diagnostics

`GeckoPresenceApplication` installs a durable uncaught-exception writer and queries `ActivityManager.getHistoricalProcessExitReasons()` at startup on Android 11+. Reports are kept in the app's private files directory with a bounded history. When Android provides a native crash trace, Gecko stores the returned protobuf tombstone with the matching exit report.

Developer Console exposes the last checkpoint and latest report. Users can copy/share the text report or export a ZIP containing report text and native tombstone files using the system document picker. Diagnostics are local-only; Gecko does not upload crash data.

## Reference-derived design principles

The project reviewed Kizzy, Kizzy Enhanced, Axe, and Kizzey for app-profile concepts, source lifecycle, crash diagnostics, and reliability rules. No third-party implementation files were copied into Gecko. The Kizzey reference notes emphasize tracing the whole send/clear path and limiting changes to the affected component; Gecko applies that principle to the single source-arbiter-to-SDK path.

The Gateway/token-based connection paths in those projects are not adopted. Gecko continues to use the official Discord Social SDK's Android Direct RPC transport.
