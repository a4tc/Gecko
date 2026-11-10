# Gecko Presence — Android app

The Android application is the primary product and the only required APK. It owns local settings, foreground Android app tracking, the localhost bridge, secure pairing storage, Discord SDK adapter, and an embedded browser-extension bundle that can be exported to XPI on demand.

## What the APK does

- Tracks only explicitly selected, launchable Android apps through Android Usage Access.
- Provides a searchable allowlist with app icons and package names.
- Rechecks the privacy denylist when foreground events arrive; saved selections cannot override blocked categories.
- Uses `127.0.0.1:32145` as the only HTTP bind address.
- Stores the bridge secret with Android Keystore AES-256-GCM.
- Exports the bundled Gecko extension to an unsigned `.xpi` when the user selects **EXPORT FIREFOX XPI**.
- Imports/restores a copied 256-bit pairing key so a debug-signature migration does not have to break the browser pairing.
- Gives one Android setting control over whether browser media titles may be passed to Discord.
- Uses the SIGNAL DECK black/yellow brutalist design system.

## What Android tracking can observe

`UsageStatsManager.queryEvents` reports foreground app transitions. It does not reveal another app's movie title, music metadata, messages, current UI, or notification text. The app therefore reports a conservative label/template for selected apps. A richer title requires a documented app-owned API or an explicitly approved integration. This product does not use Accessibility scraping or Notification Listener access.

## Discord integration gate

The conditional native integration is present in Kotlin/JNI/CMake form, but the official `discord_partner_sdk.aar` is not included. Discord distributes the AAR from its Developer Portal onboarding after approval. The user must also supply the numeric Application ID from their own Discord application.

Discord's current mobile Social SDK announcements and documentation describe support for mobile **games**. Approval for a general-purpose presence companion is unconfirmed. Do not assume that a submitted companion application will be approved.

To enable the SDK build:

1. Complete the official Discord Social SDK onboarding and obtain the official Android AAR.
2. Keep the AAR in an access-controlled/private repository or artifact store.
3. Set GitHub repository variable `DISCORD_APP_ID` to the numeric application ID.
4. Optionally set `DISCORD_SDK_AAR_PATH` to the AAR path relative to the repository root, e.g. `private-sdk/discord_partner_sdk.aar`.
5. Run the root suite workflow.

When either SDK input is absent, the workflow intentionally builds Preview mode. The preview supports the local bridge, Android tracking, allowlist, presence preview, and bundled XPI export; it does not claim Discord publishing is connected.

## Build and verify

From the combined suite root:

```sh
cd GeckoPresenceBridge
npm install --no-audit --no-fund
npm test
cd ../GeckoPresenceHost
sh scripts/test-core.sh
```

The full APK build uses the root `.github/workflows/build.yml` workflow, JDK 17, Gradle 8.10.2, Android SDK Platform 35, NDK and CMake. The current container session does not contain Android SDK/Gradle, so the APK must be built by GitHub Actions before device installation.

See the root `docs/` directory for architecture, privacy contract, design system, research and release gates.
