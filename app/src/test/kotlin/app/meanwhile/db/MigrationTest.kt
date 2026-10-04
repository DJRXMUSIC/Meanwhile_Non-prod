package app.meanwhile.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.meanwhile.data.db.AppDatabase
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

    /** A real version-1 database: the v2 schema minus the learning journal, user_version 1, with data in it. */
    private fun createVersion1() {
        context.deleteDatabase(name)
        val fresh = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        fresh.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO feedback (id, userId, createdAt, recordedAt, supersedesId, text, context, syncState) VALUES ('f1', NULL, 1, 1, NULL, 'kept across the upgrade', '', 1)")
            execSQL("DROP TABLE learning_log")
            version = 1
        }
        fresh.close()
    }

    @Test
    fun `migration 1 to 2 adds the learning journal and keeps existing data`() = runBlocking {
        createVersion1()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        db.openHelper.writableDatabase // opens: migrates and validates
        assertEquals(2, db.openHelper.readableDatabase.version)
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
        val generated = mutableListOf<String>()
        db.openHelper.readableDatabase.query("SELECT sql FROM sqlite_master WHERE tbl_name = 'learning_log' AND sql IS NOT NULL").use { c ->
            while (c.moveToNext()) generated += c.getString(0)
        }
        db.close()
        val ours = AppDatabase.MIGRATION_1_2_SQL.map { it.replace("IF NOT EXISTS ", "") }
        assertEquals(ours.sorted(), generated.sorted())
    }
}
