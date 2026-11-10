# Reverse-Engineering Notes — Supplied PreMiD XPI

**Artifact:** `/mnt/data/PreMiD.xpi.zip`  
**Observed package version:** `2.11.1`  
**Method:** archive inventory and static text inspection of the supplied package. No runtime execution was performed. No REA MCP/CLI analysis engine was available in this session.

## Observed facts

### Manifest

`manifest.json` declares:

- `manifest_version: 2`
- version `2.11.1`
- Gecko desktop minimum `128.0`
- permissions: `tabs`, `storage`, `unlimitedStorage`, `alarms`, `scripting`, `idle`, and `*://*/*`
- persistent background page (`persistent: true`)
- two content scripts matched to `<all_urls>` plus a PreMiD-domain-specific content script

This permission/injection model is much broader than the intended mobile-first fork needs.

### Bundle layout

- `background.js` is a bundled/minified file of roughly 764 KB.
- The archive includes content scripts, a script bridge, UI chunks, localization files, fonts, icons, developer-tools UI, and local presence packages.
- The package's local `presences/` tree contains six adapters: Netflix, Disney+, SoundCloud, YouTube, YouTube Music, and Twitch.
- The unpacked folder contains Mozilla signature metadata under `META-INF`; those files are not reusable signing credentials for the new project.
- The archive is about 11 MB compressed and about 15 MB after extraction in this inspection. The new project should not carry over the font catalogue, full localization catalogue, or PreMiD assets by default.

### Discord activity path

Static strings and minified class structure identify `HeadlessSessionService` with API route constants:

- `users/@me/headless-sessions`
- `users/@me/headless-sessions/delete`

The client base URL is `https://discord.com/api/v10`. The service obtains account/token state through PreMiD's Discord OAuth service, posts activity with a bearer token, stores returned per-account activity tokens/expiry data, refreshes the session token on a scheduled alarm, and posts deletion requests to clear it. The implementation includes rate-limit and duplicate-update handling.

The package also has optional activity forwarding settings (`activityForwarding` and `activityForwardingUrl`) and a developer WebSocket connection to `ws://localhost:3021`. The WebSocket is in a development/local activity path; it should not be mistaken for the primary Discord delivery transport.

These endpoint paths were observed in the supplied package, but this static inspection does not establish that they are public, stable, documented, or approved for independent third-party use. The new project must not depend on them.

## Inference

- The desktop PreMiD architecture is designed around a persistent background context, broad page observation, adapter scripts, Discord OAuth account state, and a custom headless-session API.
- Directly transplanting its background bundle to Android would preserve too much desktop-oriented state and permissions.
- A clean mobile implementation should preserve the useful product concepts (adapter-based website detection, normalized activities, user profiles, custom templates) while replacing its runtime and Discord delivery boundaries.

## Unknowns

- Runtime behavior on any specific Firefox Android or Tor Browser Android build.
- Whether every remote presence or activity script has corresponding local package data.
- Whether Discord's headless-session endpoint is available or authorized for an independent implementation.
- Whether the Social SDK's mobile RPC path accepts this non-game companion product under current terms and developer configuration.

## Clean-room boundary

This notes file does not reproduce source code. Do not copy PreMiD implementation code, logos, fonts, presence files, signing metadata, or other assets into this project without reviewing the applicable license and permissions. Use the bundle to understand feature behavior and design compatibility boundaries only.
