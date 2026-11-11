# Design system — SIGNAL DECK

## Direction

Industrial brutalism with high-contrast yellow as the anchor: near-black canvas, hard rectangular panels, visible separators, sans-serif headings, and compact monospace metadata for measured values and technical labels. Status uses explicit text with the restrained accent/neutral palette. Avoid generic gradients, glass blur, ornamental dashboards, and decorative motion.

## Tokens

| Token | Value | Role |
|---|---|---|
| Screen | `#0B0B09` | Primary canvas |
| Panel | `#171713` | Content surfaces |
| Signal yellow | `#FFE500` | Primary action and focus |
| Primary text | `#F6F3E8` | Main copy |
| Secondary text | `#D7D2BE` | Supporting copy |
| Muted | `#AAA58F` | Metadata |

## Component rules

- Rectangular geometry; no default rounded cards or soft shadows.
- Yellow primary buttons with near-black text; dark secondary buttons with clear borders.
- Compact monospace labels and clear text hierarchy.
- Every status uses both text and color.
- Interactive touch targets at least 48dp.
- Keep native Android scrolling and system permission flows.
- Apply system-bar, display-cutout, and IME insets on target SDK 35.
- No fake charts, filler metrics, decorative badges, or nonfunctional controls.

## Navigation

1. Dashboard
2. Sources
3. Presence Studio
4. Automation
5. Library
6. Settings & Diagnostics

## Presence Studio

The editor and mock Discord card are local-first. The current transport maps activity label + details into Discord's details line, with optional state and start timestamp. The registered application name is controlled by the Developer Portal. Do not show artwork, buttons, party fields, or platform badges as functional controls until their transport mapping is implemented.
