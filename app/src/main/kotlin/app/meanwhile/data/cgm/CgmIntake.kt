package app.meanwhile.data.cgm

import app.meanwhile.log.AppLog
import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.cgm.EversenseNotification
import app.meanwhile.domain.cgm.ReadingGate
import app.meanwhile.domain.cgm.Trend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Duration
import java.time.Instant

data class CgmFeedStatus(
    val webOk: Boolean? = null,
    val webMessage: String? = null,
    val lastWebOkAt: Long? = null,
    val lastBroadcastAt: Long? = null,
    val lastBackCapture: String? = null,
    /** Built-in Eversense interceptor: last notification seen, last reading saved from one, what happened. */
    val eversenseSeenAt: Long? = null,
    val eversenseSavedAt: Long? = null,
    val eversenseMessage: String? = null,
    /** The texts of the last Eversense notification ("what Meanwhile sees"). */
    val eversenseTexts: String? = null,
)

/**
 * Runs inside [app.meanwhile.service.CgmService]: back-capture, then live web polling + broadcasts.
 * Eversense notifications arrive through [acceptEversense] whether or not the service is running.
 */
class CgmIntake(
    private val repo: CgmRepository,
    private val web: XdripWebSource,
    private val broadcast: XdripBroadcastSource,
    private val settings: SettingsStore,
    val status: MutableStateFlow<CgmFeedStatus>,
    private val eversense: EversenseSource? = null,
    /**
     * Whether a local CGM web service is expected to answer: xDrip+ is installed, or a custom address
     * is set (1.4: another app serving xDrip-style readings). When none is, a failed back-fill is quiet.
     */
    private val webExpected: suspend () -> Boolean = { true },
) {
    private var job: Job? = null
    private val eversenseLock = Mutex()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { backCapture() }
            launch { web.live().collect { repo.save(listOf(it)) } }
            launch {
                settings.settings.map { it.xdripBroadcastEnabled }.distinctUntilChanged().collectLatest { enabled ->
                    if (enabled) broadcast.live().collect { acceptBroadcast(it) }
                }
            }
        }
    }

    /**
     * Any app on the phone can send xDrip's broadcast action and Android can't tell us the sender, so a
     * broadcast is a "new reading now" signal: the readings are fetched from xDrip+'s local web service
     * and the broadcast's own value is only used when that service can't be reached at all.
     */
    suspend fun acceptBroadcast(reading: CgmReading) {
        status.update { it.copy(lastBroadcastAt = System.currentTimeMillis()) }
        val fromWeb = try {
            web.fetchSince(reading.timestamp.minus(Duration.ofMinutes(15)))
        } catch (e: IOException) {
            null
        }
        if (fromWeb == null) {
            repo.save(listOf(reading))
        } else {
            repo.save(fromWeb)
            if (fromWeb.none { it.timestamp == reading.timestamp }) {
                AppLog.w("CGM", "broadcast reading at ${reading.timestamp} not confirmed by the xDrip+ web service; ignored")
            }
        }
    }

    /**
     * One Eversense notification (built-in interceptor): parsed strictly, then saved unless it is a
     * re-post, a reading another source already delivered, or a value stuck for 35 min. Returns whether
     * a reading was saved.
     */
    suspend fun acceptEversense(n: CompanionNotification, now: Instant = Instant.now()): Boolean = eversenseLock.withLock {
        val texts = (n.texts + n.descriptions.map { "[$it]" }).joinToString(" | ").take(300)
        status.update { it.copy(eversenseSeenAt = now.toEpochMilli(), eversenseTexts = texts) }
        when (val parsed = EversenseNotification.parse(n.texts, n.descriptions)) {
            is EversenseNotification.Result.Rejected -> {
                status.update { it.copy(eversenseMessage = "Not a reading: ${parsed.reason}") }
                if (AppLog.throttle("eversense-not-understood", 30 * 60_000L)) {
                    AppLog.w("Eversense", "notification from ${n.packageName} not understood (${parsed.reason}); texts: $texts")
                }
                false
            }
            is EversenseNotification.Result.Reading -> {
                // The notification's post time is when the app got the reading; never later than now.
                val at = if (n.postedAt.isAfter(now)) now else n.postedAt
                val recent = repo.between(at.minus(Duration.ofMinutes(45)), at.plus(ReadingGate.REPOST))
                when (val d = ReadingGate.decide(parsed.mgDl, at, EversenseNotification.SOURCE, recent)) {
                    is ReadingGate.Decision.Skip -> {
                        status.update { it.copy(eversenseMessage = "${parsed.mgDl} mg/dL not saved: ${d.reason}") }
                        if (d.stuck && AppLog.throttle("eversense-stuck", 30 * 60_000L)) AppLog.w("Eversense", d.reason)
                        false
                    }
                    ReadingGate.Decision.Accept -> {
                        val reading = CgmReading(at, parsed.mgDl, Trend.rateForDirection(parsed.direction), parsed.direction, EversenseNotification.SOURCE)
                        val saved = repo.save(listOf(reading)) > 0
                        if (saved) {
                            AppLog.clearThrottle("eversense-not-understood")
                            status.update { it.copy(eversenseSavedAt = now.toEpochMilli(), eversenseMessage = "${parsed.mgDl} mg/dL" + (reading.trendRate?.let { r -> " " + Trend.arrow(r) } ?: "") + " saved") }
                            eversense?.emit(reading)
                        }
                        saved
                    }
                }
            }
        }
    }

    /** Fills the gap since the newest stored reading (spec §13.3: on every service start). */
    suspend fun backCapture(): Int {
        val since = repo.latestNow()?.timestamp ?: Instant.now().minus(Duration.ofHours(24))
        return try {
            val n = repo.save(web.fetchSince(since))
            status.update { it.copy(lastBackCapture = "Back-filled $n readings") }
            n
        } catch (e: IOException) {
            if (webExpected()) {
                AppLog.w("CGM", "back-capture failed: ${e.message}")
                status.update { it.copy(lastBackCapture = "Back-fill failed: ${e.message}") }
            } else {
                status.update { it.copy(lastBackCapture = "No back-fill: no local CGM web service is running (xDrip+ or compatible — optional)") }
            }
            0
        }
    }
}
