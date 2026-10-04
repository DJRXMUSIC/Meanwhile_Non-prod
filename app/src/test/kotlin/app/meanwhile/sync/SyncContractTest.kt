package app.meanwhile.sync

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
        Regex("create table public\\.(\\w+) \\((.*?)\\n\\);", RegexOption.DOT_MATCHES_ALL).findAll(sql).associate { m ->
            m.groupValues[1] to m.groupValues[2].lines().map { it.trim().trimEnd(',') }
                .filter { it.isNotEmpty() && !it.startsWith("--") }
                .map { line ->
                    val required = "not null" in line && "default" !in line && "generated" !in line && "primary key" !in line
                    Column(line.substringBefore(' '), required)
                }
        }
    }

    @Test
    fun `every synced table exists on the server with matching columns`() {
        val tables = syncTables(env.db)
        assertEquals("server tables vs synced tables", serverTables.keys, tables.map { it.name }.toSet())
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
    fun `learning journal is synced`() {
        assertTrue(syncTables(env.db).any { it.name == "learning_log" })
    }
}
