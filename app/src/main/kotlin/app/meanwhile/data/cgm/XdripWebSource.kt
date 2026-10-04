package app.meanwhile.data.cgm

import app.meanwhile.data.settings.AppSettings
import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.cgm.XdripSgv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/**
 * xDrip+ local web service (Nightscout-style `sgv.json` on 127.0.0.1:17580), polled every
 * `xdripPollSeconds`. Verified against xDrip+ source: `count` is capped at 1000; an `api-secret`
 * header (SHA-1 hex of the secret) is only required off-loopback.
 */
class XdripWebSource(
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    private val onStatus: (ok: Boolean, message: String?) -> Unit = { _, _ -> },
) : CgmSource {
    override val name = "xdrip_web"

    override suspend fun fetchSince(since: Instant): List<CgmReading> {
        val minutes = Duration.between(since, Instant.now()).toMinutes().coerceAtLeast(0)
        val count = (ceil(minutes / 5.0).toInt() + 3).coerceIn(3, 1000)
        return fetch(settings.current(), count).filter { it.timestamp.isAfter(since) }
    }

    override fun live(): Flow<CgmReading> = flow {
        var last = Instant.now().minus(Duration.ofMinutes(30))
        while (currentCoroutineContext().isActive) {
            val s = settings.current()
            try {
                val readings = fetchSince(last)
                readings.forEach { emit(it) }
                readings.maxOfOrNull { it.timestamp }?.let { last = it }
                onStatus(true, null)
            } catch (e: IOException) {
                onStatus(false, e.message ?: e::class.java.simpleName)
            }
            delay(s.xdripPollSeconds * 1000L)
        }
    }

    /** One request; throws IOException on connection/HTTP errors. */
    suspend fun fetch(s: AppSettings, count: Int): List<CgmReading> = withContext(Dispatchers.IO) {
        val base = s.xdripBaseUrl.trimEnd('/')
        val path = "/" + s.xdripPath.trimStart('/')
        val url = "$base$path?count=$count&sensor"
        val request = Request.Builder().url(url).apply {
            if (s.xdripApiSecret.isNotBlank()) header("api-secret", sha1Hex(s.xdripApiSecret))
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("xDrip+ answered HTTP ${response.code}")
            XdripSgv.parse(response.body.string(), name)
        }
    }

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
