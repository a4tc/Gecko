# Kizzy Reference Notes

Research date: 2026-11-16

## Source-level patterns inspected

- [`MediaRpcService.kt`](https://github.com/dead8309/Kizzy/blob/master/feature_rpc_base/src/main/java/com/my/kizzy/feature_rpc_base/services/MediaRpcService.kt) registers `MediaSessionManager.OnActiveSessionsChangedListener`, tracks a current controller, and registers `MediaController.Callback` for playback, metadata and session-destroyed events.
- [`GetCurrentPlayingMediaAll.kt`](https://github.com/dead8309/Kizzy/blob/master/data/src/main/java/com/my/kizzy/data/get_current_data/media/GetCurrentPlayingMediaAll.kt) extracts cover art and app identity from active media metadata.
- [`RpcImage.kt`](https://github.com/dead8309/Kizzy/blob/master/data/src/main/java/com/my/kizzy/data/rpc/RpcImage.kt) represents Discord-hosted, external, application-icon and bitmap images separately and caches resolved image references.
- [`ActivityRow.kt`](https://github.com/dead8309/Kizzy/blob/master/feature_profile/src/main/java/com/my/kizzy/feature_profile/ui/component/ActivityRow.kt) renders a large artwork image, optional small overlay, activity metadata, timestamps/progress and action buttons.
- [`AppDetectionService.kt`](https://github.com/dead8309/Kizzy/blob/master/feature_rpc_base/src/main/java/com/my/kizzy/feature_rpc_base/services/AppDetectionService.kt) uses Android Usage Access and periodically checks selected applications.

Gecko adapts the patterns to its own implementation and permission model. Kizzy source is a reference, not code to copy; its image relay and Discord user-session transport are not documented public upload interfaces.

## Android API references

- [MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager)
- [MediaController](https://developer.android.com/reference/android/media/session/MediaController)
- [MediaMetadata](https://developer.android.com/reference/android/media/MediaMetadata)
- [UsageEvents](https://developer.android.com/reference/android/app/usage/UsageEvents)

Gecko now uses active-session change notifications and controller callbacks as the primary media update path, with a 10-second recovery poll, a short empty-session grace period, a 30-second candidate expiry, and bounded backoff for transient artwork upload failures. Metadata artwork URI fields are attempted before embedded Bitmap fields; application icon is the final fallback.

## Discord artwork boundary

The code uses a public HTTPS Imgur URL for uploaded artwork and sends that external URL as the activity asset. It deliberately does not use `mp:attachments/...` internal references or Kizzy's private proxy/API. The standing public-upload setting is off by default, requires a user-provided Imgur Client-ID, and has an explicit public-visibility disclosure.
