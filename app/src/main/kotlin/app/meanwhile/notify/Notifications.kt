package app.meanwhile.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.meanwhile.MainActivity
import app.meanwhile.R

/** Notification channels and ids (spec §13.4). */
object Notifications {
    const val CHANNEL_CGM = "cgm"
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_REPORTS = "reports"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_ADVICE = "advice"

    const val ID_FOREGROUND = 1
    const val ID_CGM_STALE = 2
    const val ID_SYNC_FAILING = 3
    const val ID_MORNING_REPORT = 4
    const val ID_AI_REFINEMENT = 5
    const val ID_LEARNING = 6
    /** Advice uses ID_ADVICE_BASE + the alert kind's ordinal (one live notification per kind). */
    const val ID_ADVICE_BASE = 20
    /** Split reminders use ID_SPLIT_BASE + a per-proposal offset. */
    const val ID_SPLIT_BASE = 1000

    const val EXTRA_OPEN = "app.meanwhile.OPEN"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_CGM, "Live glucose", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Persistent notification with your latest BG while Meanwhile listens to the CGM feed"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_REMINDERS, "Dose reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Second injection of a split dose"
                },
                NotificationChannel(CHANNEL_REPORTS, "Reports & proposals", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Morning report ready, AI refinements ready for review"
                },
                NotificationChannel(CHANNEL_ADVICE, "What to do now", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Lows, carbs to eat and corrections the dose math suggests on its own"
                },
                NotificationChannel(CHANNEL_ALERTS, "Problems", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "CGM readings stale, cloud sync failing"
                },
            ),
        )
    }

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Opens the app, optionally to a destination (see [MainActivity] for handled values). */
    fun openAppIntent(context: Context, destination: String? = null, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (destination != null) putExtra(EXTRA_OPEN, destination)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("MissingPermission") // checked by canPost
    fun post(context: Context, id: Int, channel: String, title: String, text: String, destination: String? = null, silent: Boolean = false) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context, destination, id))
            .setAutoCancel(true)
            .setSilent(silent)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)
}
