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
