package app.meanwhile.data.sync

import android.os.Build
import app.meanwhile.BuildConfig
import app.meanwhile.data.json.isoOf
import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.diag.Diagnostics
import app.meanwhile.domain.util.UuidV7
import app.meanwhile.log.AppLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Uploads the phone's own log (every AppLog line) to Supabase `app_logs` with each sync (1.4), so a
 * problem can be looked up by time — "it broke around 2 pm" — instead of reproduced and copied.
 * Upload-only, deduplicated by a deterministic id, redacted like the diagnostics report.
 */
class LogUploader(private val settings: SettingsStore) {

    suspend fun upload(c: SupabaseClient, uid: String, now: Long = System.currentTimeMillis()): Int {
        val cursor = settings.marker(CURSOR)?.toLongOrNull() ?: 0L
        val entries = AppLog.entries().filter { it.at.toEpochMilli() >= cursor }.take(MAX_PER_SYNC)
        if (entries.isEmpty()) return 0
        var sent = 0
        for (chunk in entries.chunked(PAGE)) {
            c.from(TABLE).upsert(JsonArray(rows(chunk, uid, now))) {
                onConflict = "id"
                ignoreDuplicates = true
            }
            sent += chunk.size
            // Same-millisecond lines are re-sent next time and ignored by their id.
            settings.setMarker(CURSOR, chunk.last().at.toEpochMilli().toString())
        }
        return sent
    }

    companion object {
        const val TABLE = "app_logs"
        private const val CURSOR = "log_upload_cursor"
        private const val PAGE = 500
        private const val MAX_PER_SYNC = 3000

        /** Columns sent for each line (the sync contract test checks them against the migration). */
        val columns = listOf("id", "user_id", "created_at", "recorded_at", "level", "tag", "thread", "message", "detail", "app_version", "device")

        fun idFor(e: AppLog.Entry): String =
            UuidV7.deterministic(e.at.toEpochMilli(), "log|${e.at}|${e.level.code}|${e.tag}|${e.thread}|${e.message}")

        fun rows(entries: List<AppLog.Entry>, uid: String, now: Long): List<JsonObject> {
            val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            val device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
            return entries.map { e ->
                buildJsonObject {
                    put("id", idFor(e))
                    put("user_id", uid)
                    put("created_at", isoOf(now))
                    put("recorded_at", isoOf(e.at.toEpochMilli()))
                    put("level", e.level.code.toString())
                    put("tag", e.tag)
                    put("thread", e.thread)
                    put("message", Diagnostics.redact(e.message))
                    put("detail", Diagnostics.redact(e.detail))
                    put("app_version", version)
                    put("device", device)
                }
            }
        }
    }
}
