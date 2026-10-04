package app.meanwhile.data.cgm

import app.meanwhile.data.RecordFactory
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.CgmReadingEntity
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

fun CgmReadingEntity.toDomain() = CgmReading(
    timestamp = Instant.ofEpochMilli(recordedAt),
    mgDl = mgDl,
    trendRate = trendRate,
    direction = direction,
    source = source,
    sensorStatus = sensorStatus,
)

class CgmRepository(
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val onWrite: () -> Unit,
) {
    val latest: Flow<CgmReading?> = db.cgm().latestFlow().map { it?.toDomain() }

    fun since(from: Instant): Flow<List<CgmReading>> = db.cgm().sinceFlow(from.toEpochMilli()).map { list -> list.map { it.toDomain() } }

    suspend fun recent(from: Instant): List<CgmReading> = db.cgm().since(from.toEpochMilli()).map { it.toDomain() }

    suspend fun latestNow(): CgmReading? = db.cgm().latest()?.toDomain()

    /**
     * Inserts new readings (dedupe by timestamp and deterministic id); returns how many were new.
     * Readings stamped in the future are dropped: one would otherwise stay "latest" (BG on screen and in
     * the dose) until the clock caught up, and stop back-fill.
     */
    suspend fun save(readings: List<CgmReading>): Int {
        val now = System.currentTimeMillis()
        val valid = readings.filter { it.timestamp.toEpochMilli() <= now + MAX_FUTURE_MS }
        if (valid.isEmpty()) return 0
        val userId = records.meta().userId
        val rows = valid.distinctBy { it.timestamp }.map { r ->
            val ts = r.timestamp.toEpochMilli()
            CgmReadingEntity(
                id = UuidV7.deterministic(ts, "cgm:$ts"),
                userId = userId,
                createdAt = now,
                recordedAt = ts,
                mgDl = r.mgDl,
                trendRate = r.trendRate,
                direction = r.direction,
                source = r.source,
                sensorStatus = r.sensorStatus,
            )
        }
        val inserted = db.cgm().insertAll(rows).count { it != -1L }
        if (inserted > 0) onWrite()
        return inserted
    }

    private companion object {
        /** Tolerance for phone/transmitter clock skew. */
        const val MAX_FUTURE_MS = 2 * 60_000L
    }
}
