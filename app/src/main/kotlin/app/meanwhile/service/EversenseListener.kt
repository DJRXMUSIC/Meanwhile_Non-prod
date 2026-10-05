package app.meanwhile.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.meanwhile.MeanwhileApp
import app.meanwhile.data.cgm.EversenseSource
import app.meanwhile.data.cgm.NotificationTexts
import app.meanwhile.domain.cgm.EversenseNotification
import app.meanwhile.log.AppLog
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The built-in Eversense interceptor: Android hands every notification to an enabled listener, and the
 * official Eversense app's notification shows the current glucose. Android binds this service while
 * notification access is granted (keeping the process alive) and rebinds it after updates and crashes.
 */
class EversenseListener : NotificationListenerService() {

    override fun onListenerConnected() {
        AppLog.i("Eversense", "notification listener connected")
        // The current value, if the Eversense notification is already showing.
        runCatching { activeNotifications }.getOrNull()
            ?.filter { it.packageName in EversenseNotification.PACKAGES }
            ?.forEach(::handle)
        CgmService.start(this)
    }

    override fun onListenerDisconnected() {
        AppLog.w("Eversense", "notification listener disconnected — asking Android to reconnect")
        runCatching { NotificationListenerService.requestRebind(EversenseSource.listenerComponent(this)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in EversenseNotification.PACKAGES) handle(sbn)
    }

    private fun handle(sbn: StatusBarNotification) {
        val seen = try {
            NotificationTexts.extract(this, sbn.notification, sbn.packageName, Instant.ofEpochMilli(sbn.postTime))
        } catch (e: Exception) {
            if (AppLog.throttle("eversense-extract", 30 * 60_000L)) AppLog.e("Eversense", "couldn't read the notification from ${sbn.packageName}", e)
            return
        }
        val container = (application as MeanwhileApp).container
        container.appScope.launch {
            container.cgmIntake.acceptEversense(seen)
        }
        CgmService.start(this)
    }
}
