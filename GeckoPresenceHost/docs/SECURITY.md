# Security Model

## Assets

- Discord OAuth refresh token.
- Ephemeral Discord OAuth access token.
- Local bridge pairing key.
- User activity and optional page title.

## Trust boundaries

- Firefox extension background context -> Android app loopback server.
- Android Kotlin service -> native Discord SDK through JNI.
- Android app -> Discord OAuth2 and Social SDK endpoints.

## Controls

- The HTTP listener binds to `127.0.0.1` only.
- Mutating routes require a 256-bit pairing key and constant-time comparison.
- CORS only allows Firefox extension origins.
- The Android app does not implement endpoints that return or accept Discord credentials from the extension.
- OAuth uses Discord Social SDK and PKCE; there are no password fields, cookie reads, browser-storage scraping or user-token extraction.
- Refresh token encryption uses an AES-256-GCM key in Android Keystore.
- App backup is disabled to avoid restoring encrypted preference ciphertext without the device key.
- Full URL and page title sharing are not enabled by default; the server stores only the most recent in-memory activity.
- HTTP request size, header size and socket timeout are bounded.
- Activity text is length-limited and strips control characters before native calls.
- Tokens and activity payloads are not logged.

## Operational controls

- User explicitly starts and stops the foreground service.
- A persistent notification indicates when the bridge is active.
- Rotating the pairing key invalidates the previously copied key immediately.
- Disconnect removes the stored refresh token.
- Stopping the service closes the server socket and shuts down the SDK client.

## Known limits

- The loopback key protects against accidental/untrusted requests; it is not a hardware-backed browser-to-app attestation mechanism. Keep the key private and rotate it if exposed.
- A locally installed malicious app running under the same Android user may attempt to connect to loopback, but protected routes still require the high-entropy key.
- Discord SDK availability and permitted use depend on Discord's Developer Portal enablement and SDK terms.
- The app must be tested on-device for lifecycle, battery behaviour, OAuth deep links, and SDK compatibility before distribution.


## Native app activity tracking

See [APP_ACTIVITY_PRIVACY.md](APP_ACTIVITY_PRIVACY.md) for the Usage Access boundary, allowlist model, sensitive-app filtering, data minimization, and the deliberate exclusion of Notification Listener and Accessibility Service permissions.
