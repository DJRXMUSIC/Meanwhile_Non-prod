package app.meanwhile.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Which AI provider the Edge Function tries first (spec §10.2). */
enum class AiProviderPreference(val wire: String, val label: String) {
    GEMINI_FIRST("gemini_first", "Gemini first (default)"),
    CLAUDE_FIRST("claude_first", "Claude first"),
    GEMINI_ONLY("gemini_only", "Gemini only"),
    CLAUDE_ONLY("claude_only", "Claude only");

    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wire == value } ?: GEMINI_FIRST
    }
}

/** Light/dark appearance; SYSTEM follows the phone. */
enum class ThemeMode(val wire: String, val label: String) {
    SYSTEM("system", "Follow system"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

/** Device settings (not part of the dose profile, not synced). */
data class AppSettings(
    val aiProvider: AiProviderPreference = AiProviderPreference.GEMINI_FIRST,
    val xdripBaseUrl: String = DEFAULT_XDRIP_URL,
    val xdripPath: String = DEFAULT_XDRIP_PATH,
    val xdripApiSecret: String = "",
    val xdripPollSeconds: Int = 60,
    val xdripBroadcastEnabled: Boolean = true,
    val staleMinutes: Int = 15,
    val authSkipped: Boolean = false,
    val setupPromptsShown: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    companion object {
        const val DEFAULT_XDRIP_URL = "http://127.0.0.1:17580"
        const val DEFAULT_XDRIP_PATH = "/sgv.json"
    }
}

data class SyncStatus(
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    /** Start of the current run of consecutive failures (null when the last attempt succeeded). */
    val failingSince: Long? = null,
)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    private object Keys {
        val aiProvider = stringPreferencesKey("ai_provider")
        val xdripBaseUrl = stringPreferencesKey("xdrip_base_url")
        val xdripPath = stringPreferencesKey("xdrip_path")
        val xdripApiSecret = stringPreferencesKey("xdrip_api_secret")
        val xdripPollSeconds = intPreferencesKey("xdrip_poll_seconds")
        val xdripBroadcast = booleanPreferencesKey("xdrip_broadcast")
        val staleMinutes = intPreferencesKey("stale_minutes")
        val authSkipped = booleanPreferencesKey("auth_skipped")
        val setupPromptsShown = booleanPreferencesKey("setup_prompts_shown")
        val themeMode = stringPreferencesKey("theme_mode")
        val syncLastSuccess = longPreferencesKey("sync_last_success")
        val syncLastAttempt = longPreferencesKey("sync_last_attempt")
        val syncLastError = stringPreferencesKey("sync_last_error")
        val syncFailingSince = longPreferencesKey("sync_failing_since")
        val syncFailNotified = booleanPreferencesKey("sync_fail_notified")
        fun pullCursor(table: String) = longPreferencesKey("pull_cursor_$table")
        fun marker(name: String) = stringPreferencesKey("marker_$name")
    }

    private fun Preferences.toSettings() = AppSettings(
        aiProvider = AiProviderPreference.fromWire(this[Keys.aiProvider]),
        xdripBaseUrl = this[Keys.xdripBaseUrl] ?: AppSettings.DEFAULT_XDRIP_URL,
        xdripPath = this[Keys.xdripPath] ?: AppSettings.DEFAULT_XDRIP_PATH,
        xdripApiSecret = this[Keys.xdripApiSecret] ?: "",
        xdripPollSeconds = this[Keys.xdripPollSeconds] ?: 60,
        xdripBroadcastEnabled = this[Keys.xdripBroadcast] ?: true,
        staleMinutes = this[Keys.staleMinutes] ?: 15,
        authSkipped = this[Keys.authSkipped] ?: false,
        setupPromptsShown = this[Keys.setupPromptsShown] ?: false,
        themeMode = ThemeMode.fromWire(this[Keys.themeMode]),
    )

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val old = p.toSettings()
            val new = transform(old)
            p[Keys.aiProvider] = new.aiProvider.wire
            p[Keys.xdripBaseUrl] = new.xdripBaseUrl
            p[Keys.xdripPath] = new.xdripPath
            p[Keys.xdripApiSecret] = new.xdripApiSecret
            p[Keys.xdripPollSeconds] = new.xdripPollSeconds.coerceAtLeast(15)
            p[Keys.xdripBroadcast] = new.xdripBroadcastEnabled
            p[Keys.staleMinutes] = new.staleMinutes.coerceAtLeast(1)
            p[Keys.authSkipped] = new.authSkipped
            p[Keys.setupPromptsShown] = new.setupPromptsShown
            p[Keys.themeMode] = new.themeMode.wire
        }
    }

    // --- sync bookkeeping ---

    val syncStatus: Flow<SyncStatus> = store.data.map { p ->
        SyncStatus(
            lastSuccessAt = p[Keys.syncLastSuccess],
            lastAttemptAt = p[Keys.syncLastAttempt],
            lastError = p[Keys.syncLastError],
            failingSince = p[Keys.syncFailingSince],
        )
    }

    suspend fun recordSyncSuccess(at: Long) {
        store.edit { p ->
            p[Keys.syncLastSuccess] = at
            p[Keys.syncLastAttempt] = at
            p.remove(Keys.syncLastError)
            p.remove(Keys.syncFailingSince)
            p.remove(Keys.syncFailNotified)
        }
    }

    /** Returns the start of the current failure streak. */
    suspend fun recordSyncFailure(at: Long, error: String): Long {
        var since = at
        store.edit { p ->
            p[Keys.syncLastAttempt] = at
            p[Keys.syncLastError] = error.take(500)
            since = p[Keys.syncFailingSince] ?: at
            p[Keys.syncFailingSince] = since
        }
        return since
    }

    /** True the first time it's called during a failure streak. */
    suspend fun claimSyncFailureNotification(): Boolean {
        var claimed = false
        store.edit { p ->
            if (p[Keys.syncFailNotified] != true) {
                p[Keys.syncFailNotified] = true
                claimed = true
            }
        }
        return claimed
    }

    suspend fun pullCursor(table: String): Long = store.data.first()[Keys.pullCursor(table)] ?: 0L

    suspend fun setPullCursor(table: String, value: Long) {
        store.edit { it[Keys.pullCursor(table)] = value }
    }

    /** Small named string markers (e.g. "learn_cycle_done_for" → "2026-10-04"). */
    suspend fun marker(name: String): String? = store.data.first()[Keys.marker(name)]

    fun markerFlow(name: String): Flow<String?> = store.data.map { it[Keys.marker(name)] }

    suspend fun setMarker(name: String, value: String?) {
        store.edit { p -> if (value == null) p.remove(Keys.marker(name)) else p[Keys.marker(name)] = value }
    }
}
