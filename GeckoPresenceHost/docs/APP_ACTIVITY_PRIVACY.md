# Android App Activity Tracking

## Scope

The optional native-app tracker uses Android `UsageStatsManager` events to identify the currently resumed foreground package. It does not inspect another app's UI, view hierarchy, clipboard, notifications, messages, or internal storage.

## Opt-in controls

- The feature is off by default.
- Android Usage Access must be granted by the user in system settings.
- The user selects individual apps in an allowlist. The usage observer filters events against this allowlist before retaining a foreground package; unselected apps never generate Rich Presence.
- Known banking, payments, wallet, investment, authentication, password, health, identity, government, messaging, email and other private-data apps are filtered from the app picker using package-name and app-label deny rules. Android does not expose a general `Finance` value in `ApplicationInfo.category`, so the app does not pretend it can classify every installed finance app automatically.
- Browser apps are excluded from native-app tracking; browser activity is handled by the Gecko extension instead.
- App package history is not persisted. The tracker queries UsageEvents on a dedicated worker thread every 3 seconds while the user-started bridge service is running. It keeps only the current allow-listed foreground package in memory. Blocked and unselected package events are discarded immediately.
- A live diagnostic line reports poll count, UsageEvents count/type, and the active/last selected app label. It is ephemeral runtime state, not a usage timeline. The user can copy this diagnostic snapshot to troubleshoot detection without copying package history.
- Discord presence receives a generic source (`android.app`), the selected app's display label, user-configurable details, and an optional custom state. Package identifiers are not sent.

## What app activity can show

This feature identifies which selected app is in the foreground. It cannot generically read a TikTok video title, Instagram Reel, YouTube video title, Spotify track, or chess game details from another app. Those require app-specific supported APIs or broad additional permissions. This build does not request Notification Listener or Accessibility Service access.

Default activity labels include:

- TikTok: `Watching short videos`
- Instagram: `Watching Reels`
- YouTube: `Watching videos`
- Spotify: `Listening to music`
- Chess apps: `Playing chess`

Users may customize the app name, details, and optional state for each allowed app.

## Security notes

Usage Access is a sensitive Android permission. The implementation queries foreground activity events, filters to an explicit allowlist, avoids storing a usage timeline, and performs no remote upload of usage events. The Android service sends only the derived current presence to the local loopback HTTP bridge. Keep this feature disabled when it is not needed.

The package-name and keyword denylist is a conservative safety layer, not a universal classification system. The explicit allowlist remains the primary enforcement boundary: no app is tracked unless the user selects it. Review the selected-app list before enabling tracking, and leave sensitive apps unselected even if an OEM has mislabeled an app category.

## On-device verification

1. Open Android Settings → Special app access → Usage access and grant access to Gecko Presence Host.
2. In the app, select one ordinary test app (for example Spotify or YouTube), enable `Track Selected Android Apps`, and start the local bridge.
3. Switch to the selected app and leave it foreground for at least 3–6 seconds.
4. Return to Gecko Presence Host. The detector line should show polling and a selected app label; the activity preview should show the app name and its configured details.
5. Switch to an unselected app. The Android app activity should clear. Switch back to the selected app; it should return.
6. If the detector reports `no new events`, switch between apps once more. If it reports a query denial/error, recheck Usage Access and copy the diagnostic snapshot.

The local preview is independent of Discord publishing. A Preview build without the official Discord Social SDK and configured Application ID can detect and display local activity, but it cannot publish it to a Discord profile.
