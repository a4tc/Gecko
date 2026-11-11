# Gecko Presence 0.5.0 — Build Report

## Produced artifacts

- `GeckoPresenceBridge-0.5.0-unsigned.xpi` — built and archive-validated; manifest version 0.5.0 and required background/options/native IDLIX/PreMiD compatibility files present.
- `GeckoPresence-Suite-0.5.0-Source.zip` — source archive validated; contains Android Host, Gecko Bridge, pinned PreMiD source snapshot, generated import lock, notices, documentation and CI workflow.

## Automated checks completed in this environment

| Check | Result |
|---|---:|
| TypeScript build | Passed |
| Extension test suite | 77 / 77 passed |
| Android pure Kotlin core self-test | 39 / 39 passed |
| Android layout and manifest XML parse | Passed |
| Gecko manifest JSON validation | Passed |
| Options page duplicate-ID validation | Passed |
| PreMiD source snapshot SHA-256 verification | Passed |
| XPI ZIP integrity and required file checks | Passed |
| Source ZIP integrity | Passed |

## Checks not completed locally

- Android Gradle unit tests and APK assembly: Gradle 8.10.2 could not be downloaded in this environment. The source contains the JUnit tests and the repository workflow builds the debug APK on GitHub Actions.
- `web-ext lint`: the local package fetch timed out; the workflow installs `web-ext` and runs the lint step.
- Device testing: no physical Samsung Android 14, Firefox Android or Tor Browser run was performed during this build.
- Discord end-to-end: the official Discord Social SDK AAR and Application ID were not configured, so the APK workflow must produce a Preview build unless those inputs are supplied.

## Known functional boundary

The Android native Discord transport currently sends `ActivityTypes::Playing`. The UAM and adapter previews preserve Playing/Watching/Listening/Streaming/Competing, but non-Playing Discord activity types are not yet published by the native transport. Artwork, buttons, party data and slideshow transport are also not claimed as complete.

## Release state

**Source and unsigned XPI build verified. Android APK and real-device/Discord verification remain CI and device release gates.**
