# Gecko Presence — Signal Deck 0.6.0

A native-first Android Rich Presence controller. The Android APK owns source discovery, activity composition, local presets, priority arbitration, preview, diagnostics, and Discord publishing. There is no browser extension, XPI, loopback HTTP bridge, browser pairing key, OAuth login, or Discord user-token flow in the runtime.

## Native source adapters

- **Android apps:** opt-in Usage Access plus an explicit allowlist. Only the selected foreground app is retained; sensitive categories and browser packages are excluded.
- **Media sessions:** opt-in Notification Access through `MediaSessionManager`. Notification content is not read. Media title/artist sharing is a separate setting and is off by default.
- **Browser / website:** optional Accessibility Service for selected browsers. It searches only known address-bar view IDs and emits a normalized public hostname. It does not inspect DOM, page text, form fields, passwords, paths, query parameters, or history. Some browser versions do not expose a stable address-bar node; compatibility is device/browser dependent.
- **Custom Presence:** a manual override with a local mock preview and saved local presets. Manual presence always wins over automatic sources.

## Product navigation

1. Dashboard
2. Sources
3. Presence Studio
4. Automation
5. Library
6. Settings & Diagnostics

The current Discord native adapter publishes a source/details line, optional state, and start timestamp. The first `Playing [application name]` line is controlled by the Discord Developer Portal registration. Artwork, buttons, party data, and other optional Discord fields are intentionally not presented as supported controls until the AAR mapping is implemented and verified.

## Privacy and permissions

All sources are off by default. The app requests Usage Access, Notification Access, or Accessibility access only when the user enables the corresponding source. Browser detection uses a package allowlist and known address-field identifiers; if the browser hides the field, the adapter returns no candidate. Sensitive domains are rejected before a presence candidate is accepted. Candidate data is in-memory and expires when the source stops refreshing.

See `docs/ARCHITECTURE-0.6.0.md`, `docs/PRIVACY-CONTRACT.md`, and `GeckoPresenceHost/docs/SECURITY.md`.

## Build

Run the Android-only workflow `.github/workflows/gecko.yml`. It tests the pure Kotlin core and builds the APK with JDK 17, Gradle 8.10.2, Android SDK 35, NDK 27.2.12479018, and CMake 3.22.1.

For Direct Rich Presence, supply the official `discord_partner_sdk.aar` from Discord's Developer Portal and set the repository variable `DISCORD_APP_ID`. For a local build, copy the AAR to `GeckoPresenceHost/app/libs/discord_partner_sdk.aar`; keep it in private or access-controlled storage. Without both inputs, CI builds local Preview mode; Preview is not evidence of Discord publishing.

```sh
cd GeckoPresenceHost
sh scripts/test-core.sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

## Source map

- `GeckoPresenceHost/`: Android application, native source adapters, service, UI, SDK JNI adapter.
- `GeckoPresenceResearch/`: retained PreMiD/import research and third-party notices only; not built, packaged, loaded, or executed by the app.
- `docs/ARCHITECTURE-0.6.0.md`: current native architecture.
- `docs/PRIVACY-CONTRACT.md`: source-by-source data and permission contract.
- `docs/DESIGN-SYSTEM.md`: SIGNAL DECK visual direction.
