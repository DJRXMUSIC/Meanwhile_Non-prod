package app.meanwhile.data.cgm

import android.util.Log
import app.meanwhile.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Duration
import java.time.Instant

data class CgmFeedStatus(
    val webOk: Boolean? = null,
    val webMessage: String? = null,
    val lastWebOkAt: Long? = null,
    val lastBroadcastAt: Long? = null,
    val lastBackCapture: String? = null,
)

/** Runs inside [app.meanwhile.service.CgmService]: back-capture, then live web polling + broadcasts. */
class CgmIntake(
    private val repo: CgmRepository,
    private val web: XdripWebSource,
    private val broadcast: XdripBroadcastSource,
    private val settings: SettingsStore,
    val status: MutableStateFlow<CgmFeedStatus>,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { backCapture() }
            launch { web.live().collect { repo.save(listOf(it)) } }
            launch {
                settings.settings.map { it.xdripBroadcastEnabled }.distinctUntilChanged().collectLatest { enabled ->
                    if (enabled) {
                        broadcast.live().collect {
                            repo.save(listOf(it))
                            status.update { s -> s.copy(lastBroadcastAt = System.currentTimeMillis()) }
                        }
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
            Log.w("CgmIntake", "back-capture failed: ${e.message}")
            status.update { it.copy(lastBackCapture = "Back-fill failed: ${e.message}") }
            0
        }
    }
}
