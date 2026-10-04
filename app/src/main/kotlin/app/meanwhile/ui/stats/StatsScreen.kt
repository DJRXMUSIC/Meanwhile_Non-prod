package app.meanwhile.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.meanwhile.data.stats.StatsSnapshot
import app.meanwhile.domain.stats.Follow
import app.meanwhile.domain.stats.GlucoseSummary
import app.meanwhile.format.fmt
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.SectionCard
import app.meanwhile.ui.theme.LocalGlucoseColors
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val GOAL_TIR = 80.0

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    var days by remember { mutableIntStateOf(14) }
    val stats by produceState<StatsSnapshot?>(null, days) { value = c.stats.compute(days) }
    val colors = LocalGlucoseColors.current

    ScreenScaffold(title = "Reliability stats", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 7, 14, 30).forEach { d ->
                FilterChip(selected = days == d, onClick = { days = d }, label = { Text(if (d == 1) "Today" else "$d days") })
            }
        }
        val s = stats
        if (s == null) {
            Text("Calculating…")
            return@ScreenScaffold
        }

        SectionCard("Time in range (70–180)") {
            val tir = s.window.timeInRangePct
            Text(
                "${fmt(tir, 0)}%",
                fontSize = 48.sp, fontWeight = FontWeight.Bold,
                color = if (tir >= GOAL_TIR) colors.inRange else colors.high,
            )
            Text(
                "Goal: ${GOAL_TIR.toInt()}% sustained for 14 days · current streak ${s.goalStreakDays} day${if (s.goalStreakDays == 1) "" else "s"} ≥ ${GOAL_TIR.toInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
            SummaryRows("Selected period", s.window)
            HorizontalDivider()
            SummaryRows("Today", s.today)
        }

        SectionCard("Daily") {
            Legend()
            val fmtDay = DateTimeFormatter.ofPattern("EEE d", Locale.US)
            s.daily.forEach { (day, d) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fmtDay.format(day), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
                    RangeBar(d, Modifier.weight(1f))
                    Text(if (d.readings == 0) "—" else "${fmt(d.timeInRangePct, 0)}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                }
            }
        }

        SectionCard("Proposals: followed vs overridden") {
            val f = s.follow
            if (f.total == 0) {
                Text("No proposals in this period.")
            } else {
                Text("${f.total} proposals · followed ${fmt(f.pct(Follow.FOLLOWED), 0)}% · overridden ${fmt(f.pct(Follow.OVERRIDDEN), 0)}% · not logged ${fmt(f.pct(Follow.NOT_LOGGED), 0)}%")
                listOf(Follow.FOLLOWED to "Followed", Follow.OVERRIDDEN to "Overridden").forEach { (kind, label) ->
                    val o = f.outcomes[kind] ?: return@forEach
                    Text(
                        "$label (${o.proposals}, ${o.withOutcome} with outcomes): " +
                            listOfNotNull(
                                o.meanBg3h?.let { "mean BG at 3 h ${fmt(it, 0)}" },
                                o.inRange3hPct?.let { "in range at 3 h ${fmt(it, 0)}%" },
                                o.lowWithin4hPct?.let { "<70 within 4 h ${fmt(it, 0)}%" },
                                o.highWithin4hPct?.let { ">250 within 4 h ${fmt(it, 0)}%" },
                            ).joinToString(" · ").ifEmpty { "no outcomes yet" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        SectionCard("AI accuracy by provider / model") {
            if (s.ai.isEmpty()) Text("No AI calls in this period.")
            s.ai.forEach { a ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${a.provider} · ${a.model}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${a.calls} calls · ${fmt(a.successPct, 0)}% valid · ${a.invalid} invalid · ${a.errors} errors · " +
                            "${a.fallbackServed} as fallback" + (a.meanLatencyMs?.let { " · ${fmt(it / 1000, 1)} s avg" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (a.accepted + a.edited + a.rejected > 0) {
                        Text("Proposals: ${a.accepted} accepted, ${a.edited} edited, ${a.rejected} rejected", style = MaterialTheme.typography.bodySmall)
                    }
                    a.meanAbsCarbError?.let { Text("Meal estimates: ${a.estimates}, mean carb error ${fmt(it, 1)} g vs what you logged", style = MaterialTheme.typography.bodySmall) }
                    Text(a.byJob.entries.joinToString { "${it.key} ${it.value}" }, style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
        }

        SectionCard("Speed") {
            val ms = s.nbaComputeMs.sorted()
            if (ms.isEmpty()) {
                Text("No Next Best Action calculations yet.")
            } else {
                Text("Next Best Action: median ${ms[ms.size / 2]} ms, slowest ${ms.last()} ms (target < 200 ms)")
            }
            Text("Stats computed in ${s.computedInMs} ms", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SummaryRows(label: String, s: GlucoseSummary) {
    val colors = LocalGlucoseColors.current
    Text(label, style = MaterialTheme.typography.labelMedium)
    if (s.readings == 0) {
        Text("No readings", style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("In range ${fmt(s.timeInRangePct, 0)}%", color = colors.inRange, style = MaterialTheme.typography.bodySmall)
        Text("<70 ${fmt(s.timeBelow70Pct, 1)}%", color = colors.low, style = MaterialTheme.typography.bodySmall)
        Text("<54 ${fmt(s.timeBelow54Pct, 1)}%", color = colors.veryLow, style = MaterialTheme.typography.bodySmall)
        Text(">180 ${fmt(s.timeAbove180Pct, 0)}%", color = colors.high, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        listOfNotNull(
            s.meanMgDl?.let { "mean ${fmt(it, 0)}" }, s.sdMgDl?.let { "SD ${fmt(it, 0)}" }, s.gmi?.let { "GMI ${fmt(it, 1)}%" },
            ">250 ${fmt(s.timeAbove250Pct, 0)}%", "${fmt(s.coveredMinutes / 60, 1)} h of CGM",
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Stacked bar: below 70 | in range | above 180 (not a glucose graph). */
@Composable
private fun RangeBar(s: GlucoseSummary, modifier: Modifier) {
    val colors = LocalGlucoseColors.current
    Row(
        modifier
            .height(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (s.coveredMinutes > 0) {
            val parts = listOf(s.timeBelow70Pct to colors.low, s.timeInRangePct to colors.inRange, s.timeAbove180Pct to colors.high)
            parts.filter { it.first > 0 }.forEach { (pct, color) ->
                Box(
                    Modifier
                        .weight(pct.toFloat())
                        .fillMaxHeight()
                        .background(color),
                )
            }
        } else {
            Box(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Legend() {
    val colors = LocalGlucoseColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("below 70" to colors.low, "70–180" to colors.inRange, "above 180" to colors.high).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(10.dp).height(10.dp).background(color))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
