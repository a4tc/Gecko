# Local Bridge API v1

The Android host binds to `127.0.0.1:32145` only. The API is intended for the Gecko Presence Bridge WebExtension background context. It is not a general-purpose LAN service.

## Extension permissions

For a Manifest V2 Gecko extension, add this host permission to the extension permission list:

```json
"http://127.0.0.1:32145/*"
```

The extension stores the pairing key in `browser.storage.local`; do not expose it to page scripts or content scripts. Keep all bridge calls in the extension background context.

## Pairing

The Android app creates a cryptographically random 256-bit key. The user reveals/copies it from the app and pastes it into the extension's bridge settings. The host encrypts its copy using Android Keystore AES-256-GCM.

Send the key in the `X-GPB-Key` header. Never send a Discord OAuth token through this bridge.

## Health

`GET /v1/health`

No pairing key is required. It returns non-sensitive availability data:

```json
{
  "ok": true,
  "service": "Gecko Presence Host",
  "bridgeState": "listening",
  "discordSdkEnabled": true,
  "discordReady": false,
  "apiVersion": 1
}
```

## Activity update

`POST /v1/activity`

Headers:

```http
Content-Type: application/json
X-GPB-Key: <pairing-key>
```

Body:

```json
{
  "source": "youtube.com",
  "name": "YouTube",
  "details": "Watching",
  "state": "Optional video title",
  "mediaState": "playing",
  "startTimestamp": 1794144000
}
```

Fields:

- `source`: required host/domain only, such as `youtube.com`. Schemes, paths, query strings and whitespace are rejected.
- `name`: optional Rich Presence application name (max 80 characters).
- `details`: optional activity detail (max 120 characters).
- `state`: optional page/media title (max 120 characters); it is discarded unless **Share Page Titles** is enabled in the Android app.
- `mediaState`: `playing`, `paused`, `ended` or `browsing`.
- `startTimestamp`: optional epoch seconds; invalid or absent values are replaced with the current time.

Success: HTTP `202 Accepted`.

## Clear activity

`POST /v1/clear`

Use the same headers and send an empty JSON object. Success: HTTP `200 OK`.

## CORS and origin rules

- The server echoes the request origin only when it begins with `moz-extension://`.
- Preflight supports `GET`, `POST`, and headers `Content-Type`, `X-GPB-Key`.
- HTTP requests with no `Origin` header are accepted only because the socket is loopback-only and protected write routes still require the pairing key.
- `null`, web-page, and remote origins are rejected.

## Example extension request

The extension background script should send JSON using `fetch`:

```js
const response = await fetch('http://127.0.0.1:32145/v1/activity', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-GPB-Key': bridgeKey,
  },
  body: JSON.stringify({
    source: 'youtube.com',
    name: 'YouTube',
    details: 'Watching',
    mediaState: 'playing',
    startTimestamp: Math.floor(Date.now() / 1000),
  }),
});

if (!response.ok) {
  throw new Error(`Local bridge rejected activity: HTTP ${response.status}`);
}
```

Do not call this from the website's page context. The extension background script owns the bridge key and the local HTTP request.
