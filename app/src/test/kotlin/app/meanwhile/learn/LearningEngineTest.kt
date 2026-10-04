package app.meanwhile.learn

import app.meanwhile.data.db.MealEntity
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.data.profile.changes
import app.meanwhile.data.settings.LearningAutonomy
import app.meanwhile.testing.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/**
 * Continuous learning end to end on the real data layer: proposals → logged doses → CGM → outcome
 * tagging → lessons → local tuning → applied (or proposed) → judged → kept / reverted / undone.
 * Default profile: ICR 10, ISF 25, target 100; 60 g at BG 100 → 6 u.
 */
@RunWith(RobolectricTestRunner::class)
class LearningEngineTest {
    private val env = TestEnv()

    @After fun tearDown() = env.close()

    private fun noon(daysAgo: Long): Instant = env.day(daysAgo).plus(Duration.ofHours(12))
    private fun evening(daysAgo: Long): Instant = env.day(daysAgo).plus(Duration.ofHours(18))

    /** Three lunches that each ended at [end]: the outcomes say ICR 10 is too weak (7.5 g/u). */
    private suspend fun underDosedLunches(fromDaysAgo: Long, end: Int = 150) {
        for (d in fromDaysAgo downTo fromDaysAgo - 2) env.meal(noon(d), end = end)
    }

    private suspend fun icr() = env.profiles.current().profile.dose.icr

    @Test
    fun `automatic - outcomes tune ICR, the change is documented and applied`() = runBlocking {
        underDosedLunches(fromDaysAgo = 10)
        env.housekeeping(evening(8))

        assertEquals(8.8, icr(), 1e-9) // 10 + 0.5 × (7.5 − 10)
        val v = env.profiles.current().version!!
        assertEquals(ProfileSource.AUTO_TUNE, v.source)
        assertEquals(ProfileStatus.ACCEPTED, v.status)
        assertEquals("auto", v.changes().single().decision)

        val journal = env.db.learningLog().since(0)
        assertTrue(journal.any { it.kind == "lessons" && it.summary.startsWith("3 new lessons") })
        val applied = journal.single { it.kind == "applied" }
        assertTrue(applied.summary, applied.summary.startsWith("ICR 10 → 8.8"))
        assertEquals(v.id, applied.profileVersionId)
        assertEquals(listOf(applied.id), env.db.learningLog().openChanges().map { it.id })
    }

    @Test
    fun `ask me first - learned change waits for review and is never stacked`() = runBlocking {
        env.settings.update { it.copy(learningAutonomy = LearningAutonomy.ASK) }
        underDosedLunches(fromDaysAgo = 10)
        env.housekeeping(evening(8))

        assertEquals(10.0, icr(), 1e-9)
        val pending = env.db.profileVersions().pendingUndecided().single()
        assertEquals(ProfileSource.AUTO_TUNE, pending.source)
        assertTrue(env.db.learningLog().since(0).any { it.kind == "proposed" })

        // Neither the next pass nor "Learn now" adds a second proposal for the same value.
        env.housekeeping(evening(8).plusSeconds(900))
        env.learning.runNow(evening(8).plusSeconds(1800))
        assertEquals(1, env.db.profileVersions().pendingUndecided().size)
    }

    @Test
    fun `automatic for factors - ICR is core so it waits`() = runBlocking {
        env.settings.update { it.copy(learningAutonomy = LearningAutonomy.AUTO_FACTORS) }
        underDosedLunches(fromDaysAgo = 10)
        env.housekeeping(evening(8))
        assertEquals(10.0, icr(), 1e-9)
        assertEquals(1, env.db.profileVersions().pendingUndecided().size)
    }

    @Test
    fun `no lessons, no change - and the same evidence is never used twice`() = runBlocking {
        underDosedLunches(fromDaysAgo = 10)
        env.housekeeping(evening(8))
        assertEquals(8.8, icr(), 1e-9)
        // Nothing new: another pass (and an explicit Learn now) leaves ICR alone.
        env.housekeeping(evening(8).plusSeconds(900))
        env.learning.runNow(evening(8).plusSeconds(1800))
        assertEquals(8.8, icr(), 1e-9)
    }

