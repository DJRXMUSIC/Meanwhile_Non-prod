package app.meanwhile.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.meanwhile.MeanwhileApp
import app.meanwhile.R
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.cgm.Trend
import app.meanwhile.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Long-running foreground service (type `specialUse`: no time limit, may start at boot) that keeps the
 * CGM intake alive and shows the latest BG in a persistent notification (spec §13.3).
 */
class CgmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false
    private var staleNotified = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, Notifications.ID_FOREGROUND, notification(null, null, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (!running) {
            running = true
            run()
        }
        return START_STICKY
    }

    private fun run() {
        val c = (application as MeanwhileApp).container
        c.cgmIntake.start(scope)
        val ticker = flow {
            while (true) {
                emit(Instant.now())
                delay(60_000)
            }
        }
        scope.launch {
            combine(c.cgm.latest, ticker, c.settings.settings) { latest, now, s -> Triple(latest, now, s.staleMinutes) }
                .collect { (latest, now, staleMinutes) ->
                    val recent = c.cgm.recent(now.minus(Duration.ofMinutes(20)))
                    val rate = Trend.rate(recent)
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    if (Notifications.canPost(this@CgmService)) {
                        nm.notify(Notifications.ID_FOREGROUND, notification(latest, rate, now))
                    }
                    checkStale(latest, now, staleMinutes)
                }
        }
    }

    private fun checkStale(latest: CgmReading?, now: Instant, staleMinutes: Int) {
        if (latest == null) return
        val age = Duration.between(latest.timestamp, now).toMinutes()
        if (age > staleMinutes) {
            if (!staleNotified) {
                staleNotified = true
                Notifications.post(
                    this, Notifications.ID_CGM_STALE, Notifications.CHANNEL_ALERTS,
                    "CGM readings are stale",
                    "Last reading was $age min ago. Check xDrip+ and the Eversense transmitter.",
                )
            }
        } else if (staleNotified) {
            staleNotified = false
            Notifications.cancel(this, Notifications.ID_CGM_STALE)
        }
    }

    private fun notification(latest: CgmReading?, rate: Double?, now: Instant?): Notification {
        val title: String
        val text: String
        if (latest == null) {
            title = "Meanwhile"
            text = "Waiting for CGM readings from xDrip+"
        } else {
            val age = Duration.between(latest.timestamp, now ?: Instant.now()).toMinutes()
            title = "${latest.mgDl} mg/dL ${Trend.arrow(rate ?: latest.trendRate)}"
            val rateText = (rate ?: latest.trendRate)?.let { String.format(Locale.US, "%+.1f mg/dL/min · ", it) } ?: ""
            text = rateText + if (age < 1) "just now" else "$age min ago"
        }
        return NotificationCompat.Builder(this, Notifications.CHANNEL_CGM)
            .setSmallIcon(R.drawable.ic_stat_drop)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openAppIntent(this))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        running = false
        super.onDestroy()
    }

    companion object {
        /** Safe to call from anywhere; background starts can be refused by the OS and are retried later. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CgmService::class.java))
            } catch (e: Exception) {
                Log.w("CgmService", "start refused: ${e.message}")
            }
        }
    }
}
