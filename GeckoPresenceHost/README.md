# Gecko Presence — Android application

The Android application is the complete product runtime. It owns navigation, source configuration, native source adapters, priority arbitration, manual presence, local presets, mock preview, diagnostics, and the optional Discord Social SDK Direct Rich Presence publisher.

## Runtime modules

- `PresenceEngineService`: foreground lifecycle, source arbitration, candidate expiry, and Discord publisher coordination.
- `UsageAppTracker`: selected foreground packages from Usage Access; no UI scraping or usage-history persistence.
- `MediaSessionPresenceTracker`: active media sessions through the user-enabled Notification Listener; notification contents are not read.
- `BrowserAccessibilityService`: selected-browser address-bar host detection only; no page DOM or body text extraction.
- `PresencePriorityPolicy`: user-adjustable automatic source priorities; manual presence is fixed at 100.
- `PresencePresetStore`: local-only saved presets.
- `DiscordSdk` + JNI/CMake: official Discord Social SDK AAR adapter. Preview builds work without the AAR but do not publish.

## Permissions

All sources default off. Usage Access, Notification Access, and Accessibility access are optional and independently controlled. Browser detection needs Accessibility's window-content capability because Android exposes the address field through `AccessibilityNodeInfo`; the implementation restricts reads to selected browser packages and known address-bar resource IDs. If no matching address-bar node is exposed, it reports no website candidate.

## Discord SDK build

Place the official `discord_partner_sdk.aar` at `app/libs/discord_partner_sdk.aar` and configure `DISCORD_APP_ID` as a Gradle property or GitHub repository variable. The AAR is not included in the source archive. Do not redistribute the AAR unless the SDK license permits it.

## Local tests

```sh
sh scripts/test-core.sh
```

Full Android build:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

The full Gradle build requires JDK 17, Gradle 8.10.2, Android SDK Platform 35, NDK 27.2.12479018, and CMake 3.22.1.
