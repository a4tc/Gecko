# Tooling and Plugin Selection

## Engineering workflows selected

These workflows are already available in the current ChatGPT environment; no additional plugin installation is required to prepare this starter.

| Workflow | Why it is selected | Stage |
|---|---|---|
| Reverse Engineering Anything (REA) guidance | Routes the supplied XPI investigation and distinguishes static observations from runtime evidence. Its actual REA execution engine/tools are not connected in this session. | Artifact inspection; later use local REA if configured |
| Spec-Driven Development | Keeps the capability map, product requirements, and acceptance criteria ahead of implementation. | Current design stage |
| Source-Driven Development | Requires browser/Android/Discord API choices to be checked against current official documentation before implementation. | Before each framework-specific code change |
| Test-Driven Development | Behavior changes require tests first, especially bridge parsing, state transitions, deduplication, expiry, and privacy redaction. | Implementation stage |
| Security and Hardening | Covers local bridge authentication, sensitive activity data, dependency review, and third-party trust boundaries. | Before bridge and SDK work |
| Performance Optimization | Keeps event-driven design and requires measurement on the target Android device rather than unsupported speed claims. | After the first vertical slice |
| Ponytail | Enforces a small dependency footprint and avoids unnecessary runtime layers. | Throughout implementation |

## Local development tools

Required or likely required:

- Node.js 22+, TypeScript, and `web-ext` (development/build time only).
- Android Studio or Android SDK command-line tools, Platform Tools (`adb`), Gradle, Kotlin plugin, and a matching NDK/CMake toolchain if the Discord SDK spike passes.
- Firefox for Android (Nightly/Beta/Release) for development and testing.
- Tor Browser Android for the separate experimental compatibility lane.
- Discord Developer Portal application and Social SDK package if access is available and terms permit the intended use.

## Not selected

- No cloud backend, database, Redis, message broker, or telemetry service for MVP.
- No Node.js or Python runtime on-device.
- No React, Compose UI framework, cross-platform framework, or large dependency injection system at the start.
- No private Discord REST/headless-session endpoint.
- No remote code loading.

## Plugin/tool caveat

The REA skill provides investigation methodology, not an automatic reverse-engineering runtime. If the local REA MCP/CLI is connected later, route the XPI as an archive and use the advertised inventory/JavaScript-analysis workflows only. Do not claim runtime traces unless the target is actually launched under an authorized capture.
