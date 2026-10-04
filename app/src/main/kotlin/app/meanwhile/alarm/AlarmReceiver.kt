package app.meanwhile.alarm

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.meanwhile.MeanwhileApp
import app.meanwhile.R
import app.meanwhile.notify.Notifications
import app.meanwhile.format.formatTime
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Alarms.ACTION_SPLIT -> splitReminder(context, intent)
            else -> {
                val pending = goAsync()
                val c = (context.applicationContext as MeanwhileApp).container
                c.appScope.launch {
                    try {
                        c.onAlarm(intent.action)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun splitReminder(context: Context, intent: Intent) {
        val proposalId = intent.getStringExtra(Alarms.EXTRA_PROPOSAL) ?: return
        val units = intent.getIntExtra(Alarms.EXTRA_UNITS, 0)
        if (!Notifications.canPost(context)) return
        val logIntent = PendingIntent.getBroadcast(
            context, Alarms.requestCode(proposalId) + 1,
            Intent(context, DoseActionReceiver::class.java).setAction(DoseActionReceiver.ACTION_LOG_SECOND)
                .putExtra(Alarms.EXTRA_PROPOSAL, proposalId).putExtra(Alarms.EXTRA_UNITS, units),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle("Second injection: $units u")
            .setContentText("Split dose from ${formatTime(intent.getLongExtra(Alarms.EXTRA_DUE, System.currentTimeMillis()) - 60 * 60_000L)} meal. Tap Log after injecting.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(Notifications.openAppIntent(context, null, Alarms.requestCode(proposalId) + 2))
            .addAction(0, "Log $units u", logIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(Alarms.notificationId(proposalId), n)
    }
}

/** "Log N u" action on the split reminder (spec §9.6: second injection logged from the notification). */
class DoseActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG_SECOND) return
        val proposalId = intent.getStringExtra(Alarms.EXTRA_PROPOSAL) ?: return
        val units = intent.getIntExtra(Alarms.EXTRA_UNITS, 0)
        val pending = goAsync()
        val c = (context.applicationContext as MeanwhileApp).container
        c.appScope.launch {
            try {
                c.nba.logSecond(proposalId, units)
                Notifications.post(
                    context, Alarms.notificationId(proposalId), Notifications.CHANNEL_REMINDERS,
                    "Logged $units u", "Second injection recorded.",
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOG_SECOND = "app.meanwhile.alarm.LOG_SECOND"
    }
}
