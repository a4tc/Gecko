> Historical design artifact for the retired extension prototype. Not the current product contract; see `../ARCHITECTURE-0.6.0.md`.

# Architecture — Gecko Presence 0.4.0

## Product shape: one Android APK, optional browser module

The Android app is the only primary installation. Its APK contains the Android controller, Android foreground-app observer, localhost transport, presence policy, Discord adapter, and the compiled Gecko extension under `assets/gpb-extension/`.

There are two source lanes:

1. **Android app source lane** — tracks explicitly selected launchable Android applications through Usage Access. It works without a browser extension.
2. **Gecko website lane** — the Android app exports the embedded XPI. The XPI executes inside Firefox/Tor and reports the active browser tab to the Android app through the loopback bridge. This is required because an ordinary Android app cannot inspect another browser's active tab.

The browser module is not a separate APK and does not need to be installed until the user wants browser-site activity. It remains a browser add-on because Firefox owns the tab and page context.

## Runtime data flow

```text
Android selected app ── UsageStatsManager ─┐
                                          ├─ PresencePolicy ─ Discord adapter
Firefox/Tor active tab ─ XPI ─ localhost ──┘
```

### Android lane

- Uses `UsageStatsManager.queryEvents` with a user-granted Usage Access permission.
- Polling interval: 3 seconds; only one current foreground package is retained in memory.
- Only explicitly selected packages are emitted.
- Sensitive and excluded categories are blocked both when listing apps and again when processing a foreground event.
- The event contains the app label and a conservative app-specific activity template. It does not contain an app's internal screen, playback title, messages, notifications, or account details.
- No usage history is written to a local database.

### Browser lane

- Firefox Gecko MV2 event page, non-persistent.
- Publishes only the foreground active tab to prevent multiple tabs competing for one Discord status.
- Website permissions are optional and requested per registrable host pattern from an explicit user gesture.
- YouTube keeps its built-in permission/detector. Other sites require an explicit domain grant in extension settings.
- Generic media detector observes HTML `<video>` / `<audio>` playback events and DOM title changes; it does not use a global polling timer.
- IDLix metadata candidates: `.entry-title`, `.movie-title`, `.film-name`, `.data h1`, `.info h1`, `.mvic-desc h3`, `.single-title`, `#mv-info h1`, `h1[itemprop=name]`, Open Graph/Twitter title metadata, and `document.title`.
- The detector strips common IDLix suffixes and caps titles at 120 characters. It never sends the full page URL, path, query, fragment, or referrer.
- Embedded cross-origin players can require a separate grant for the iframe's domain. If no HTML5 media event is visible, the extension cannot infer playback state.

### Bridge

- Bind address: `127.0.0.1:32145` only.
- `GET /v1/health` — non-secret health state.
- `POST /v1/activity` — authenticated activity event.
- `POST /v1/clear` — authenticated clear event.
- Pairing key: 256-bit random secret, stored by Android Keystore AES-256-GCM on the host side and in extension-local storage on the browser side.
- Browser title is sent only for active media states; the Android app's single “Share media titles” switch is the final gate before Discord.
- Never transfer Discord OAuth access/refresh tokens through the browser bridge.

## Presence contract

| Field | Browser source | Android app source |
|---|---|---|
| `source` | Normalized domain | `android.app` |
| `name` | Site display name | Selected app label/custom name |
| `details` | `Watching`, `Listening`, `Paused`, `Finished watching`, or `Browsing` | App-specific template/custom details |
| `state` | Sanitized media/page title, only when enabled | User-defined state only |
| `mediaState` | HTML media state | `browsing` (foreground-app-only) |
| `startTimestamp` | Current activity time | Foreground transition time |

The current Discord native adapter publishes name, details, state, and start timestamp. Registered artwork assets, buttons, rich party data, and full PreMiD slideshow/dependency parity remain future capabilities rather than claimed features.

## Privacy deny policy

Blocked app categories include banking, financial services, investments, wallets, payments, credit/loans, identity/government, health/medical/insurance, authentication/OTP/password managers, email/messaging/communications, browser apps (browser tabs use the XPI), and selected system utilities. The denylist applies to both UI enumeration and runtime checks; a saved allowlist cannot override it.

Blocked website categories are enforced in the extension host check and include banking, payments, finance/investment/crypto, account/login/authentication/password, health/medical, government/identity, email/messaging, and Discord/social account domains. This is a conservative heuristic and not a substitute for a complete, audited domain taxonomy.

## Explicit non-goals

- No Accessibility Service scraping.
- No Notification Listener.
- No reading other apps' UI, message content, credentials, notification text, or media sessions.
- No extraction of Discord user tokens from browser storage/cookies.
- No private Discord API or undocumented RPC transport.
- No DRM/paywall/authentication bypass.
- No broad website permission by default.
- No third-party telemetry or remote adapter code execution.

## Constraints / known gaps

- The exact IDLix mirror's DOM and player iframe were not available in the source archive. The selectors are a fallback pack, not device-verified selectors for every IDLix mirror. A site-specific adapter may need a small correction after testing the exact URL.
- If a video player lives on a third-party iframe domain, that player domain may need separate permission. A title from the parent page may still be available while playback state is not.
- Android Usage Access reports the foreground app only. It cannot know the exact movie/song title. Obtaining that metadata would require a documented app API or additional Android media access that this project currently excludes.
- Firefox/Tor website tracking still requires the XPI to be installed in the chosen browser. The APK cannot bypass browser process isolation.
- Tor Browser remains experimental; no claim of verified compatibility is made for all versions.
