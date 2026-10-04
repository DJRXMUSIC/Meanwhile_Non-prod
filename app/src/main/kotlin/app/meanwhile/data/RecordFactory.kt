package app.meanwhile.data

import app.meanwhile.data.remote.AuthRepository
import app.meanwhile.domain.util.UuidV7

/** Common fields for a new append-only record. */
data class RecordMeta(val id: String, val userId: String?, val createdAt: Long, val recordedAt: Long)

class RecordFactory(private val auth: AuthRepository) {
    suspend fun meta(recordedAt: Long? = null, now: Long = System.currentTimeMillis()): RecordMeta =
        RecordMeta(UuidV7.string(now), auth.userIdForRecords(), now, recordedAt ?: now)
}
