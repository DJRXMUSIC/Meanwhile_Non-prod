package app.meanwhile.testing

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.meanwhile.data.RecordFactory
import app.meanwhile.data.ai.AiClient
import app.meanwhile.data.cgm.CgmRepository
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.dose.DoseContextBuilder
import app.meanwhile.data.input.AiHooks
import app.meanwhile.data.input.ConversationLog
import app.meanwhile.data.input.FactorUpdater
import app.meanwhile.data.input.InputProcessor
import app.meanwhile.data.input.MealDraft
import app.meanwhile.data.input.NbaService
import app.meanwhile.data.learn.LearningEngine
import app.meanwhile.data.learn.NightlyJobs
import app.meanwhile.data.net.NetworkMonitor
import app.meanwhile.data.profile.ProfileRepository
import app.meanwhile.data.remote.AuthRepository
import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.domain.cgm.CgmReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The real data layer wired the way AppContainer wires it, over an in-memory Room database and a
 * throwaway DataStore — no network (AI unreachable), no WorkManager, no notifications.
 */
class TestEnv : Closeable {
    val context: Application = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val dir = File(context.cacheDir, "test-${UUID.randomUUID()}").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") })
    val auth = AuthRepository(null, settings, scope)
    val records = RecordFactory(auth)
    var writes = 0
        private set
    val onWrite: () -> Unit = { writes++ }
    val profiles = ProfileRepository(db, records, onWrite, zone = { ZONE })
    val cgm = CgmRepository(db, records, onWrite)
    val doseContext = DoseContextBuilder(db, cgm, profiles, zone = { ZONE })
    val scheduled = mutableListOf<Triple<String, Int, Long>>()
    val cancelled = mutableListOf<String>()
    val nba = NbaService(
        db, records, doseContext, onWrite,
        staleMinutes = { 15 },
        scheduleSecond = { id, units, due -> scheduled += Triple(id, units, due) },
        cancelSecond = { cancelled += it },
    )
    val factorUpdater = FactorUpdater(db, records, profiles, onWrite)
    val conversation = ConversationLog(db, records, onWrite)
    /** Advice notifications "posted" (2.0); nothing reaches the system tray in tests. */
    val posted = mutableListOf<app.meanwhile.domain.nba.Alert>()
    val advice = app.meanwhile.data.input.AdviceService(nba, settings, conversation) { posted += it }
    /** The AI layer the processor sees; offline unless a test swaps in a fake. */
    var aiHooks: AiHooks = object : AiHooks {}
    val inputs = InputProcessor(db, records, profiles, factorUpdater, nba, onWrite, conversation) { aiHooks }
    val network = NetworkMonitor(context)
    val ai = AiClient(null, db, records, auth, settings, network, onWrite)
    val learning = LearningEngine(context, db, records, profiles, ai, settings, onWrite, zone = { ZONE })
    val nightly = NightlyJobs(context, db, records, profiles, cgm, settings, onWrite, zone = { ZONE }, learning = { learning })

    /** Midnight [daysAgo] days ago in [ZONE] — scenarios live in the past (future CGM is rejected). */
    fun day(daysAgo: Long): Instant = LocalDate.now(ZONE).minusDays(daysAgo).atStartOfDay(ZONE).toInstant()

    /**
     * A meal dose at [at]: Next Best Action proposed with BG [bg], logged as proposed (or [units]), then
     * CGM every 5 min for 4 h 10 min drifting to [min] by +2 h and settling at [end] by +3 h.
     * Returns the proposal id.
     */
    suspend fun meal(at: Instant, end: Int, carbs: Double = 60.0, bg: Double = 100.0, min: Int = end, units: Int? = null): String {
        val card = nba.propose(MealDraft("lunch", carbs), inputId = null, now = at, bgOverride = bg)
        nba.logFromProposal(card, units ?: card.result.finalUnits, 0, null, now = at)
        curve(at, bg.toInt(), min, end)
        return card.proposalId
    }

    /** CGM every 5 min from [from] to [to] (inclusive), [mgAt] minutes after [from]. */
    suspend fun readings(from: Instant, minutes: Int, mgAt: (Int) -> Int) {
        cgm.save((0..minutes step 5).map { m -> CgmReading(from.plus(Duration.ofMinutes(m.toLong())), mgAt(m), null, "Flat", "test") })
    }

    suspend fun curve(start: Instant, from: Int, low: Int, end: Int) {
        val readings = (0..50).map { i ->
            val t = i * 5.0
            val mg = when {
                t <= 120 -> from + (low - from) * t / 120
                t <= 180 -> low + (end - low) * (t - 120) / 60
                else -> end.toDouble()
            }
            CgmReading(start.plus(Duration.ofMinutes(i * 5L)).plusSeconds(30), Math.round(mg).toInt(), null, "Flat", "test")
        }
        cgm.save(readings)
    }

    /** Outcome tagging + one learning pass, as the 15-minute housekeeping does. */
    suspend fun housekeeping(now: Instant) {
        nightly.tagOutcomes(now)
        learning.afterOutcomes(now)
    }

    override fun close() {
        db.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("America/New_York")
    }
}
