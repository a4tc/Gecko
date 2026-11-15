# Gecko Presence 0.6.4

## Added

- First-party Discord sign-in screen with explicit, user-triggered session import, inspired by the documented Kizzy workflow.
- Local credential vault backed by an Android Keystore AES-256-GCM key.
- Dedicated WebView data-directory isolation on Android 9+; session import fails closed when isolation cannot be initialized.
- Startup/return-path cleanup recovery for temporary WebView cookies and web storage.
- Static security audit script with checks for manifest exposure, source permissions, WebView restrictions, credential handling, diagnostics, and build-flavor gates.
- Source-level security assessment and documented residual risks.

## Security changes

- Removed manual token paste to avoid leaving session values in the keyboard or clipboard path.
- WebView blocks external top-level navigation, file/content access, file-origin access, mixed content, third-party cookies, persistent cache, and debugging.
- Credential is not displayed, copied, placed in an Intent, logged, uploaded, or backed up by Gecko.
- Diagnostics redact token-like secrets and log only sanitized error summaries.
- Native memory tombstones are no longer collected or stored; legacy Gecko tombstone files are purged on diagnostics initialization.
- Secure and automation source capabilities remain separate build flavors, with runtime gates in the secure flavor.

## Explicitly not included yet

- Discord Gateway WebSocket transport and `PRESENCE_UPDATE` publisher.
- Removal of the legacy Discord Social SDK adapter.
- Wiring `DiscordCredentialVault.loadForConnection()` to a publisher.
- Rich Presence Studio controls for every field and artwork.
- Live Discord session compatibility test, Android build, or device test in this environment.

## Validation

- `bash scripts/test-core.sh` — pass.
- `bash scripts/verify-source.sh` — pass.
- `bash scripts/security-audit.sh` — 68 static checks passed.
- Android Gradle build — not run successfully: Gradle distribution download failed due unavailable DNS, Android SDK Platform 35 was not available, and the required JDK 17 was not installed.
