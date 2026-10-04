package app.meanwhile.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Instant

/** Current time, refreshed every [periodMs] (for "3 min ago" labels and staleness). */
@Composable
fun rememberNow(periodMs: Long = 30_000): State<Instant> = produceState(Instant.now()) {
    while (true) {
        delay(periodMs)
        value = Instant.now()
    }
}
