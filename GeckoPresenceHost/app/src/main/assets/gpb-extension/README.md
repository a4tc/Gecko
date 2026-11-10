# Gecko Presence browser module

This directory is the generated Gecko MV2 extension bundle. Edit TypeScript in `../src`, then run `npm test` to rebuild and verify it.

## Pairing

1. Start **Local Bridge** in the Gecko Presence Android app.
2. Copy the pairing key from the Android app.
3. Export the browser module from **Browser Module → Export Firefox XPI**.
4. Install the Mozilla-signed XPI in Firefox and open the add-on's Settings page.
5. Paste the pairing key, enable the local bridge, and save.
6. Add only domains that should be eligible for website presence. Access is requested per domain rather than for all websites.

Android app tracking runs directly in the APK and does not require this extension. The XPI is only needed to report browser websites from an external Firefox/Tor process.

## Title and playback behaviour

The browser module uses the active tab only. It reports `Watching` when HTML5 media playback is active and includes a sanitized page/media title only for active/paused/ended media states. The Android app's single **Share media titles** switch is the final gate before publishing the title to Discord.

IDLix extraction uses common movie headings, Open Graph/Twitter metadata and `document.title`. If the player is inside a third-party iframe, the iframe domain may need a separate per-domain permission. If the player does not expose an HTML5 media element, the extension cannot infer its state.

## Signing

This exported build is unsigned. Normal Firefox Android installation requires Mozilla signing. Submit it to addons.mozilla.org as an unlisted add-on and install the signed XPI returned by Mozilla. Temporary/debug loading is a development method and does not guarantee persistence. Tor Browser compatibility is experimental.
