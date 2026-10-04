package app.meanwhile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CgmReadingEntity::class,
        MealEntity::class,
        FactorEventEntity::class,
        DoseEntity::class,
        ProposalEntity::class,
        OutcomeEntity::class,
        ProfileVersionEntity::class,
        FactorDefinitionEntity::class,
        AiCallEntity::class,
        FeedbackEntity::class,
        InputEntity::class,
        AiQueueEntity::class,
    ],
    version = 1,
    // Debug and release KSP run in parallel and raced on the exported schema file; see DECISIONS.
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cgm(): CgmDao
    abstract fun meals(): MealDao
    abstract fun factorEvents(): FactorEventDao
    abstract fun doses(): DoseDao
    abstract fun proposals(): ProposalDao
    abstract fun outcomes(): OutcomeDao
    abstract fun profileVersions(): ProfileVersionDao
    abstract fun factorDefinitions(): FactorDefinitionDao
    abstract fun aiCalls(): AiCallDao
    abstract fun feedback(): FeedbackDao
    abstract fun inputs(): InputDao
    abstract fun aiQueue(): AiQueueDao
    abstract fun sync(): SyncDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "meanwhile.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
