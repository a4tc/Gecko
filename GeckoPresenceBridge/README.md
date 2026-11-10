# Gecko Presence — Gecko browser module

A small Gecko MV2 extension bundled inside the Android APK. It is exported only when the user wants website activity from an external Firefox/Tor browser. It is not a separate Android app.

## Current 0.5.0 behaviour

- Publishes only the active foreground browser tab, so background tabs cannot fight over one Discord status.
- Uses built-in YouTube media detection.
- Adds generic event-driven HTML5 `video`/`audio` detection on domains the user has explicitly granted.
- Requests access per domain instead of prompting for every HTTP/HTTPS website at once.
- Blocks sensitive domains even if the browser permission was granted.
- Sends only normalized site identity, activity state, and (when media is playing/paused/ended) an optional sanitized title. Full URLs, paths, query strings, fragments, referrers, cookies and credentials are not sent.
- Uses a local pairing key to reach the Android bridge at `127.0.0.1:32145`.
- Uses the Android app's single title-sharing setting as the final gate before Discord.
- IDLIX native adapter handles movie/series/episode routes and title metadata; the pinned PreMiD IDLIX adapter is injected as a lower-priority compatibility fallback for its historical domain. Native results win when confident; imported results can fill a low-confidence gap.
- Activity Preview, Developer Lab, and Custom Presence Studio (including locally saved presets) are available in extension settings.
- Manual presence is a priority-100 override; disabling it calls the host's `/v2/manual/clear` endpoint so the highest-priority automatic source is restored.

## Pairing

1. Start the Local Bridge in the Android app.
2. Copy the pairing key from the Android app.
3. Export the browser XPI from the Android app.
4. After installing the add-on in Firefox, open its Settings and paste the pairing key.
5. Enable the local bridge and save.
6. In the extension settings, add only the websites you intend to track, such as your IDLix mirror.

Firefox/Tor website tracking requires the add-on to run inside the browser. Android application tracking does not.

## Firefox signing

The XPI exported by the Android app is unsigned. Normal Firefox Android installation requires Mozilla signing. Submit it to AMO as an unlisted add-on and use the signed XPI that Mozilla returns. Temporary/debug loading is for development only and may disappear after a restart. Tor Browser compatibility remains experimental and is not guaranteed.

## Development

```sh
npm install --no-audit --no-fund
npm test
npx web-ext lint --source-dir extension
```

Source is in `src/`; generated browser assets are in `extension/`. Tests use Node's built-in test runner. The TypeScript build is run before the tests.

The project borrows public activity-adapter concepts from PreMiD's documentation and public Activities repository. The PreMiD README states its Activities are open source while the main extension is not currently open source; GPB does not copy or claim to reimplement that closed extension.
