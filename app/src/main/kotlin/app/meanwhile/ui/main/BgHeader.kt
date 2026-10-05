package app.meanwhile.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.cgm.Trend
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.rememberNow
import app.meanwhile.ui.theme.LocalGlucoseColors
import app.meanwhile.ui.theme.forMgDl
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Latest BG + trend + age, as used by the main screen and the NBA card. */
data class BgSnapshot(val reading: CgmReading?, val rate: Double?, val ageMinutes: Long?, val stale: Boolean)

@Composable
fun rememberBgSnapshot(): BgSnapshot {
    val c = LocalAppContainer.current
    val now by rememberNow()
    val since = remember { Instant.now().minus(Duration.ofHours(1)) }
    val readings by c.cgm.since(since).collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val latest = readings.lastOrNull()
    val latestAny by c.cgm.latest.collectAsStateWithLifecycle(initialValue = null)
    val reading = latest ?: latestAny
    val age = reading?.let { Duration.between(it.timestamp, now).toMinutes().coerceAtLeast(0) }
    val staleAfter = settings?.staleMinutes ?: 15
    return BgSnapshot(
        reading = reading,
        rate = Trend.rate(readings.filter { Duration.between(it.timestamp, now).toMinutes() <= 30 }),
        ageMinutes = age,
        stale = age == null || age > staleAfter,
    )
}

@Composable
fun BgHeader(bg: BgSnapshot, modifier: Modifier = Modifier, onTap: (() -> Unit)? = null) {
    val colors = LocalGlucoseColors.current
    Column(modifier = (if (onTap != null) modifier.clickable(onClick = onTap) else modifier).fillMaxWidth()) {
        if (bg.reading != null && bg.stale) {
            StaleBanner(bg.ageMinutes)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = bg.reading?.mgDl?.toString() ?: "—",
                fontSize = 84.sp,
                fontWeight = FontWeight.Bold,
                color = colors.forMgDl(bg.reading?.mgDl, bg.stale),
            )
            Column(Modifier.padding(bottom = 16.dp)) {
                Text(Trend.arrow(bg.rate), fontSize = 40.sp, color = colors.forMgDl(bg.reading?.mgDl, bg.stale))
                Text("mg/dL", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            text = when {
                bg.reading == null -> "No CGM readings yet — allow Eversense access (Settings → Setup)"
                else -> buildString {
                    bg.rate?.let { append(String.format(Locale.US, "%+.1f mg/dL/min · ", it)) }
                    append(if ((bg.ageMinutes ?: 0) < 1) "just now" else "${bg.ageMinutes} min ago")
                    if (onTap != null) append("  ·  stats ›")
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun StaleBanner(ageMinutes: Long?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Text(
            "CGM reading is ${ageMinutes ?: "?"} min old — check the Eversense app / transmitter before dosing.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}
