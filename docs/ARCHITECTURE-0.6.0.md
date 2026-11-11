# Gecko Presence 0.6.0 — Native Android architecture

## Product contract

Gecko Presence is an Android application. There is no browser extension or external companion process in the product runtime. All user configuration, source discovery, activity editing, preview, automation, presets, and diagnostics live in the APK.

## Runtime topology

```text
Android UI
  ├─ Dashboard / Sources / Studio / Automation / Library / Settings
  ├─ SharedPreferences: source settings, priorities, manual activity
  └─ PresencePresetStore: local JSON preset collection

PresenceEngineService (foreground)
  ├─ UsageAppTracker ── UsageStatsManager + explicit package allowlist
  ├─ MediaSessionPresenceTracker ── MediaSessionManager + NotificationListenerService
  ├─ BrowserAccessibilityService ── selected browser address-bar view IDs only
  ├─ PresenceSourceArbiter ── one candidate per source type + expiry
  └─ DiscordSdk ── JNI ── discord_partner_sdk AAR
```

## Source adapters

| Source | System capability | Collected data | Default |
|---|---|---|---|
| Android apps | Usage Access | selected foreground package label and user template | Off |
| Media sessions | Notification Access / MediaSessionManager | app label; optional title and artist | Off; title sharing off |
| Browser website | Accessibility Service | normalized public hostname only | Off |
| Manual custom presence | app-local input | user-authored activity label/details/state/start time | Off |

Browser detection is best-effort. It inspects only known address-bar resource IDs and only for selected browser packages. It does not inspect DOM or page text. If a browser version does not expose its address field, the adapter produces no candidate.

## Candidate arbitration

`PresenceUpdate` is the source contract. `PresenceSourceArbiter` keeps one candidate per `sourceType`, removes expired candidates, and selects the highest priority; start timestamp breaks ties. `PresencePriorityPolicy` persists automatic priorities from 1–99. Manual presence is fixed at 100. When a source clears or expires, the next eligible candidate becomes active.

Current default priorities:

- Manual: 100 (fixed)
- Media session: 98
- Android app: 95
- Website: 85

## Discord publishing

The Kotlin adapter initializes the official Social SDK client with the configured Application ID. Direct Rich Presence does not call the authenticated `Connect()` flow and does not use OAuth tokens. Discord controls the first-line application name from the Developer Portal; it cannot be customized per activity through the partial RPC Activity object. The current native mapping combines the source/activity label and user details into the `details` line, and maps the optional state and start timestamp. Other SDK fields are not exposed until the actual AAR mapping and callback behavior are verified.

The AAR is an external licensed input and is intentionally absent from the source archive. Builds without the AAR and Application ID are local Preview builds only.

## Lifecycle

The user starts the foreground `PresenceEngineService`. It initializes the optional Discord SDK, registers in-process source broadcasts, starts enabled source adapters, and publishes the selected candidate. The service periodically sweeps expired candidates. On stop it stops trackers, clears presence, destroys the SDK client, unregisters its receiver, and removes the foreground notification.

## Failure behavior

- Missing SDK/AAR/Application ID: local preview and source tracking remain available; Discord publishing is marked unavailable.
- Missing Usage Access: app tracking remains inactive and reports the missing permission.
- Missing Notification Access: media tracking remains inactive; notification content is never used as a fallback.
- Missing Accessibility permission or unsupported browser address field: website tracking remains inactive/no candidate.
- SDK update failure: status is surfaced in diagnostics; the app does not report a successful Discord publish based only on local preview.
