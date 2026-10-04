package app.meanwhile.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)
private val dateFmt = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

fun formatTime(epochMillis: Long): String = timeFmt.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
fun formatDateTime(epochMillis: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
fun formatDate(epochMillis: Long): String = dateFmt.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** "just now", "4 min ago", "2 h ago", "Tue Oct 3, 9:15 PM". */
fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - epochMillis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ${minutes % 60} min ago"
        else -> formatDateTime(epochMillis)
    }
}

fun formatUnits(units: Double): String =
    if (abs(units - units.roundToInt()) < 1e-9) "${units.roundToInt()} u" else String.format(Locale.US, "%.2f u", units)

fun fmt(value: Double, decimals: Int = 2): String = String.format(Locale.US, "%.${decimals}f", value)
