# Android build and Discord Direct Rich Presence

## Native-only build

`.github/workflows/gecko.yml` runs the pure Kotlin core tests and builds one Android APK. It does not install Node, compile a browser extension, export an XPI, or bundle browser runtime assets.

Toolchain: JDK 17, Gradle 8.10.2, Android SDK Platform 35, Build Tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1.

## Discord Social SDK

The SDK is optional at build time and required for actual Discord publishing:

- Official `discord_partner_sdk.aar` obtained from Discord Developer Portal onboarding.
- Numeric `DISCORD_APP_ID` configured as a GitHub repository variable or Gradle property. The source tree includes the known Application ID as a default; a repository variable can override it.

The workflow copies an AAR from the configured `DISCORD_SDK_AAR_PATH`, or from the repository root if present, into `GeckoPresenceHost/app/libs/discord_partner_sdk.aar`. CI installs Gradle 8.10.2 with `gradle/actions/setup-gradle`; the local `gradlew` launcher downloads that same distribution and verifies its published SHA-256. Keep the AAR in private/access-controlled storage and comply with its license.

When either input is absent, the build is local Preview mode. It can render activity locally and exercise source adapters but cannot claim Discord publishing.

For the local SDK-enabled build, copy the AAR already obtained from Discord into the ignored app library path, then build:

```sh
cd GeckoPresenceHost
mkdir -p app/libs
cp "$HOME/discord_partner_sdk.aar" app/libs/discord_partner_sdk.aar
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

## Direct RPC behavior

The Android client sets its Application ID and calls the Social SDK's direct `UpdateRichPresence` API. It does not run OAuth authorization, does not call the authenticated `Connect()` flow, and does not persist a Discord user token. The SDK's callback result is surfaced as a presence error when Discord rejects an update.

The current mapping combines the source/activity label and details into Discord's `Activity.details` line, maps optional state and start timestamp, and leaves the registered application name to Discord. Artwork, buttons, party data, and other fields remain unavailable in the UI until implemented and verified against the supplied AAR.

## Release verification

CI success proves compilation and tests, not live Discord publishing. A device test must confirm Discord is installed and signed in, the configured Application ID is enabled, and an update appears in the expected profile/activity surface.
