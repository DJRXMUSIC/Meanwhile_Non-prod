package app.meanwhile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        LearningLogEntity::class,
    ],
    version = 2,
    // Debug and release KSP run in parallel and raced on the exported schema file; see DECISIONS.
    // Migrations are verified instead by MigrationTest (Room validates the migrated schema).
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
    abstract fun learningLog(): LearningLogDao
    abstract fun sync(): SyncDao

    companion object {
        /** 1.3: the learning journal. Exactly the SQL Room generates for [LearningLogEntity]. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }

        val MIGRATION_1_2_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `learning_log` (`id` TEXT NOT NULL, `userId` TEXT, `createdAt` INTEGER NOT NULL, " +
                "`recordedAt` INTEGER NOT NULL, `supersedesId` TEXT, `kind` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                "`details` TEXT NOT NULL, `profileVersionId` TEXT, `aiCallId` TEXT, `syncState` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_learning_log_recordedAt` ON `learning_log` (`recordedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_learning_log_syncState` ON `learning_log` (`syncState`)",
            "CREATE INDEX IF NOT EXISTS `index_learning_log_kind` ON `learning_log` (`kind`)",
        )

        val MIGRATIONS = arrayOf(MIGRATION_1_2)

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "meanwhile.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
