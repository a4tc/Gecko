# ADR 0001 — Gecko Extension Runtime

**Status:** Proposed  
**Decision:** Use a Firefox WebExtension with Manifest V2 event-page background (`persistent: false`) as the first runtime.

## Context

The supplied PreMiD XPI is Manifest V2 with a persistent background page and broad `<all_urls>` content-script injection. That is not an appropriate default for the intended Android footprint. Firefox Android does not support extension background service workers, and Mozilla's Android extension guide recommends Manifest V2 for Android targets. Firefox supports non-persistent background pages/event pages.

## Decision

- Manifest V2.
- `background.scripts` with `persistent: false`.
- TypeScript compiled to plain JavaScript.
- Synchronous top-level event listener registration.
- Store only required persistent configuration/state in extension storage.
- Use tabs events and narrow host-specific content scripts.
- Avoid `<all_urls>` and broad permissions by default.

## Consequences

- The implementation must not rely on a continuously resident background process.
- Event listeners must be registered correctly for event-page wakeups.
- Runtime state must be persisted or reconstructed after an event-page unload.
- Cross-browser Chromium support is not an MVP requirement.

## References

- https://extensionworkshop.com/documentation/develop/developing-extensions-for-firefox-for-android/
- https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/background
- https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Background_scripts
