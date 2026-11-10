# Design system — SIGNAL DECK

## Design direction

Industrial brutalism with high-contrast yellow as the anchor: black surfaces, hard rectangular blocks, obvious separators, condensed heavy headings, monospace metadata, and limited signal-red/acid-green status colors. The direction references the energy of neon cyberpunk interfaces without copying Cyberpunk 2077 / Edgerunners logos, characters, typography, or screen artwork.

## Tokens

| Token | Value | Role |
|---|---|---|
| `screen_bg` | `#0B0B09` | Primary canvas |
| `panel_bg` | `#171713` | Control panels |
| `accent_yellow` (`accent_cyan` legacy resource name) | `#FFE500` | Primary action, focus, highlights |
| `accent_yellow_dark` | `#B7A700` | Borders and pressed states |
| `text_primary` | `#F6F3E8` | Main text |
| `text_secondary` | `#D7D2BE` | Supporting text |
| `text_muted` | `#AAA58F` | Labels and metadata |
| `status_green` | `#C7F36B` | Healthy/connected |
| `status_red` | `#FF6258` | Error/blocked |

## Component rules

- Corners: 0dp by default; use geometry rather than pill cards.
- Borders: 1–2dp high-contrast strokes.
- Shadows: offset, hard-edged; no soft glass blur or gradient-heavy panels.
- Primary action: yellow fill, near-black label, visible ivory outline.
- Secondary action: transparent near-black fill, yellow/ivory outline.
- Data labels: compact monospace uppercase with tracking.
- Headlines: sans-serif black/condensed, tight line height, sharp scale contrast.
- Status colors must also be accompanied by text. Never rely on color alone.
- Touch targets: at least 44–48dp for buttons and app-picker rows.
- Focus: visible yellow/ivory contrast; no invisible keyboard focus.

## Information architecture

1. **Live Signal** — bridge and Discord states, current preview, connect controls.
2. **Bridge Routing** — start/stop loopback service and preview.
3. **Android Sources** — allowlist, searchable app picker, custom templates, single title-sharing control.
4. **Browser Module** — export optional XPI and manage the local pairing key.
5. **Privacy Contract** — explain collection boundaries and excluded sources.

## Icon

An original signal/radar mark in yellow, ivory and near-black. No external brand mark or character artwork is used.


## Custom Presence composer UX

- Keep **Automatic Rules** (per-app/per-site templates) and **Custom Presence** (a user-authored global preset) as separate destinations and separate data models.
- The Custom Presence screen uses a persistent Discord activity preview above a compact editor. Group fields into Activity, Text, Artwork, and Platform Badge.
- Use two distinct selectors: **Activity Type** (Playing/Listening/Watching/Competing, plus conditionally supported types) and **Status Display** (Name/State/Details). Never label these as the same setting.
- Large and small artwork controls show the selected image and tooltip text. Prefer registered asset keys; allow validated HTTPS image URLs only when the selected Discord transport supports them.
- Platform Badge is explicitly a visual label. The selector may offer Android, iOS, Desktop/PC, Samsung, Xbox, PS4, PS5, and Embedded; each maps to an artwork asset and tooltip. Do not claim this reflects the actual device unless detected.
- Manual Custom Presence shows a clear `MANUAL OVERRIDE` state and takes precedence over automatic app/browser tracking until disabled. The preview must show the active source and whether the data is simulated or actually published.
- Use bottom sheets or full-screen editor sections for complex controls; avoid stacked nested AlertDialogs for multi-field editing. Preserve draft state on rotation/navigation and provide Save, Preview, Apply, and Reset actions.
