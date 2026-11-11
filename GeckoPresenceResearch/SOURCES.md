# Primary Sources Checked (2026-11-08)

## Mozilla / Gecko

- Firefox for Android extension development and test workflow: https://extensionworkshop.com/documentation/develop/developing-extensions-for-firefox-for-android/
- Manifest background scripts and Firefox event-page/service-worker support: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/background
- Background scripts/event pages lifecycle: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Background_scripts
- Tabs activation events: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/tabs/onActivated
- Background-to-content-script messaging (`tabs.sendMessage`): https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/tabs/sendMessage
- Extension message listeners (`runtime.onMessage`): https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/runtime/onMessage
- Tabs update events: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/tabs/onUpdated

## Discord

- Social SDK release notes (Android unauthenticated RPC added in 1.10.18247): https://discord.com/developers/docs/social-sdk/release_notes.html
- Social SDK mobile support overview (Android 7.0+; C++, Unreal, Unity): https://discord.com/blog/mobile-platform-support-arrives-in-discord-social-sdk
- Social SDK Activity API and constraints: https://discord.com/developers/docs/social-sdk/classdiscordpp_1_1Activity.html
- Social SDK Client API: https://discord.com/developers/docs/social-sdk/classdiscordpp_1_1Client.html
- Social SDK getting started and application setup: https://discord.com/developers/docs/slayer-sdk/getting_started.html

## Android

- Foreground services overview and visible notification expectations: https://developer.android.com/develop/background-work/services/fgs
- Foreground service startup restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Launching a foreground service: https://developer.android.com/develop/background-work/services/fgs/launch

## Tor Project

- Tor Browser guidance on additional extensions and fingerprinting/security risk: https://support.torproject.org/tor-browser/features/plugins/

## Artifact evidence

- User-supplied `/mnt/data/PreMiD.xpi.zip`, statically inspected. See `docs/REVERSE_ENGINEERING_NOTES.md`.

## PreMiD activity imports

- Upstream: https://github.com/PreMiD/Activities
- Import manifest: `premid-imports.json`
- Importer: `scripts/import-premid-activities.mjs`
- Imported activities are commit-pinned and statically scanned before compilation.
- Imported source is MPL-2.0; per-adapter `NOTICE.md` files preserve provenance.
