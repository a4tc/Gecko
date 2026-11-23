# Gecko Presence — Visual System

## Product promise

Gecko turns selected Android signals into one Discord activity. The interface should make three things immediately understandable: whether Gecko is on, which source currently owns the activity, and what Discord is expected to display.

## Visual direction

**Signal Deck / Cyberpunk Utility** combines an ink-dark technical surface, acid-lime primary actions, cyan information accents, and restrained magenta danger/secondary states. The world is inspired by neon-noir anime title cards and instrument panels, but the UI remains a legible Android utility rather than an illustration.

- Background: `#060A13`
- Surface: `#101725`
- Primary action: `#C8FF24`
- Information accent: `#00D8F5`
- Destructive / Turn Off accent: `#FF3D9A`
- Success: `#58F7A7`
- Primary text: `#F4F7FC`
- Secondary text: `#CBD5E1`
- Muted text: `#95A9BE`
- Surface outline: `#426B86`

## Typography and hierarchy

- Use the Android system sans family for activity titles, controls and body copy.
- Use monospace only for short technical labels, source identifiers, timestamps and diagnostics.
- Screen title: approximately 19sp; body: 12–14sp; metadata labels: 10–11sp.
- Use uppercase for short operational labels, not for paragraphs.
- Keep source status and Gateway status as separate facts; a local preview never implies Discord accepted the payload.

## Navigation and interaction

Compact phones use five bottom destinations: Home, Sources, Studio, Library and Settings. Automation priorities and Developer Console are secondary routes under Settings. The Android system Back gesture remains authoritative.

The Home screen owns the single runtime control. It changes between **TURN ON** and **TURN OFF**. Turn On starts the foreground service and requests Gateway connection in one user action. Turn Off stops the whole service, including detectors and Gateway. No second Connect/Disconnect control is presented in Developer Console.

Source preferences can be edited while the runtime is off; they do not start a partially running engine. The saved preferences take effect the next time Turn On starts Gecko.

## Studio preview

The Studio card approximates Discord's activity row:

- one large image on the left;
- optional small image overlaid at its lower-right corner;
- activity type and formatted headline;
- details, state and start time kept as separate fields;
- up to two action-button labels;
- account status shown as metadata.

The same `PresenceActivityDisplayFormatter` is used for the type-specific headline. Once a manual override has been applied, editing fields debounces, validates, persists and republishes the updated draft after 700ms. While the runtime is off, Studio stores the draft and asks the user to turn Gecko on before publishing.

## Artwork policy

Gallery imports remain private by default. Automatic public hosting is a separate standing opt-in with an explicit disclosure that Imgur uploads become public to anyone with their URL. It requires the user's Imgur Client-ID. Gecko uses the fixed Imgur anonymous upload endpoint only; it does not call Discord-internal image-upload endpoints or Kizzy's private relay. Reuse a previously uploaded URL for a SHA-256 duplicate. Detected artwork uses a separate bounded 200-entry URL cache and temporary private staging files, so routine media changes do not consume the user’s 40-item Gallery quota.

## Native Android constraints

- Keep native Android navigation, switches, dialogs, system settings hand-offs and touch feedback.
- Preserve safe-area/system insets and IME resizing.
- Interactive targets should remain at least 48dp high.
- Text uses sp and should remain readable at large system font scales.
- Avoid iOS-only gestures or controls; the iPhone reference is limited to visual restraint, rhythm and polish.
- No new UI framework dependency is introduced by this pass; the app remains native Android Views.

## Reference concept

`design-reference/gecko-cyberpunk-screens.png` is an art-direction reference for Home, Studio and Sources. It is not a screenshot of a built APK; exact text and runtime values in the concept image are illustrative.
