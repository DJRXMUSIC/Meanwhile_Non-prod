package app.meanwhile.di

import android.app.Application
import app.meanwhile.data.RecordFactory
import app.meanwhile.data.cgm.CgmFeedStatus
import app.meanwhile.data.cgm.CgmIntake
import app.meanwhile.data.cgm.CgmRepository
import app.meanwhile.data.cgm.XdripBroadcastSource
import app.meanwhile.data.cgm.XdripWebSource
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.alarm.Alarms
import app.meanwhile.data.dose.DoseContextBuilder
import app.meanwhile.data.input.AiHooks
import app.meanwhile.data.input.FactorUpdater
import app.meanwhile.data.input.InputProcessor
import app.meanwhile.data.input.NbaService
import app.meanwhile.data.profile.ProfileRepository
import app.meanwhile.data.db.FeedbackEntity
import app.meanwhile.data.export.CsvExporter
import app.meanwhile.data.remote.AuthRepository
import app.meanwhile.data.remote.AuthState
import app.meanwhile.data.remote.SupabaseProvider
import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.data.sync.SyncEngine
import app.meanwhile.data.sync.SyncWorker
import app.meanwhile.service.CgmService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    val cgm: CgmRepository by lazy { CgmRepository(db, records, ::requestSync) }
    val cgmStatus = MutableStateFlow(CgmFeedStatus())
    val xdripWeb: XdripWebSource by lazy {
        XdripWebSource(http, settings) { ok, message ->
            cgmStatus.update {
                it.copy(webOk = ok, webMessage = message, lastWebOkAt = if (ok) System.currentTimeMillis() else it.lastWebOkAt)
            }
        }
    }
    val cgmIntake: CgmIntake by lazy { CgmIntake(cgm, xdripWeb, XdripBroadcastSource(app), settings, cgmStatus) }

    val profiles: ProfileRepository by lazy { ProfileRepository(db, records, ::requestSync) }
    val doseContext: DoseContextBuilder by lazy { DoseContextBuilder(db, cgm, profiles) }
    val factorUpdater: FactorUpdater by lazy { FactorUpdater(db, records, profiles, ::requestSync) }
    val nba: NbaService by lazy {
        NbaService(
            db, records, doseContext, ::requestSync,
            staleMinutes = { settings.current().staleMinutes },
            scheduleSecond = { id, units, due -> Alarms.scheduleSplit(app, id, units, due) },
            cancelSecond = { id -> Alarms.cancelSplit(app, id) },
        )
    }

    /** Replaced by the AI client in M6; the offline path never needs it. */
    @Volatile var aiHooks: AiHooks = object : AiHooks {}
    val inputs: InputProcessor by lazy { InputProcessor(db, records, profiles, factorUpdater, nba, ::requestSync) { aiHooks } }

    fun requestSync() = SyncWorker.requestNow(app)

    /** Called by [app.meanwhile.service.BootReceiver]: re-arm everything time-based. */
    fun onBootOrUpdate() {
        requestSync()
        appScope.launch { rearmAlarms() }
    }

    /** Re-arms split reminders that are still pending (alarms don't survive reboot). */
    suspend fun rearmAlarms() {
        nba.pendingSeconds().forEach { Alarms.scheduleSplit(app, it.proposalId, it.units, it.dueAt) }
    }

    /** Non-split alarms (M7 adds the 1 am learn cycle and 6 am F11). */
    suspend fun onAlarm(action: String?) {
        // Filled in by later milestones.
    }

    fun start() {
        CgmService.start(app)
        appScope.launch { rearmAlarms() }
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
