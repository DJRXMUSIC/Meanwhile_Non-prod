package app.meanwhile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.meanwhile.MeanwhileApp

/** Restart the CGM service and re-arm alarms after reboot or an app update (spec §13.3). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                CgmService.start(context)
                (context.applicationContext as MeanwhileApp).container.onBootOrUpdate()
            }
        }
    }
}
