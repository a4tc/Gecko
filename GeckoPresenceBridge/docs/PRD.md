# Product Requirements — Gecko Presence Bridge

**Status:** Draft for review  
**Version:** 0.1.0  
**Primary platform:** Firefox for Android / Gecko  
**Experimental platform:** Tor Browser for Android

## 1. Objective

Build a low-resource Android companion and Gecko WebExtension that detect the user's active website/media activity and, when explicitly enabled, publish a concise Rich Presence to the signed-in Discord Android client. A Discord web tab must not be required.

The product is a clean-room reimplementation. It does not depend on PreMiD's desktop companion or copy PreMiD's code/assets.

## 2. Users and primary journey

A user installs the extension and Companion APK, opens the Companion, pairs the extension, selects allowed websites and a presence template, and explicitly starts presence forwarding. While the user browses, the extension emits event updates. The Companion validates and transforms the latest event, then sends/clears Discord activity through an officially supported integration.

The user can stop forwarding at any time from the app or notification. When stopped, the Companion clears the current activity if the transport supports it.

## 3. Goals

- Publish browser activity without an open Discord Web tab.
- Detect the active tab and supported HTML media with event-driven work.
- Support site-specific metadata adapters for selected services.
- Let users customize details, state, timestamps, registered artwork assets, and privacy rules.
- Provide a separate global Custom Presence composer, independent of per-app/per-site automatic rules, with reusable presets and an accurate preview.
- Keep the browser extension useful as a detector even when Discord integration is unavailable.
- Keep all pairing, preferences, and transient activity data on-device.
- Treat Tor compatibility as a separately tested opt-in mode.

## 4. Non-goals

- Reimplement Discord's private or undocumented endpoints.
- Store Discord user bearer tokens in the extension or Companion.
- Control Discord accounts, messages, friends, servers, voice, or user settings.
- Read message content, credentials, cookies, or unrelated page text.
- Bypass website DRM, paywalls, authentication, browser protections, or Tor privacy features.
- Collect browsing history or upload analytics to a project backend.
- Guarantee that Android will keep a process alive after force-stop, battery restriction, or OS termination.
- Promise `Watching`/`Listening` Discord activity verbs merely because the UI offers a corresponding content template.

## 5. Functional requirements

### FR-01 — Active tab detection

The extension must detect the active tab, navigation changes, title changes, and tab closure. It must not publish activity for a background tab. It must clear or replace activity when the active tab becomes unsupported or is closed.

### FR-02 — Generic website presence

For a domain without a dedicated adapter, the extension may emit only the normalized origin/domain and page title if the user has enabled generic presence. It must never send the full URL, query string, fragment, referrer, or browsing history.

### FR-03 — Media detection

For approved sites, the extension may detect `play`, `pause`, `ended`, and relevant metadata changes from media elements or Media Session metadata. It must stop observing when the tab is no longer relevant and must not use a global one-second polling loop.

### FR-04 — Site adapters

MVP starts with one adapter (YouTube) after the generic detector and bridge work. Twitch and Netflix are later adapters. Each adapter must declare its domains, permissions, fields collected, selectors/events used, and a test fixture. A missing adapter must degrade to generic presence rather than break the extension.

### FR-05 — Pairing and local bridge

The Companion must provide an explicit pairing flow. The bridge accepts only the paired extension identity/secret, validates protocol version, limits payload size, rejects unknown fields, and binds only to loopback. It exposes health, activity update, and clear operations. No endpoint may execute shell commands or arbitrary URLs.

### FR-06 — Discord integration

The Companion must expose a transport interface that can publish and clear activity. The first implementation spike must verify official Discord Social SDK availability, Android RPC behavior, account/login prerequisites, and policy/terms eligibility for a non-game companion app. Until verified, show the Discord transport as `unavailable/experimental`; do not claim success.

### FR-07 — Custom presence templates

Users may configure automatic per-source templates for `name` (activity display name), `details`, `state`, timestamps, and artwork. These templates are rules attached to detected apps/sites and are distinct from the global Custom Presence composer. The Discord application identity remains the configured Application ID; changing an activity display name does not change the registered Discord application identity. Artwork keys must belong to that configured application unless a supported external HTTPS image URL is used.

### FR-08 — Global Custom Presence composer

The Companion must provide a user-authored Custom Presence mode that is independent from automatic source rules and per-app templates. It must support saved presets and explicit activation/deactivation. While Custom Presence is active, it takes priority over automatically detected browser/app activity; disabling it returns control to the automatic resolver without losing the saved preset.

The composer must expose, when supported by the configured Discord transport:
- Activity type dropdown. Start with `Playing`; expose `Listening`, `Watching`, and `Competing` only when the exact bundled Social SDK version supports them (Social SDK 1.5 release notes added these types). Keep `Streaming`, `CustomStatus`, and `HangStatus` hidden/experimental until their exact transport behaviour is verified. Streaming requires a valid supported Twitch/YouTube URL where applicable.
- Application/activity name (the profile header), details, and state text.
- Status display dropdown: Name, State, or Details. This is separate from the activity type dropdown.
- Large image and small image, each with hover/tooltip text. Support registered Discord asset keys and validated public HTTPS image URLs only when the transport supports them. Discord recommends 1024×1024 assets and allows up to 300 registered assets per application.
- Optional platform badge preset (Android, iOS, Desktop/PC, Samsung, Xbox, PS4, PS5, Embedded), rendered through a selected small-image asset and tooltip. This is a visual label, not proof of the device currently in use. There is only one small-image slot, so selecting a platform badge replaces the custom small image unless the user supplies a combined asset.
- Up to two optional clickable action buttons (label + validated HTTPS URL), only when supported by the selected transport.
- A preview of the resulting Discord activity and clear validation for unsupported combinations, empty fields, URL restrictions, and text length.

