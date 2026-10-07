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
        ConversationLogEntity::class,
    ],
    version = 3,
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
    abstract fun conversation(): ConversationLogDao
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

        /** 1.4: the word-for-word conversation log, and the full request on every AI call. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach(db::execSQL)
                db.execSQL("ALTER TABLE `ai_calls` ADD COLUMN `request` TEXT NOT NULL DEFAULT '{}'")
            }
        }

        /** Exactly the SQL Room generates for [ConversationLogEntity]. */
        val MIGRATION_2_3_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `conversation_log` (`id` TEXT NOT NULL, `userId` TEXT, `createdAt` INTEGER NOT NULL, " +
                "`recordedAt` INTEGER NOT NULL, `supersedesId` TEXT, `role` TEXT NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`details` TEXT NOT NULL, `inputId` TEXT, `syncState` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_conversation_log_recordedAt` ON `conversation_log` (`recordedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_conversation_log_syncState` ON `conversation_log` (`syncState`)",
            "CREATE INDEX IF NOT EXISTS `index_conversation_log_inputId` ON `conversation_log` (`inputId`)",
        )

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "meanwhile.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
