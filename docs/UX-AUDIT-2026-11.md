# Gecko Presence — UI / UX Audit

Audit scope: source-level review of the native Android Views implementation and the edited Home, Sources, Studio, Library and Settings paths. This is not a device screenshot audit; Android SDK/emulator tooling was unavailable in the editing environment.

## Issues addressed

| Previous issue | Change |
|---|---|
| Engine and Gateway could be started/stopped independently from different pages | Home now owns one stateful Turn On / Turn Off control. Developer Console is diagnostic-only. |
| Source-setting changes could start a service with the Gateway disconnected | Source and profile changes update an already-running service only. Preferences remain saved while off. |
| Studio changes needed a manual Apply action every time | When a manual override is active, edits debounce for 700ms, validate and republish. When off, edits remain a draft until Turn On. |
| Studio showed two equal artwork slots, not a Discord-like activity row | Large artwork is primary and a smaller image overlays its lower-right edge. Type, headline, details, state, timestamp and button labels remain distinct. |
| Navigation hid Library and Settings under More | Five compact primary destinations are now Home, Sources, Studio, Library and Settings. Automation and Developer are secondary Settings routes. |
| Runtime color system was warm olive/yellow on near-black and lacked a clear status hierarchy | Updated to ink/navy surfaces, lime primary action, cyan information, magenta Turn Off/error and green engine-active status. Cards use a restrained diagonal surface gradient. |
| Android Back did not restore the previous in-app destination | A bounded 16-page history now restores the previous page before leaving the Activity. |
| Every auto-detected cover would consume the user Gallery quota | Automatic artwork now stages privately in cache and uses a 200-entry SHA-256 URL cache; manual Gallery quota is independent. |

## Accessibility and component checks

- Primary actions, switches, text fields and spinners retain minimum heights of approximately 48dp or more.
- Bottom navigation is 64dp high with five equal destinations.
- Text uses sp and respects system font scaling; controls retain native Android semantics.
- Settings continues to use `FLAG_SECURE` while displaying account/session-sensitive information.
- Preview image roles have content descriptions, navigation items expose a destination description, and form fields retain explicit hints/labels.
- Back navigation, IME resize and system inset handling remain Android-native.

Contrast ratios calculated from the design tokens (WCAG relative luminance calculation; normal text target 4.5:1):

| Foreground on panel `#101725` | Contrast |
|---|---:|
| Primary text `#F4F7FC` | 16.70:1 |
| Secondary text `#CBD5E1` | 12.08:1 |
| Muted text `#95A9BE` | 7.42:1 |
| Lime `#C8FF24` | 15.18:1 |
| Cyan `#00D8F5` | 10.37:1 |
| Magenta `#FF3D9A` | 5.45:1 |
| Green `#58F7A7` | 13.04:1 |

The surface outline `#426B86` has approximately 3.14:1 contrast against the panel surface, providing a clearer component boundary without becoming a dominant neon stroke.

## Known verification gaps

- No Android SDK `android.jar`, installed Gradle, or `gradle-wrapper.jar` was available. Android Kotlin sources were not compiled and no APK was assembled in this environment.
- No emulator/device screenshots were captured. Dynamic type at the largest system font sizes, TalkBack traversal, predictive Back behavior, OEM notification listener lifecycle and low-memory behavior still require device verification.
- The current app remains native Android Views with the platform Material theme; it was not migrated to Jetpack Compose or Material 3. The iPhone reference is limited to visual rhythm and restraint, not iOS-only behavior.
- The generated concept image is art direction, not a screenshot of the actual APK.
