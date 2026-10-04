package app.meanwhile.data.sync

import app.meanwhile.log.AppLog
import android.content.Context
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.json.RecordJson
import app.meanwhile.data.remote.AuthRepository
import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.notify.Notifications
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

enum class SyncOutcome { SUCCESS, NOT_CONFIGURED, NOT_SIGNED_IN, FAILED }

/**
 * Pushes unsynced rows (idempotent: `INSERT … ON CONFLICT (id) DO NOTHING`) and pulls rows missing
 * locally using each table's server-side `seq` as a cursor. A fresh install pulls everything (restore).
 */
class SyncEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val client: SupabaseClient?,
    private val auth: AuthRepository,
    private val settings: SettingsStore,
) {
    private val tables = syncTables(db)
    private val mutex = Mutex()

    val tableNames: List<String> get() = tables.map { it.name }

    suspend fun syncOnce(): SyncOutcome = mutex.withLock {
        val c = client ?: return SyncOutcome.NOT_CONFIGURED
        val uid = auth.awaitSessionUserId() ?: return SyncOutcome.NOT_SIGNED_IN
        val now = System.currentTimeMillis()
        try {
            for (t in tables) push(c, t, uid)
            for (t in tables) pull(c, t)
            if (settings.syncStatus.first().failingSince != null) AppLog.i(TAG, "sync recovered")
            AppLog.clearThrottle("sync-failing")
            settings.recordSyncSuccess(System.currentTimeMillis())
            Notifications.cancel(context, Notifications.ID_SYNC_FAILING)
            SyncOutcome.SUCCESS
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (AppLog.throttle("sync-failing", 30 * 60_000L)) AppLog.w(TAG, "sync failed (retrying automatically): ${e.message}", e)
            val since = settings.recordSyncFailure(now, e.message ?: e::class.java.simpleName)
            val pending = db.sync().pendingCount().first()
            if (pending > 0 && now - since > FAILING_NOTIFY_AFTER_MS && settings.claimSyncFailureNotification()) {
                Notifications.post(
                    context, Notifications.ID_SYNC_FAILING, Notifications.CHANNEL_ALERTS,
                    "Cloud sync failing",
                    "$pending records are waiting to sync for over an hour. Last error: ${e.message}",
                    destination = "settings",
                )
            }
            SyncOutcome.FAILED
        }
    }

    private suspend fun push(c: SupabaseClient, table: SyncTable<*>, uid: String) {
        while (true) {
            val batch = table.pending(PUSH_BATCH)
            if (batch.ids.isEmpty()) return
            val rows = batch.rows.map { withUser(it, uid) }
            try {
                upsert(c, table.name, rows)
                table.markSynced(batch.ids)
            } catch (e: RestException) {
                if (!isRowProblem(e)) throw e
                // One bad row must not block the queue: retry individually and set aside rejects.
                batch.ids.zip(rows).forEach { (id, row) ->
                    try {
                        upsert(c, table.name, listOf(row))
                        table.markSynced(listOf(id))
                    } catch (rowError: RestException) {
                        if (!isRowProblem(rowError)) throw rowError
                        table.markRejected(listOf(id))
                        AppLog.w(TAG, "server rejected ${table.name}/$id (kept on phone): ${rowError.description ?: rowError.error}")
                    }
                }
            }
            if (batch.ids.size < PUSH_BATCH) return
        }
    }

    private suspend fun upsert(c: SupabaseClient, table: String, rows: List<JsonObject>) {
        c.from(table).upsert(JsonArray(rows)) {
            onConflict = "id"
            ignoreDuplicates = true
        }
    }

    /** Client errors about the data itself (not auth/network): bad value, RLS mismatch, conflict. */
    private fun isRowProblem(e: RestException): Boolean = e.response.status.value in setOf(400, 403, 409, 422)

    private suspend fun pull(c: SupabaseClient, table: SyncTable<*>) {
        var cursor = settings.pullCursor(table.name)
        while (true) {
            val result = c.from(table.name).select {
                filter { gt("seq", cursor) }
                order("seq", Order.ASCENDING)
                limit(PULL_PAGE.toLong())
            }
            val rows = RecordJson.parseToJsonElement(result.data).jsonArray
            if (rows.isEmpty()) return
            table.insertPulled(rows)
            cursor = rows.maxOf { it.jsonObject["seq"]?.jsonPrimitive?.long ?: cursor }
            settings.setPullCursor(table.name, cursor)
            if (rows.size < PULL_PAGE) return
        }
    }

    private fun withUser(row: JsonObject, uid: String): JsonObject {
        val existing = row["user_id"]
        return if (existing == null || existing is JsonNull) JsonObject(row + ("user_id" to JsonPrimitive(uid))) else row
    }

    /** Rows for CSV export, keyed by table name. */
    suspend fun export(from: Long, to: Long, only: Set<String>? = null): Map<String, Pair<List<String>, List<JsonObject>>> =
        tables.filter { only == null || it.name in only }.associate { it.name to (it.columns to it.export(from, to)) }

    private companion object {
        const val PUSH_BATCH = 500
        const val PULL_PAGE = 1000
        const val FAILING_NOTIFY_AFTER_MS = 60 * 60 * 1000L
        const val TAG = "SyncEngine"
    }
}
