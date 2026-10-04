package app.meanwhile.data.cgm

import app.meanwhile.domain.cgm.CgmReading
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** A CGM feed (spec §13.1). A future built-in Eversense interceptor is another implementation. */
interface CgmSource {
    /** Readings newer than [since], for back-capture after the app or phone was down. */
    suspend fun fetchSince(since: Instant): List<CgmReading>

    /** New readings as they arrive. */
    fun live(): Flow<CgmReading>

    val name: String
}
