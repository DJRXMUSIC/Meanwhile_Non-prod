package app.meanwhile.db

import app.meanwhile.data.db.CgmReadingEntity
import app.meanwhile.data.db.FeedbackEntity
import app.meanwhile.data.db.LearningLogEntity
import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.testing.TestEnv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The hand-written queries the app's correctness depends on. */
@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    private fun version(id: String, v: Int, status: String, supersedes: String? = null, aiCall: String? = null, at: Long = v * 1000L) =
        ProfileVersionEntity(
            id = id, createdAt = at, recordedAt = at, supersedesId = supersedes, version = v, source = "manual", status = status,
            profile = "{}", diff = "[]", aiCallId = aiCall,
        )

    @Test
    fun `current profile is the newest applied version`() = runBlocking {
        val d = env.db.profileVersions()
        assertNull(d.currentApplied())
        d.insertAll(listOf(version("a", 1, "accepted"), version("b", 2, "edited"), version("c", 3, "pending"), version("d", 4, "rejected")))
        assertEquals("b", d.currentApplied()!!.id)
        assertEquals("b", d.currentAppliedFlow().first()!!.id)
    }

    @Test
    fun `pending proposals disappear once decided`() = runBlocking {
        val d = env.db.profileVersions()
        d.insertAll(listOf(version("p1", 1, "pending"), version("p2", 2, "pending")))
        assertEquals(setOf("p1", "p2"), d.pendingUndecided().map { it.id }.toSet())
        d.insert(version("dec", 3, "accepted", supersedes = "p1"))
        assertEquals(listOf("p2"), d.pendingUndecided().map { it.id })
        assertEquals(listOf("p2"), d.pendingUndecidedFlow().first().map { it.id })
        assertEquals("dec", d.supersededBy("p1")!!.id)
        d.insert(version("ai", 4, "rejected", aiCall = "call-1", at = 9000))
        assertEquals(listOf("ai"), d.aiLinkedSince(5000).map { it.id })
    }

    @Test
    fun `journal open changes close when superseded`() = runBlocking {
        val l = env.db.learningLog()
        fun entry(id: String, kind: String, at: Long, supersedes: String? = null) =
            LearningLogEntity(id = id, createdAt = at, recordedAt = at, supersedesId = supersedes, kind = kind, summary = id)
        l.insertAll(listOf(entry("a1", "applied", 1), entry("a2", "applied", 2), entry("x", "lessons", 3)))
        assertEquals(listOf("a1", "a2"), l.openChanges().map { it.id })
        l.insert(entry("k", "kept", 4, supersedes = "a1"))
        assertEquals(listOf("a2"), l.openChanges().map { it.id })
        assertEquals("k", l.latest("kept")!!.id)
    }

    @Test
    fun `duplicates are ignored, never overwritten`() = runBlocking {
        val c = env.db.cgm()
        val r = CgmReadingEntity(id = "r1", createdAt = 1, recordedAt = 100, mgDl = 120, source = "a")
        c.insertAll(listOf(r))
        // Same id → ignored; same timestamp with a new id → ignored by the unique index.
        val results = c.insertAll(listOf(r.copy(mgDl = 999), r.copy(id = "r2", mgDl = 999)))
        assertEquals(listOf(-1L, -1L), results)
        assertEquals(120, c.latest()!!.mgDl)
    }

    @Test
    fun `sync counters include every synced table`() = runBlocking {
        assertEquals(0, env.db.sync().pendingCount().first())
        env.db.feedback().insert(FeedbackEntity(id = "f", createdAt = 1, recordedAt = 1, text = "x"))
        env.db.learningLog().insert(LearningLogEntity(id = "l", createdAt = 1, recordedAt = 1, kind = "lessons", summary = "s"))
        assertEquals(2, env.db.sync().pendingCount().first())
        env.db.learningLog().markFailed(listOf("l"))
        assertEquals(1, env.db.sync().pendingCount().first())
        assertEquals(1, env.db.sync().rejectedCount().first())
    }
}
