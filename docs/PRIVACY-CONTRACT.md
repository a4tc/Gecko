# Gecko Presence privacy contract

## Defaults

Every automatic source is disabled by default. Media title/artist sharing is independently disabled by default. Manual presence is not applied until the user chooses Apply.

## Data minimization

| Source | Reads | Retains | Never reads |
|---|---|---|---|
| Android app | UsageEvents foreground transitions | current allow-listed package in memory | app screens, messages, notification text, clipboard, usage history |
| Media session | active MediaController metadata/playback state | current candidate in memory; optional title/artist only when enabled | notification title/body/extras/actions |
| Browser | known address-bar view IDs in selected browser packages | normalized hostname in current candidate | DOM, page body, generic text, form values, passwords, path, query, fragment, browsing history |
| Manual | user input | local settings and saved local presets | no network sync |

## Sensitive exclusions

App package/label deny rules exclude financial, payment, authentication, health, identity, government, messaging, email, and other sensitive categories. Browser host deny rules reject sensitive services and domains. These lists are defense-in-depth, not a claim of perfect classification; users should keep sensitive sources unselected.

## Data flow

Source adapters produce bounded `PresenceUpdate` values. The in-memory arbiter chooses one candidate. The Discord adapter receives only the selected activity fields supported by the current native mapping. There is no browser extension, local HTTP listener, remote adapter loader, account token, or external analytics endpoint.

## Permission rationale

- Usage Access is needed to identify the selected foreground app.
- Notification Access is used by Android's `MediaSessionManager` integration to enumerate active sessions. The listener class deliberately ignores notification payload callbacks.
- Accessibility is needed because Android does not otherwise expose a browser tab URL to another app. The service is package-filtered and restricted to known address-bar view IDs; compatibility varies by browser and release.

Users can disable each source and revoke its system permission at any time.