Do not conflate the platform badge with Discord Social SDK `supportedPlatforms`: the official enum includes Desktop, Xbox, Samsung, iOS, Android, Embedded, PS4, and PS5, but that field controls where a joinable activity can be accepted and is not a decorative device indicator. GPB must not set joinable platform flags unless it actually implements a compatible join/invite flow. A generic `Mobile` platform is not an official enum value; use Android or iOS for a visual badge.

Global Custom Presence must not overwrite per-app/per-site rules. The UI must name these modes separately and show which source currently owns the published activity. All text and image settings remain local; never require a Discord user token.

### FR-09 — Privacy profiles

Profiles:
- `standard`: explicit per-site allowlist; selected metadata only.
- `private`: domain or category only; redact page title and media title.
- `tor`: disabled until the user explicitly opts in; per-domain allowlist; no full URL, query, referrer, or page text; title/media details off by default.

Changing profile must immediately re-evaluate the current activity. Switching to a stricter profile must clear the previous, more detailed activity before sending a redacted update.

### FR-10 — Lifecycle and idle handling

Forwarding starts only after a visible user action in the Companion. While active, show an ongoing notification with Stop. On Stop, permission revocation, Companion shutdown, bridge disconnect timeout, or browser inactivity timeout, clear current presence when possible. Latest state wins; stale updates are discarded using sequence and expiry metadata.

### FR-11 — Offline behavior

If Discord is unavailable, keep at most the latest activity in memory for a short TTL. Do not queue a history of activities. On reconnect, publish only if the event is still current and the user has not stopped forwarding.

## 6. Proposed event contract

```json
{
  "protocolVersion": 1,
  "sequence": 18,
  "eventId": "uuid",
  "kind": "activity.update",
  "source": "youtube",
  "occurredAt": 1794140000000,
  "expiresInMs": 15000,
  "activity": {
    "details": "Watching: Video title",
    "state": "YouTube",
    "startedAt": 1794139000000,
    "assetKey": "youtube"
  }
}
```

Fields are optional where supported. The bridge must not accept arbitrary URLs for image assets. The activity schema must be versioned and validated on both sides.

## 7. Platform and compatibility requirements

- Firefox Android is the primary browser development target.
- Use a Gecko event page (`background.scripts`, `persistent: false`) and verify APIs using `web-ext lint` with a `gecko_android` minimum version.
- Avoid Manifest V3 service-worker assumptions; Firefox Android does not support extension background service workers.
- No `<all_urls>` injection by default. Use a small built-in site allowlist and optional host grants only when required.
- Tor Browser Android is experimental. Tor Project warns additional extensions may compromise privacy, weaken security, or make users easier to track. Do not advertise Tor compatibility until tested against an exact release and do not instruct users to weaken protections to install it.

## 8. Security and privacy boundaries

- No third-party backend in MVP.
- No secrets in the XPI, APK resources, logs, crash reports, or source control.
- The local bridge binds to `127.0.0.1` only and requires pairing authentication.
- CORS/Origin checks must be constrained to the exact extension origin where possible.
- Validate all bridge input: protocol version, enum values, lengths, timestamps, sequence, payload size, and TTL.
- No full browser URL is sent to Discord.
- No remote JavaScript or remote adapter updates are executed.
- No attempt to circumvent Tor fingerprinting defenses, content blocking, or extension signing.

## 9. Performance requirements (initial targets; validate on target device)

- No permanent polling loop in the extension.
- No background service is active while forwarding is off.
- No more than one current activity retained in memory per browser profile.
- Coalesce bursty DOM/media mutations; publish only on semantic change.
- Prefer no database for transient activity. Persist only settings, allowlists, profile templates, and pairing material.
- Measure APK size, XPI size, idle CPU, peak memory, and activity update latency on a low-end Android device before setting release budgets.

## 10. Commands and toolchain (to be pinned at implementation)

- Extension install/debug: `web-ext run -t firefox-android --adb-device <DEVICE> --firefox-apk org.mozilla.fenix`
- Extension compatibility: `web-ext lint`
- Android device discovery: `adb devices`
- Android logs: `adb logcat`
- Android build/test: Gradle wrapper commands after the Android module is scaffolded.
- C++ SDK build: CMake/NDK versions must match the SDK package actually obtained from the Discord Developer Portal.

No unverified SDK version or library version is hard-coded in this draft.

## 11. Acceptance criteria for first vertical slice

- [ ] Firefox Android event page starts and idles without a persistent background page.
- [ ] Active tab change produces one normalized activity event.
- [ ] Background tab activity never replaces active-tab presence.
- [ ] A supported HTML media element transitions play/pause/end correctly without periodic polling.
- [ ] Extension sends no full URL or query string.
- [ ] Companion receives only valid paired messages on loopback.
- [ ] Stop action prevents further updates and clears current activity when available.
- [ ] Discord feasibility spike either succeeds with documented, permitted Android RPC or records a no-go with evidence.
- [ ] `web-ext lint` reports no known Android-incompatible API/manifest issues for the selected target version.
- [ ] Tor lane is not marked supported based only on Firefox Android success.

## 12. Open decisions

1. Is the Discord Social SDK legally/contractually and technically usable by a non-game utility Companion app?
2. What exact Android foreground-service type is appropriate for this user-visible, ongoing presence bridge on the target Android API level? Must be verified against current Android requirements before manifest implementation.
3. Does Firefox Android permit the proposed loopback HTTP bridge from this add-on origin on the exact target release, including CORS and cleartext restrictions?
4. Can the exact Tor Browser Android release load the signed add-on at all without unsupported changes?
5. Which media/site adapters are in MVP after YouTube is proven?
