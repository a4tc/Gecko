# Test Plan

## A. Extension unit tests

- Active tab reducer selects only the latest focused tab.
- A navigation to unsupported domain clears or produces generic activity according to profile.
- Out-of-order sequence numbers are ignored.
- Duplicate semantic activity is not forwarded twice.
- Adapter failures degrade to generic mode without leaving stale detailed activity.
- Media play/pause/ended and metadata changes map to correct normalized states.
- Closing or switching away from the active tab removes obsolete activity.
- URL sanitizer removes paths, query strings, fragments, credentials, and referrers.
- Tor profile redacts title/media metadata unless the explicit per-domain detail override is enabled.

## B. Bridge tests

- Health endpoint works only on loopback.
- Non-loopback bind is rejected.
- Missing/invalid pairing secret is rejected.
- Wrong extension Origin is rejected where Origin is available.
- Unknown protocol version is rejected.
- Oversized, malformed, truncated, or slow requests are rejected safely.
- Invalid JSON types, overlong strings, invalid timestamps, bad TTL, and invalid event kind are rejected.
- Duplicate event IDs and stale sequence numbers are ignored.
- Stop/clear immediately supersedes queued activity.
- No arbitrary URL, shell command, file path, or remote image URL is accepted.

## C. Android foreground tracker and lifecycle tests

- Pure reducer tests cover allow-listed resume, unselected resume, pause of the current package, stale pause of a previous package, and screen-off clearing.
- UsageEvents queries run on a worker thread and never block the UI thread.
- On-device test switches between two selected apps and one unselected app; presence appears, clears, and returns correctly.
- Diagnostics distinguish permission missing, no selected apps, no UsageEvents, active selected app, and query errors without retaining a usage timeline.
- User can copy a privacy-minimal diagnostic snapshot from the Android Sources panel.
- User can start the service from a visible Companion activity.
- Ongoing notification appears while forwarding is active.
- Stop action from notification stops bridge and clears activity.
- Permission denial / notification disabled paths are explained and handled.
- Process kill, force-stop, battery restriction, and device reboot are tested separately; no false guarantee of automatic restart.
- App handles Discord not installed, logged out, unavailable, or not ready.

## D. Discord SDK feasibility gate

Before implementation beyond a throwaway proof of concept:

1. Obtain the current official SDK package from the developer portal.
2. Read and record the current Social SDK terms and platform requirements.
3. Confirm that the application category and non-game browser presence use are permitted or obtain written clarification from Discord.
4. Build a minimal Android C++ app that initializes the supported RPC path and emits one controlled test activity.
5. Verify the exact activity type, details/state fields, timestamps, registered assets, and clear behavior.
6. Test restart, disconnect, logged-out Discord, and background behavior.
7. Record the SDK package version, hashes, compiler/NDK/ABI requirements, and licensing obligations.

No-go if the use case is not supported, the package cannot be obtained, or the behavior requires an undocumented endpoint or credential extraction.

## E. Browser compatibility

| Target | Required test |
|---|---|
| Firefox Android Nightly | web-ext temporary install, event page wake/unload, tabs API, media events, bridge fetch |
| Firefox Android Beta | same as Nightly plus permission and restart behavior |
| Firefox Android Release | signed XPI installation and regression |
| Tor Browser Android | exact release/architecture; signed XPI install feasibility; no changes to Tor protections; privacy/fingerprint impact documented |

Run `web-ext lint` with accurate `gecko` and `gecko_android` minimum versions. Lint is necessary but does not replace real-device testing.

## F. Performance measurements

Capture on a representative low-end Android device:

- XPI size and APK size.
- Companion idle and active memory (PSS/RSS where available).
- Extension idle CPU and CPU during media state changes.
- Number of background wakeups per minute under a steady-playing video.
- Update latency from source event to Discord status change.
- Battery impact over a fixed, repeatable test session.

Do not set numerical release budgets until a baseline is measured. Compare the same device, browser version, content, battery state, and test duration.

## G. Release acceptance

- All unit and bridge tests pass.
- No broad host permissions or all-URL content-script injection without documented approval.
- No secrets or user browsing data appear in logs.
- Discord SDK feasibility gate passes.
- Tor mode remains labelled experimental until exact-build tests pass and Tor privacy consequences are documented.


## M1 local checks

Run `npm test` from the project root. It compiles TypeScript into `extension/` and runs the Node.js built-in test suite. Current coverage includes active-tab selection, ambiguous selection, privacy-minimal event shape, explicit page-title opt-in and sanitization, invalid/non-web URL rejection, media-state normalization, active-tab message filtering, media-state restoration on tab activation, title-update state preservation, and manifest/script wiring.

Firefox Android runtime verification remains required: run `npm run lint:android`, then `web-ext run -t firefox-android` against an actual device or emulator. `web-ext` and Android Platform Tools were not available in the current build environment, so those checks have not been performed here.
