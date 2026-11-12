# Reference research and clean-room boundaries

## PreMiD

PreMiD's publicly documented activity model is useful as a reference: a site-specific activity module reads a small amount of page context and maps it to a presence payload. The public Activity library and developer docs show concepts such as site identity, title/state, buttons, metadata and per-site modules.

Important source boundary: PreMiD's own README states that its activity definitions are open source while the main extension is not currently open source. This project therefore does not claim to reverse-engineer or reproduce the closed extension. GPB uses public documentation and its own native Android implementation; no browser bridge is part of the shipped runtime.

References:
- https://github.com/PreMiD/PreMiD
- https://github.com/PreMiD/Activities
- https://docs.premid.app/dev/presence

## Comparable bridge patterns

- `p00ya/browser-activity` demonstrates an extension/native bridge pattern for sending browser activity to Discord. It is a reference for the boundary only, not copied code.
  - https://github.com/p00ya/browser-activity
- `vinithreddybanda/activities` demonstrates an Android-first Discord presence project using Discord Social SDK concepts. Its license and current SDK compatibility must be inspected before any code reuse.
  - https://github.com/vinithreddybanda/activities

## GPB adapter contract (research-only reference)

Each site adapter should declare:

- Stable adapter ID and display name.
- Exact host patterns and permissions needed.
- Fields it reads (title, playback state, creator, category, etc.).
- Metadata extraction selectors/events.
- A strict privacy category and sensitive-host exclusion.
- Fixture-based tests for title parsing and state transitions.
- Fallback behavior when selectors or media APIs are unavailable.

No adapter may read cookies, credentials, messages, arbitrary page text, or unrelated DOM content. No remote JavaScript or runtime code downloading is permitted.

## Discord Social SDK

The official Social SDK is distributed as a native SDK package from Discord's Developer Portal. Android uses an AAR plus native/Prefab/CMake integration. GPB's Gradle file already treats the AAR and `DISCORD_APP_ID` as conditional inputs; it cannot manufacture the proprietary SDK binary or the user's Discord Application ID.

Current official documentation and onboarding are game-focused. The developer must obtain approval and confirm that a general-purpose presence companion is eligible. A build with no approved AAR intentionally remains Preview mode.

References:
- https://discord.com/developers/docs/social-sdk/overview
- https://discord.com/developers/docs/social-sdk/getting-started
- https://github.com/discord/discord-social-sdk

## Android Rich Presence references (research only)

Research performed on 2026-11-12. These projects were inspected for product behavior and architecture ideas; their transports and implementation code were not imported into Gecko.

| Project | Observed status | Useful reference | Gecko decision |
|---|---|---|---|
| [dead8309/Kizzy](https://github.com/dead8309/Kizzy) | Original Kotlin/Jetpack Compose app; its README documents Android 8.1–14 and a Discord Gateway/user-token warning. | Selected-package detection, profile settings, reusable presets, placeholders, media session handling. | Reuse the product concept of package-specific profiles, but retain Gecko's UsageEvents tracker and official Social SDK transport. |
| [milliarderr/Kizzy-Enhanced](https://github.com/milliarderr/Kizzy-Enhanced) | GitHub marks this fork archived. | Reconnect backoff, error isolation around detection loops, shorter detection interval, app-specific custom placeholders. | Treat as resilience inspiration only; don't import its Gateway/token logic or use it as a maintained base. |
| [sudoloser/Axe](https://github.com/sudoloser/Axe) | Public fork; latest commit returned by GitHub search was 2026-08-06. | Per-app RPC configurations; optional remote gateway proxy. | Adopt package-keyed local profile UX; reject remote token relay as outside Gecko's privacy and transport contract. |
| [UnpackedX/Kizzey](https://codeberg.org/UnpackedX/Kizzey) | Codeberg history shows 8.4.5 stable on 2026-11-02. | Dedicated crash-file logger, previous-crash surfacing, source ownership/arbitration guidance, service-lifecycle checks. | Implement a clean local crash reporter and keep one Gecko-owned candidate arbiter. No Kizzey source file was copied. |

### Clean-room and transport boundaries

- Kizzy's README identifies GPL-3.0, but inspected source files also contain explicit author notices restricting copying/distribution without express permission. Gecko therefore uses observed behavior and architecture concepts, not source-code transplantation.
- Kizzey's `CrashFileLogger.kt` and RPC service files have the same explicit source-header restriction. Gecko's `CrashDiagnostics.kt` is a separate implementation using Android's `ApplicationExitInfo`, private app storage, and system document-picker export.
- Kizzy, Kizzy Enhanced, Axe, and Kizzey describe Discord Gateway-based account-token flows. Gecko does not adopt those flows. Gecko keeps the official Discord Social SDK AAR and Android unauthenticated Direct RPC transport.
- Kizzey's `docs/FRAGILE-RPC.md` stresses tracing the entire publish/clear path and testing the visible result on a real phone and Discord client. Gecko's existing single `PresenceEngineService` and `PresenceSourceArbiter` remain the only source-to-RPC path; real-device verification remains mandatory.

### Source links

- Kizzy: https://github.com/dead8309/Kizzy
- Kizzy app-detection source: https://github.com/dead8309/Kizzy/blob/master/feature_rpc_base/src/main/java/com/my/kizzy/feature_rpc_base/services/AppDetectionService.kt
- Kizzy package preference model: https://github.com/dead8309/Kizzy/blob/master/common/preference/src/main/java/com/my/kizzy/preference/Prefs.kt
- Kizzy Enhanced: https://github.com/milliarderr/Kizzy-Enhanced
- Axe: https://github.com/sudoloser/Axe
- Kizzey: https://codeberg.org/UnpackedX/Kizzey
- Kizzey crash logger: https://codeberg.org/UnpackedX/Kizzey/src/branch/main/feature_crash_handler/src/main/java/com/my/kizzy/feature_crash_handler/CrashFileLogger.kt
- Kizzey RPC fragility notes: https://codeberg.org/UnpackedX/Kizzey/src/branch/main/docs/FRAGILE-RPC.md
