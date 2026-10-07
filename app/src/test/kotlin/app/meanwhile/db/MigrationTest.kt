package app.meanwhile.db

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.ConversationLogEntity
import app.meanwhile.data.db.LearningLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Upgrading an installed app must never lose data or crash on open. Room validates the migrated
 * schema against the entities when the database opens, so a successful open after the migration
 * proves MIGRATION_1_2 creates exactly the table Room expects.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    /** Removes [columnDef] from [table] by recreating it from the SQL Room generated (keeps indices). */
    private fun dropColumn(db: SupportSQLiteDatabase, table: String, columnDef: String) {
        val sqls = mutableListOf<String>()
        db.query("SELECT sql FROM sqlite_master WHERE tbl_name = '$table' AND sql IS NOT NULL ORDER BY type DESC").use { c ->
            while (c.moveToNext()) sqls += c.getString(0)
        }
        db.execSQL("DROP TABLE `$table`")
        sqls.forEach { db.execSQL(it.replace(columnDef, "")) }
    }

    /** A real version-2 database: today's schema minus the 1.4 additions, user_version 2, with data in it. */
    private fun createVersion2(): Unit = createOld(2)

    /** A real version-1 database: the v2 schema minus the learning journal, user_version 1, with data in it. */
    private fun createVersion1(): Unit = createOld(1)

    private fun createOld(version: Int) {
        context.deleteDatabase(name)
        val fresh = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        fresh.openHelper.writableDatabase.apply {
            execSQL("DROP TABLE conversation_log")
            dropColumn(this, "ai_calls", ", `request` TEXT NOT NULL")
            if (version < 2) execSQL("DROP TABLE learning_log")
            execSQL("INSERT INTO feedback (id, userId, createdAt, recordedAt, supersedesId, text, context, syncState) VALUES ('f1', NULL, 1, 1, NULL, 'kept across the upgrade', '', 1)")
            execSQL(
                "INSERT INTO ai_calls (id, userId, createdAt, recordedAt, supersedesId, job, provider, model, latencyMs, fallbackUsed, " +
                    "requestSummary, response, validation, error, inputId, syncState) VALUES ('a1', NULL, 1, 1, NULL, 'route', 'gemini', 'g', 5, 0, " +
                    "'route: pizza', '{}', 'ok', NULL, NULL, 1)",
            )
            this.version = version
        }
        fresh.close()
    }

    @Test
    fun `migration 2 to 3 adds the conversation log and the AI request, keeping existing data`() = runBlocking {
        createVersion2()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        db.openHelper.writableDatabase // opens: migrates and validates
        assertEquals(3, db.openHelper.readableDatabase.version)
        assertEquals("kept across the upgrade", db.feedback().byContext("", 1).single().text)
        assertEquals("{}", db.aiCalls().since(0).single().request)
        db.conversation().insert(ConversationLogEntity(id = "c1", createdAt = 2, recordedAt = 2, role = "user", kind = "message", text = "took 6 units"))
        assertEquals("took 6 units", db.conversation().since(0).single().text)
        db.close()
    }

    @Test
    fun `migration 1 to 2 adds the learning journal and keeps existing data`() = runBlocking {
        createVersion1()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        db.openHelper.writableDatabase // opens: migrates and validates
        assertEquals(3, db.openHelper.readableDatabase.version)
        assertEquals("kept across the upgrade", db.feedback().byContext("", 1).single().text)
        db.learningLog().insert(LearningLogEntity(id = "l1", createdAt = 2, recordedAt = 2, kind = "lessons", summary = "works"))
        assertNotNull(db.learningLog().byId("l1"))
        db.close()
    }

    @Test
    fun `without the migration the upgrade is refused (the test above is not vacuous)`() {
        createVersion1()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase
            fail("opening a v1 database without MIGRATION_1_2 should fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("migration", ignoreCase = true))
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration SQL matches what Room generates for a fresh install`() {
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        fun generated(table: String): List<String> {
            val out = mutableListOf<String>()
            db.openHelper.readableDatabase.query("SELECT sql FROM sqlite_master WHERE tbl_name = '$table' AND sql IS NOT NULL").use { c ->
                while (c.moveToNext()) out += c.getString(0)
            }
            return out.sorted()
        }
        assertEquals(AppDatabase.MIGRATION_1_2_SQL.map { it.replace("IF NOT EXISTS ", "") }.sorted(), generated("learning_log"))
        assertEquals(AppDatabase.MIGRATION_2_3_SQL.map { it.replace("IF NOT EXISTS ", "") }.sorted(), generated("conversation_log"))
        db.close()
    }
}
