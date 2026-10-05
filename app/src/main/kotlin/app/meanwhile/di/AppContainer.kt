package app.meanwhile.di

import app.meanwhile.log.AppLog
import android.app.Application
import app.meanwhile.CrashLog
import app.meanwhile.diag.Diagnostics
import app.meanwhile.data.RecordFactory
import app.meanwhile.data.cgm.CgmFeedStatus
import app.meanwhile.data.cgm.CgmIntake
import app.meanwhile.data.cgm.CgmRepository
import app.meanwhile.data.cgm.EversenseSource
import app.meanwhile.data.cgm.XdripBroadcastSource
import app.meanwhile.data.cgm.XdripIntents
import app.meanwhile.data.cgm.XdripWebSource
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.alarm.Alarms
import app.meanwhile.data.ai.AiClient
import app.meanwhile.data.ai.AiHooksImpl
import app.meanwhile.data.ai.AiQueueProcessor
import app.meanwhile.data.ai.ProposalReview
import app.meanwhile.data.net.NetworkMonitor
import app.meanwhile.data.dose.DoseContextBuilder
import app.meanwhile.domain.dose.DoseEngine
import app.meanwhile.data.input.AiHooks
import app.meanwhile.data.input.FactorUpdater
import app.meanwhile.data.input.InputProcessor
import app.meanwhile.data.input.NbaService
import app.meanwhile.data.learn.LearnWorker
import app.meanwhile.data.learn.LearningEngine
import app.meanwhile.data.learn.NightlyJobs
import app.meanwhile.data.profile.ProfileRepository
import app.meanwhile.data.db.FeedbackEntity
import app.meanwhile.data.export.CsvExporter
import app.meanwhile.data.remote.AuthRepository
import app.meanwhile.data.remote.AuthState
import app.meanwhile.data.remote.SupabaseProvider
import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.data.stats.StatsRepository
import app.meanwhile.data.sync.SyncEngine
import app.meanwhile.data.sync.SyncWorker
import app.meanwhile.service.CgmService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container (see docs/DECISIONS.md). One instance per process. */
class AppContainer(val app: Application) {
    /** Background work; a failure is logged, never fatal (it would also take down CGM intake). */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> AppLog.e("Background", "task failed: ${e.message ?: e::class.java.simpleName}", e) },
    )

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
    val eversense: EversenseSource by lazy { EversenseSource() }
    val xdripWeb: XdripWebSource by lazy {
        XdripWebSource(http, settings) { ok, message ->
            val was = cgmStatus.value.webOk
            // xDrip+ is optional now that Eversense is read directly: without it installed, an
            // unreachable web service is expected, not a problem.
            val installed = EversenseSource.installed(app, XdripIntents.PACKAGE)
            // Polling runs every minute: log the transitions, and a failure at most every 30 min.
            if (ok && was == false) {
                AppLog.i("CGM", "xDrip+ web service reachable again")
                AppLog.clearThrottle("xdrip-web-failing")
            } else if (!ok && installed && AppLog.throttle("xdrip-web-failing", 30 * 60_000L)) {
                AppLog.w("CGM", "xDrip+ web service unreachable: $message")
            }
            val shown = if (ok || installed) message else "xDrip+ isn't installed (optional — Meanwhile reads the Eversense app directly)"
            cgmStatus.update {
                it.copy(webOk = ok, webMessage = shown, lastWebOkAt = if (ok) System.currentTimeMillis() else it.lastWebOkAt)
            }
        }
    }
    val cgmIntake: CgmIntake by lazy {
        CgmIntake(
            cgm, xdripWeb, XdripBroadcastSource(app), settings, cgmStatus, eversense,
            xdripInstalled = { EversenseSource.installed(app, XdripIntents.PACKAGE) },
        )
    }

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

    val network: NetworkMonitor by lazy { NetworkMonitor(app) }
    val ai: AiClient by lazy { AiClient(supabase, db, records, auth, settings, network, ::requestSync) }
    val aiHooksImpl: AiHooksImpl by lazy { AiHooksImpl(ai, db) }
    val aiQueue: AiQueueProcessor by lazy { AiQueueProcessor(app, db, ai, aiHooksImpl, profiles) }
    // Learned changes Danny accepts in a review are watched (keep/revert) like auto-applied ones.
    val review: ProposalReview by lazy { ProposalReview(db, records, profiles, ::requestSync) { v, applied -> learning.trackReviewed(v, applied) } }

    /** Online AI steps when Supabase is configured; the offline path never needs them. */
    val aiHooks: AiHooks get() = if (supabase != null) aiHooksImpl else object : AiHooks {}
    val inputs: InputProcessor by lazy { InputProcessor(db, records, profiles, factorUpdater, nba, ::requestSync) { aiHooks } }

    val learning: LearningEngine by lazy { LearningEngine(app, db, records, profiles, ai, settings, ::requestSync) }
    val nightly: NightlyJobs by lazy { NightlyJobs(app, db, records, profiles, cgm, settings, ::requestSync, learning = { learning }) }
    val stats: StatsRepository by lazy { StatsRepository(db) }
    val diagnostics: Diagnostics by lazy { Diagnostics(this) }

    fun requestSync() = SyncWorker.requestNow(app)

    /** 1 am learn cycle and 6 am overnight-highs alarms (re-armed after each fire, boot and app start). */
    suspend fun scheduleDailyAlarms() {
        val p = profiles.current().profile
        Alarms.scheduleDaily(app, Alarms.ACTION_LEARN, p.resetHour)
        Alarms.scheduleDaily(app, Alarms.ACTION_F11, (p.factor("F11")?.params?.get("endHour") ?: 6.0).toInt())
    }

    /** Called by [app.meanwhile.service.BootReceiver]: re-arm everything time-based. */
    fun onBootOrUpdate() {
        requestSync()
        appScope.launch {
            rearmAlarms()
            scheduleDailyAlarms()
        }
    }

    /** Re-arms split reminders that are still pending (alarms don't survive reboot). */
    suspend fun rearmAlarms() {
        nba.pendingSeconds().forEach { Alarms.scheduleSplit(app, it.proposalId, it.units, it.dueAt) }
    }

    /** Daily alarms (spec §11.1). The learn cycle runs in a worker (AI call up to ~2 min). */
    suspend fun onAlarm(action: String?) {
        AppLog.i("Alarm", "fired: ${action?.substringAfterLast('.')}")
        when (action) {
            Alarms.ACTION_LEARN -> LearnWorker.enqueue(app)
            Alarms.ACTION_F11 -> runCatching { nightly.overnightIfNeeded() }
        }
        scheduleDailyAlarms()
    }

    /** Periodic housekeeping from the CGM service: outcome tagging and a missed 6 am F11. */
    suspend fun housekeeping() {
        runCatching { nightly.tagOutcomes() }.onFailure { AppLog.e("Housekeeping", "outcome tagging failed: ${it.message}", it) }
        runCatching { nightly.overnightIfNeeded() }.onFailure { AppLog.e("Housekeeping", "overnight check failed: ${it.message}", it) }
        // Continuous learning: new outcomes → lessons → judge open changes → tune → maybe an AI review.
        runCatching { learning.afterOutcomes() }.onFailure { AppLog.e("Housekeeping", "learning failed: ${it.message}", it) }
    }

    fun start() {
        CgmService.start(app)
        appScope.launch {
            // Last run's crash (if any) becomes a feedback row: synced, exported, visible.
            CrashLog.takePending(app)?.let { runCatching { addFeedback(it, "crash") } }
            rearmAlarms()
            scheduleDailyAlarms()
            housekeeping()
        }
        SyncWorker.schedulePeriodic(app)
        requestSync()
        // Warm Room, the profile and the dose engine so the first Next Best Action is fast (spec §14 M8: < 200 ms).
        appScope.launch { runCatching { DoseEngine.compute(doseContext.build().input(), profiles.current().profile) } }
        // Back online (or signed in): run AI calls queued while offline (spec §9.4).
        appScope.launch {
            combine(network.online, auth.state) { online, a -> online && a is AuthState.SignedIn && !a.offline }
                .distinctUntilChanged()
                .collect { ready -> if (ready) runCatching { aiQueue.runPending() } }
        }
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
