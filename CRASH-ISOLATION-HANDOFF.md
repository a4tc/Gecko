# Gecko Presence 0.6.0 — crash isolation and Developer Console update

## Changes
- Local source-engine startup no longer initializes Discord's native SDK.
- Discord Direct RPC initialization is only triggered by the explicit Developer Console connect action.
- Added explicit RPC connect/disconnect routing policy and core regression checks.
- Added guarded service actions and source-broadcast handling with runtime event diagnostics.
- Fixed the signature-protected internal broadcast senders so they provide the required permission.
- Added a bottom navigation shell with Home, Sources, Studio, Developer, and More destinations.
- Added a Developer Console with engine/RPC state, local activity payload, build identity, event log, and copy diagnostics.

## Verification
- `sh scripts/verify-source.sh` passes (68 core checks plus source-contract checks).
- The Android app APK has not been built in this environment: the Android SDK and Gradle wrapper JAR are not present.
- The reported device-side force-close has not been reproduced here because no Android logcat trace was available. The changes isolate native RPC initialization from normal source/toggle operations, but the exact crash root cause remains unconfirmed until tested on-device.

## Apply patch
From the repository root, run:

```bash
git apply --check GeckoPresence-0.6.0-crash-isolation-ui.patch
git apply GeckoPresence-0.6.0-crash-isolation-ui.patch
```

Then from `GeckoPresenceHost` run:

```bash
sh scripts/verify-source.sh
```

Build and install the debug APK through the repository's GitHub Actions workflow or a machine with Android SDK installed.
