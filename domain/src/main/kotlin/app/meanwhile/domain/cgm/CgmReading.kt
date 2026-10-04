package app.meanwhile.domain.cgm

import java.time.Instant

/** One glucose reading. [trendRate] is mg/dL per minute as reported by the source (positive = rising). */
data class CgmReading(
    val timestamp: Instant,
    val mgDl: Int,
    val trendRate: Double?,
    val direction: String?,
    val source: String,
    val sensorStatus: String? = null,
)
