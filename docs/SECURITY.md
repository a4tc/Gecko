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
- Version 0.6.4 includes an explicit Discord session-import screen inspired by Kizzy's public implementation. This is **not OAuth2**: the user signs in at `https://discord.com/login`, then must press an Import button while the top-level page is on Discord's app route. Only that user action evaluates a fixed script against the first-party page's `localStorage` session value.
- The login Activity is not exported, accepts top-level HTTPS navigation only to `discord.com`, has no JavaScript-to-native interface, denies file/content access and mixed content, disables file-origin access, third-party cookies, persistent cache, camera/microphone grants, geolocation, and WebView debugging, cancels TLS verification errors, and uses `FLAG_SECURE`.
- Session import fails closed unless Android 9+ WebView data-directory isolation is successfully initialized. Cookies, web storage, history, cache, and form data are cleared after import/cancel. A persisted cleanup marker causes retry when the user returns to Gecko or after process restart if Android kills Gecko before cleanup finishes.
- The session value is not displayed, logged, or sent to a Gecko server. It is immediately passed to the local credential vault. The app does not implement a browser-to-app HTTP server.
- This session value is a bearer credential with broad account access. It must only be imported for the user's own account. Discord may change its web client storage format and break import; it can also revoke the session independently of Gecko.
- Activity text, timestamps, URLs, image references, and button counts are validated before a Gateway payload is encoded. Payloads are UTF-8 size-limited and updates are coalesced/rate-limited. Diagnostics exclude credential values and do not collect native memory-snapshot tombstones.

## Credential storage and Gateway connection (0.6.6)

- A credential imported through the explicit Discord session flow is normalized in memory and encrypted using AES-256-GCM with a fresh 96-bit nonce and 128-bit authentication tag.
- The AES key is generated and retained by Android Keystore under a Gecko-specific alias; it is non-exportable through the app API. Whether a particular device backs the key with secure hardware is device-dependent and has not been measured here.
- Private SharedPreferences hold only the versioned ciphertext envelope. The manifest disables app backup and explicitly disables cleartext traffic.
- The manual token-paste route has been removed to avoid exposing the credential to the keyboard or clipboard. The Settings and sign-in screens enable `FLAG_SECURE` while sensitive authentication state is present.
- Removing the credential deletes both the ciphertext and Keystore alias. The Gateway publisher unlocks the credential only following an explicit Developer Console connect action; the normal engine start does not auto-connect.
- Token-like strings and Authorization/Bearer values are redacted from crash diagnostics and log messages on a best-effort basis. Gecko does not collect native tombstone memory snapshots; legacy tombstone files from older versions are deleted during diagnostics initialization.
- OAuth2 sign-in does not provide the normal user-account Gateway session credential. The Kizzy-style session import is an unofficial client-session technique, not a supported Discord authentication API. Its use can violate Discord's rules on automating normal user accounts and may expose the account to enforcement. The Gateway publisher is implemented in source, but actual session acceptance and rich-presence rendering remain unverified without a live device test.
- The private artwork Gallery stores JPEG, PNG, WebP and GIF files under app-private `filesDir`, caps each imported image at 25,000,000 bytes, and retains the existing 40-item/64 MiB aggregate limits. It checks file signatures and dimensions, deduplicates by SHA-256, and verifies the stored file hash before upload.
- Each user may configure a Discord webhook they control. The webhook URL is stored as AES-GCM ciphertext using a dedicated Android Keystore key; it is not compiled into `BuildConfig`, written to cache metadata, or logged by the uploader.
- Artwork bytes are sent only to `discord.com` over HTTPS. The uploader disables redirects, bounds connection/read timeouts and response bytes, checks HTTP status, validates the returned message ID and signed Discord CDN attachment URL, and converts the attachment URL to an `mp:` reference before presence publication.
- The Discord CDN link is reachable by anyone who obtains it. Removing a local Gallery entry does not delete previously uploaded Discord messages. Automatic media/app artwork uploads are OFF by default and require both an explicit user toggle and a saved webhook.
- Cache keys combine the content hash with a one-way hash of the normalized webhook identity. The raw webhook URL is never used as a cache key or stored in the cache. Cache eviction only deletes a remote message when the evicted entry belongs to the currently configured webhook.
- Per-app profile preferences are app-private local settings, not secrets. They store user-authored activity fields and optional status overrides; credentials remain in the separate encrypted vault.

## Trust boundaries

- Android system APIs -> native source adapters.
- Discord first-party WebView session -> user-triggered localStorage read -> token decoder -> Android Keystore credential vault. The dedicated WebView profile is isolated from any other app WebView data and is cleared after use.
- Android source adapters -> presence arbitration -> transport-neutral `PresenceActivity` -> Gateway JSON payload builder -> Ktor WebSocket publisher.
- User-authored preferences -> local preset and priority stores.
- Gecko Android client -> user-configured HTTPS Worker -> that user's R2 bucket and public custom asset domain. No Gecko-owned upload proxy or shared Worker credential is required.

There is no extension runtime, XPI package, loopback bridge, pairing secret, remote adapter loader, or downloaded executable activity code.

See [SECURITY-PENTEST-0.6.6.md](SECURITY-PENTEST-0.6.6.md) for the source-level assessment, findings, test results, and limitations.

## Known limits

Accessibility resource IDs differ by browser version and OEM. Some browsers do not expose the address bar to Accessibility services, and private/incognito pages may behave differently. The app must be tested against the exact Firefox, Tor Browser, and OEM browser builds before claiming compatibility. Direct Rich Presence requires a valid stored session. A Discord Application ID is optional in the wire payload but required for some application asset workflows. The user-session Gateway route is undocumented and not officially supported.
