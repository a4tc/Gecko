# Gecko Presence 0.5.0 — Implementation Status

| Work package | Status | Evidence / remaining gate |
|---|---|---|
| Unified Activity Model v2 | Implemented | Normalization, metadata bounds, freshness and priority arbitration tests |
| Hybrid adapter registry | Implemented | Native + PreMiD adapter resolution and fallback tests |
| PreMiD compatibility API | Implemented subset | `Presence`, events, settings, strings, timestamps, iframe message forwarding; unsupported APIs fail import validation |
| Pinned PreMiD importer | Implemented | Full commit pin, SHA-256 lock, offline snapshot import, generated provenance and notices |
| Historical IDLIX source | Imported | PreMiD IDLIX activity snapshot pinned at `f75faa1585cca7d81b4b85694829a2dfbb6ab00a` |
| Native IDLIX adapter | Implemented in source | Movie/series/episode extraction, JSON-LD, heading/OG/document fallback, artwork, selector-source diagnostics |
| IDLIX mutation refresh | Implemented in source | Mutation observer reports metadata changes to the active-tab background pipeline |
| Activity Preview | Implemented in extension options | Separate live activity card with source, activity type, artwork and transport status |
| Developer Lab | Implemented in extension options | Adapter inventory, event trace, bridge status, redacted diagnostic export |
| Custom Presence Studio | Implemented in extension options | Manual override and local saved presets; automatic tracking resumes when disabled |
| Android bridge v2 | Implemented in source | `/v2/activity`, `/v2/clear`, `/v2/health`; v1 compatibility retained |
| Android v2 mapper | Implemented in source | Unit/self-test coverage; sensitive-host rejection, title-sharing gate, source type and priority |
| Cross-source presence arbitration | Implemented in source | Android host keeps one candidate per source class; manual > Android foreground app > native website > compatibility > generic website; source-specific clear restores the next candidate |
| Android foreground tracker | Existing feature retained | Requires Samsung Android 14 device QA |
| Discord SDK transport | Preview-gated | Official AAR and Application ID required; current JNI transport uses `ActivityTypes::Playing` only |
| Artwork/buttons/party metadata to Discord | Not implemented | SDK API and entitlement need verification before adding transport fields |
| Firefox Android install | Not verified in this environment | XPI is unsigned; AMO signing and device install remain required |
| Tor Browser compatibility | Experimental / unverified | Must be tested against the exact Tor Browser build and extension policy |
| End-to-end Discord presence | Not verified in this environment | Requires official SDK AAR, configured Application ID, OAuth approval and live test |

## Local verification performed

- Extension TypeScript build: passed.
- Extension Node test suite: 77/77 passed after hybrid importer, source arbitration and IDLIX integration changes.
- Android pure Kotlin core checks: 39/39 passed.
- Full Gradle Android build: not completed locally because Gradle 8.10.2 could not be downloaded in this environment (DNS/network unavailable).
- `web-ext lint`: not completed locally because the lint package was not available and package retrieval timed out.

## Release gates

1. GitHub Actions must run the versioned source archive workflow and build the APK/XPI.
2. Review any `web-ext lint` errors and Android unit-test failures.
3. Install the APK on the target Samsung Android 14 device and verify bridge, app allowlist, foreground switching and manual override.
4. Install the signed XPI on Firefox Android and test IDLIX movie, series and episode routes.
5. Test Tor Browser separately; do not lower Tor security settings to force compatibility.
6. Only claim live Discord transport after the official SDK AAR is configured and a real presence update is verified.
