package app.meanwhile.data.cgm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.meanwhile.MeanwhileApp
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.cgm.Trend
import app.meanwhile.service.CgmService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

/**
 * xDrip+ "Broadcast locally" (Inter-app settings). Names verified against xDrip+ source
 * (`utilitymodels/Intents.java`): action `com.eveningoutpost.dexdrip.BgEstimate`, sent with receiver
 * permission `com.eveningoutpost.dexdrip.permissions.RECEIVE_BG_ESTIMATE`; slope is mg/dL per ms.
 */
object XdripIntents {
    const val ACTION_BG_ESTIMATE = "com.eveningoutpost.dexdrip.BgEstimate"
    private const val EXTRA_BG = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
    private const val EXTRA_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
    private const val EXTRA_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
    private const val EXTRA_TIME = "com.eveningoutpost.dexdrip.Extras.Time"
    const val SOURCE = "xdrip_broadcast"

    fun parse(intent: Intent): CgmReading? {
        if (intent.action != ACTION_BG_ESTIMATE) return null
        val bg = intent.getDoubleExtra(EXTRA_BG, Double.NaN)
        val time = intent.getLongExtra(EXTRA_TIME, 0L)
        if (bg.isNaN() || bg < 20 || bg > 600 || time <= 0L) return null
        val slope = intent.getDoubleExtra(EXTRA_SLOPE, Double.NaN)
        val direction = intent.getStringExtra(EXTRA_SLOPE_NAME)?.takeIf { it != "9" }
        return CgmReading(
            timestamp = Instant.ofEpochMilli(time),
            mgDl = bg.roundToInt(),
            trendRate = if (slope.isNaN()) Trend.rateForDirection(direction) else slope * 60_000.0,
            direction = direction,
            source = SOURCE,
        )
    }
}

/** Runtime-registered receiver: works for xDrip's implicit broadcasts while our service runs. */
class XdripBroadcastSource(private val context: Context) : CgmSource {
    override val name = XdripIntents.SOURCE

    override suspend fun fetchSince(since: Instant): List<CgmReading> = emptyList()

    override fun live(): Flow<CgmReading> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                XdripIntents.parse(intent)?.let { trySend(it) }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(XdripIntents.ACTION_BG_ESTIMATE), ContextCompat.RECEIVER_EXPORTED,
        )
        awaitClose { context.unregisterReceiver(receiver) }
    }
}

/**
 * Manifest receiver: gets broadcasts xDrip addresses to this package explicitly
 * ("Identify receiver" = app.meanwhile.v4), even when the app isn't running.
 */
class XdripManifestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reading = XdripIntents.parse(intent) ?: return
        val pending = goAsync()
        val container = (context.applicationContext as MeanwhileApp).container
        container.appScope.launch {
            try {
                container.cgm.save(listOf(reading))
                CgmService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
