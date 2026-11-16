package app.geckopresence.host

import android.content.Intent
import android.service.notification.NotificationListenerService

/** Permission bridge only. Notification contents are deliberately never read or stored. */
class PresenceNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        sendBroadcast(
            Intent(PresenceEngineService.ACTION_REFRESH_MEDIA).setPackage(packageName),
            PresenceEngineService.INTERNAL_BROADCAST_PERMISSION,
        )
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        sendBroadcast(
            Intent(PresenceEngineService.ACTION_REFRESH_MEDIA).setPackage(packageName),
            PresenceEngineService.INTERNAL_BROADCAST_PERMISSION,
        )
    }
}