    @Test
    fun `a change that made things worse is reverted automatically`() = runBlocking {
        underDosedLunches(fromDaysAgo = 10)          // miss 50 each
        env.housekeeping(evening(8))
        assertEquals(8.8, icr(), 1e-9)

        underDosedLunches(fromDaysAgo = 7, end = 230) // miss 130 each after the change
        env.housekeeping(evening(5))

        assertEquals(10.0, icr(), 1e-9)
        val v = env.profiles.current().version!!
        assertEquals(ProfileSource.AUTO_REVERT, v.source)
        val reverted = env.db.learningLog().since(0).single { it.kind == "reverted" }
        assertTrue(reverted.summary, reverted.summary.contains("worse"))
        assertTrue(env.db.learningLog().openChanges().isEmpty())
        // After a revert the tuner waits for fresh evidence instead of re-applying at once.
        assertEquals(1, env.db.learningLog().since(0).count { it.kind == "applied" })
    }

    @Test
    fun `a change that helped is kept`() = runBlocking {
        underDosedLunches(fromDaysAgo = 10)          // miss 50
        env.housekeeping(evening(8))
        underDosedLunches(fromDaysAgo = 7, end = 115) // miss 15
        env.housekeeping(evening(5))
        val kept = env.db.learningLog().since(0).single { it.kind == "kept" }
        assertTrue(kept.summary, kept.summary.startsWith("Kept ICR"))
    }

    @Test
    fun `a severe low after a more-insulin change reverts it at once`() = runBlocking {
        underDosedLunches(fromDaysAgo = 10)
        env.housekeeping(evening(8))
        assertEquals(8.8, icr(), 1e-9)
        env.meal(noon(7), end = 110, min = 48)
        env.housekeeping(evening(7))
        assertEquals(10.0, icr(), 1e-9)
        assertTrue(env.db.learningLog().since(0).single { it.kind == "reverted" }.summary.contains("48"))
    }

    @Test
    fun `undo puts the old value back and closes the change`() = runBlocking {
        underDosedLunches(fromDaysAgo = 10)
        env.housekeeping(evening(8))
        val applied = env.db.learningLog().openChanges().single()
        val msg = env.learning.undo(applied.id)
        assertTrue(msg, msg.startsWith("Undone"))
        assertEquals(10.0, icr(), 1e-9)
        assertEquals(ProfileSource.MANUAL, env.profiles.current().version!!.source)
        assertTrue(env.db.learningLog().openChanges().isEmpty())
        assertEquals("Already closed", env.learning.undo(applied.id))
    }

    @Test
    fun `status explains the evidence`() = runBlocking {
        underDosedLunches(fromDaysAgo = 10)
        env.nightly.tagOutcomes(evening(8))
        val st = env.learning.status(evening(8))
        assertEquals(3, st.lessons.count { it.clean })
        val icrLine = st.evidence.single { it.path == "dose.icr" }
        assertEquals(7.5, icrLine.implied!!, 1e-6)
        assertEquals(3, icrLine.lessons)
        assertNull(st.lastAiReviewAt)
    }

    @Test
    fun `confounded meals teach nothing`() = runBlocking {
        // Each lunch is followed 90 min later by food logged without a dose (e.g. "had a cookie"
        // routed as a meal and dismissed): the outcome can't be pinned on the lunch dose.
        for (d in 10L downTo 8L) {
            env.meal(noon(d), end = 150)
            val m = env.records.meta(recordedAt = noon(d).plus(Duration.ofMinutes(90)).toEpochMilli())
            env.db.meals().insert(
                MealEntity(
                    id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = m.recordedAt,
                    carbsG = 25.0, fatG = 5.0, proteinG = 2.0, description = "cookie",
                ),
            )
        }
        env.housekeeping(evening(7))
        assertEquals(10.0, icr(), 1e-9)
        val lessons = env.learning.lessons(evening(7))
        assertEquals(3, lessons.size)
        assertTrue(lessons.all { !it.clean && it.excludedBecause == "another meal within 4 h" })
        assertNotNull(env.db.learningLog().since(0).firstOrNull { it.kind == "lessons" && it.summary.contains("3 confounded") })
    }
}
