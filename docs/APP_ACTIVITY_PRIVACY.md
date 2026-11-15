# Android app activity tracking

The optional Android app tracker uses `UsageStatsManager.queryEvents` to identify the current foreground package. It does not inspect another app's UI, view hierarchy, clipboard, notifications, messages, or internal storage.

## Opt-in controls

- Tracking is off by default.
- Android Usage Access must be granted by the user.
- The user selects individual launchable apps in an allowlist.
- Banking, payments, wallet, investment, authentication, password, health, identity, government, messaging, email, browser, and other private-data categories are filtered by package and label rules.
- Only the currently selected foreground package is retained in memory. A usage timeline is not persisted.
- The service reports a generic app activity label and optional user-defined details/state. Package identifiers are not sent to Discord.

## Limits

Usage Access cannot reveal a movie title, song title, message, or arbitrary UI text from another application. Media metadata is handled by the separate optional Media Session source, not by Usage Access.

## Device verification

1. Grant Usage Access to Gecko Presence in Android Settings.
2. Select one ordinary app in Sources.
3. Enable app tracking and start the Presence Engine.
4. Switch to the selected app and wait for the polling interval.
5. Switch to an unselected app and verify the Android app candidate clears.
6. Verify that the source priority arbitration restores the next active source when the app candidate clears.

A local preview is not evidence that Discord received a live activity.
