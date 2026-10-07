package app.meanwhile.data.cgm

import app.meanwhile.data.settings.AppSettings
import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.cgm.XdripSgv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/**
 * A local CGM web service: xDrip+'s (Nightscout-style `sgv.json` on 127.0.0.1:17580) or any app that
 * serves readings the same way at the configured address (1.4), polled every
 * `xdripPollSeconds`. Verified against xDrip+ source: `count` is capped at 1000; an `api-secret`
 * header (SHA-1 hex of the secret) is only required off-loopback.
 */
class XdripWebSource(
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    private val onStatus: (ok: Boolean, message: String?, settings: AppSettings) -> Unit = { _, _, _ -> },
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
                onStatus(true, null, s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onStatus(false, e.message ?: e::class.java.simpleName, s)
            }
            delay(s.xdripPollSeconds * 1000L)
        }
    }

    /** One request; throws IOException on connection/HTTP errors and for an invalid address. */
    suspend fun fetch(s: AppSettings, count: Int): List<CgmReading> = withContext(Dispatchers.IO) {
        val url = urlFor(s.xdripBaseUrl, s.xdripPath, count) ?: throw IOException("Invalid CGM web service address: ${s.xdripBaseUrl}")
        val request = Request.Builder().url(url).apply {
            if (s.xdripApiSecret.isNotBlank()) header("api-secret", sha1Hex(s.xdripApiSecret))
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("the CGM web service answered HTTP ${response.code}")
            XdripSgv.parse(response.body.string(), name)
        }
    }

    companion object {
        /** Null when [base] isn't a usable http(s) URL (e.g. typed without `http://`). */
        fun urlFor(base: String, path: String, count: Int = 1): HttpUrl? =
            "${base.trim().trimEnd('/')}/${path.trim().trimStart('/')}?count=$count&sensor".toHttpUrlOrNull()
    }

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
