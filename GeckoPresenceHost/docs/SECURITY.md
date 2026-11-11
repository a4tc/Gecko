# Security and privacy model

## Data handled

- Selected Android package label and its current foreground state.
- Optional active media-session title/artist when the user enables title sharing.
- Optional normalized public website hostname from a selected browser address bar.
- User-authored manual activity text and locally saved presets.

## Source permissions

- **Usage Access:** reads UsageEvents to identify only explicitly selected foreground applications. It does not read another app's UI, clipboard, messages, notification text, or internal storage.
- **Notification Access:** enables `MediaSessionManager.getActiveSessions`. The `NotificationListenerService` does not inspect notification titles, bodies, actions, or extras.
- **Accessibility:** enabled only for browser URL detection. The service is package-filtered to browsers selected by the user and searches known address-bar view IDs. It does not inspect arbitrary editable fields or page body text. It emits only the public hostname after sensitive-host filtering.

## Controls

- All source toggles default off.
- Android apps require an explicit allowlist and are checked against a sensitive-app denylist when events arrive.
- Browser packages require an explicit allowlist. URL paths, query parameters, fragments, credentials, and page titles are not sent to the presence engine.
- Media titles and artist names are off by default and separately opt-in.
- Automatic source candidates are held in memory, bounded, and expired when not refreshed. Manual presence is stored locally in app preferences until disabled.
- The app does not request Discord OAuth, does not store Discord user tokens, and does not implement a browser-to-app HTTP server.
- Activity text is length-limited before the native SDK call. Diagnostics exclude raw URLs, notification content, and package histories.

## Trust boundaries

- Android system APIs -> native source adapters.
- Kotlin presence model -> JNI -> official Discord Social SDK.
- User-authored preferences -> local preset and priority stores.

There is no extension runtime, XPI package, loopback bridge, pairing secret, remote adapter loader, or downloaded executable activity code.

## Known limits

Accessibility resource IDs differ by browser version and OEM. Some browsers do not expose the address bar to Accessibility services, and private/incognito pages may behave differently. The app must be tested against the exact Firefox, Tor Browser, and OEM browser builds before claiming compatibility. Direct Rich Presence still requires the official AAR, Application ID, Discord app availability/sign-in, and policy approval for this app's use case.
