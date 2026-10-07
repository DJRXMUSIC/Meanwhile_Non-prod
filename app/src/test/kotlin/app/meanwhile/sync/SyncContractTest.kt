package app.meanwhile.sync

import app.meanwhile.data.sync.LogUploader
import app.meanwhile.data.sync.syncTables
import app.meanwhile.testing.TestEnv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The phone and Supabase must agree on every table: each column the app sends exists on the server,
 * and every column the server requires is sent. Parsed straight from supabase/migrations, so a
 * migration and an entity can't drift apart unnoticed.
 */
@RunWith(RobolectricTestRunner::class)
class SyncContractTest {
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    private data class Column(val name: String, val required: Boolean)

    private val serverTables: Map<String, List<Column>> by lazy {
        val dir = listOf(File("../supabase/migrations"), File("supabase/migrations")).first { it.isDirectory }
        val sql = dir.listFiles()!!.filter { it.name.matches(Regex("\\d+_.*\\.sql")) }.sortedBy { it.name }.joinToString("\n") { it.readText() }
        val tables = Regex("create table public\\.(\\w+) \\((.*?)\\n\\);", RegexOption.DOT_MATCHES_ALL).findAll(sql).associate { m ->
            m.groupValues[1] to m.groupValues[2].lines().map { it.trim().trimEnd(',') }
                .filter { it.isNotEmpty() && !it.startsWith("--") }
                .map { line -> Column(line.substringBefore(' '), required(line)) }
        }.toMutableMap()
        // Later migrations add columns: "alter table public.ai_calls add column request jsonb …;"
        Regex("alter table public\\.(\\w+) add column (\\w+) ([^;]+);").findAll(sql).forEach { m ->
            tables[m.groupValues[1]] = tables.getValue(m.groupValues[1]) + Column(m.groupValues[2], required(m.groupValues[3]))
        }
        tables
    }

    private fun required(definition: String) =
        "not null" in definition && "default" !in definition && "generated" !in definition && "primary key" !in definition

    @Test
    fun `every synced table exists on the server with matching columns`() {
        val tables = syncTables(env.db)
        assertEquals("server tables vs synced tables", serverTables.keys - UPLOAD_ONLY, tables.map { it.name }.toSet())
        for (t in tables) {
            val server = serverTables.getValue(t.name)
            val serverNames = server.map { it.name }.toSet()
            val missingOnServer = t.columns - serverNames
            assertTrue("${t.name}: app sends columns the server doesn't have: $missingOnServer", missingOnServer.isEmpty())
            val notSent = server.filter { it.required }.map { it.name }.toSet() - t.columns.toSet()
            assertTrue("${t.name}: server requires columns the app never sends: $notSent", notSent.isEmpty())
            assertTrue("${t.name}: server-only columns must not be sent", "seq" !in t.columns && "server_inserted_at" !in t.columns)
        }
    }

    @Test
    fun `learning journal and conversation are synced`() {
        assertTrue(syncTables(env.db).any { it.name == "learning_log" })
        assertTrue(syncTables(env.db).any { it.name == "conversation_log" })
    }

    @Test
    fun `app log rows match the server table`() {
        val server = serverTables.getValue("app_logs")
        val sent = LogUploader.columns
        val missingOnServer = sent - server.map { it.name }.toSet()
        assertTrue("app_logs: the uploader sends columns the server doesn't have: $missingOnServer", missingOnServer.isEmpty())
        val notSent = server.filter { it.required }.map { it.name }.toSet() - sent.toSet()
        assertTrue("app_logs: server requires columns the uploader never sends: $notSent", notSent.isEmpty())
    }

    private companion object {
        /** Pushed but never pulled back (the phone's own log). */
        val UPLOAD_ONLY = setOf("app_logs")
    }
}
