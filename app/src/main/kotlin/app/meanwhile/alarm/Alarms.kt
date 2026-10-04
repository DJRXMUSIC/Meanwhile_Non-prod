package app.meanwhile.alarm

import app.meanwhile.log.AppLog
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Exact alarms (spec §13.3): split-dose reminders now; 1 am learn cycle and 6 am F11 in M7. */
object Alarms {
    const val ACTION_SPLIT = "app.meanwhile.alarm.SPLIT"
    const val EXTRA_PROPOSAL = "proposal_id"
    const val EXTRA_UNITS = "units"
    const val EXTRA_DUE = "due_at"

    private fun splitIntent(context: Context, proposalId: String, units: Int, dueAt: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode(proposalId),
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_SPLIT)
                .putExtra(EXTRA_PROPOSAL, proposalId).putExtra(EXTRA_UNITS, units).putExtra(EXTRA_DUE, dueAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun scheduleSplit(context: Context, proposalId: String, units: Int, dueAt: Long) {
        exact(context, maxOf(dueAt, System.currentTimeMillis() + 1_000), splitIntent(context, proposalId, units, dueAt))
    }

    fun cancelSplit(context: Context, proposalId: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(splitIntent(context, proposalId, 0, 0))
        app.meanwhile.notify.Notifications.cancel(context, notificationId(proposalId))
    }

    fun exact(context: Context, at: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            AppLog.w("Alarms", "exact alarm refused, using inexact: ${e.message}")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    const val ACTION_LEARN = "app.meanwhile.alarm.LEARN"
    const val ACTION_F11 = "app.meanwhile.alarm.F11"

    /** Next [hour]:[minute] local time, exact (spec §11.1: 1 am learn cycle, 6 am F11). */
    fun scheduleDaily(context: Context, action: String, hour: Int, minute: Int = 0) {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        var next = now.toLocalDate().atTime(hour, minute).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(), Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        exact(context, next.toInstant().toEpochMilli(), pi)
    }

    fun requestCode(proposalId: String) = proposalId.hashCode() and 0x0FFFFFFF
    fun notificationId(proposalId: String) = app.meanwhile.notify.Notifications.ID_SPLIT_BASE + (requestCode(proposalId) % 100_000)
}
