package app.meanwhile.di

import android.app.Application
import app.meanwhile.data.RecordFactory
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.FeedbackEntity
import app.meanwhile.data.export.CsvExporter
import app.meanwhile.data.remote.AuthRepository
import app.meanwhile.data.remote.AuthState
import app.meanwhile.data.remote.SupabaseProvider
import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.data.sync.SyncEngine
import app.meanwhile.data.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual dependency container (see docs/DECISIONS.md). One instance per process. */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy { AppDatabase.build(app) }
    val settings: SettingsStore by lazy { SettingsStore(app) }
    val supabase by lazy { SupabaseProvider.create() }
    val auth: AuthRepository by lazy { AuthRepository(supabase, settings, appScope) }
    val records: RecordFactory by lazy { RecordFactory(auth) }
    val sync: SyncEngine by lazy { SyncEngine(app, db, supabase, auth, settings) }
    val exporter: CsvExporter by lazy { CsvExporter(app, sync) }

    fun requestSync() = SyncWorker.requestNow(app)

    fun start() {
        SyncWorker.schedulePeriodic(app)
        requestSync()
        // A (re)sign-in pulls everything missing locally — this is the restore path.
        appScope.launch {
            auth.state.filterIsInstance<AuthState.SignedIn>()
                .map { it.userId to it.offline }
                .distinctUntilChanged()
                .collect { requestSync() }
        }
    }

    suspend fun addFeedback(text: String, context: String) {
        val m = records.meta()
        db.feedback().insert(FeedbackEntity(m.id, m.userId, m.createdAt, m.recordedAt, text = text, context = context))
        requestSync()
    }
}
