package app.meanwhile.dose

import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.data.input.DoseUnavailableException
import app.meanwhile.data.input.MealDraft
import app.meanwhile.domain.profile.DoseSettings
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileJson
import app.meanwhile.testing.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

/** Next Best Action and dose logging on the real data layer. */
@RunWith(RobolectricTestRunner::class)
class NbaServiceTest {
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    private val noon get() = env.day(3).plus(Duration.ofHours(12))

    @Test
    fun `proposal is stored with its full math and logging records dose and meal`() = runBlocking {
        val card = env.nba.propose(MealDraft("pasta", 60.0), null, noon, bgOverride = 150.0)
        assertEquals(8, card.result.finalUnits) // 6 carbs + 2 correction
        val stored = env.db.proposals().byId(card.proposalId)!!
        assertEquals(8, stored.finalUnits)
        assertTrue(stored.breakdown.contains("\"carbDose\""))

        env.nba.logFromProposal(card, 8, 0, null, now = noon)
        val dose = env.db.doses().forProposal(card.proposalId).single()
        assertEquals(8.0, dose.units, 0.0)
        assertEquals(8.0, dose.proposedUnits!!, 0.0)
        assertEquals(1, env.db.meals().between(0, Long.MAX_VALUE).size)
    }

    @Test
    fun `split second injection is logged exactly once`() = runBlocking {
        val card = env.nba.propose(MealDraft("pizza", 60.0, fatG = 45.0, proteinG = 30.0), null, noon, bgOverride = 100.0)
        val split = card.result.split!!
        env.nba.logFromProposal(card, split.firstUnits, split.secondUnits, null, now = noon)
        assertEquals(1, env.scheduled.size)
        assertTrue(env.nba.logSecond(card.proposalId, split.secondUnits))
        // A second tap / the notification after the in-app button: ignored.
        assertFalse(env.nba.logSecond(card.proposalId, split.secondUnits))
        assertEquals(2, env.db.doses().forProposal(card.proposalId).size)
        assertTrue(env.nba.pendingSeconds(noon.plus(Duration.ofHours(2))).isEmpty())
    }

    @Test
    fun `an invalid profile gives no dose instead of a silent 0`() = runBlocking {
        val broken = Profile(dose = DoseSettings(icr = 0.0))
        env.db.profileVersions().insert(
            ProfileVersionEntity(id = "v1", createdAt = 1, recordedAt = 1, version = 1, source = "manual", status = "accepted", profile = ProfileJson.encode(broken)),
        )
        try {
            env.nba.propose(MealDraft("toast", 30.0), null, noon, bgOverride = 100.0)
            fail("expected DoseUnavailableException")
        } catch (e: DoseUnavailableException) {
            assertTrue(e.message!!, e.message!!.contains("dose.icr"))
        }
        assertTrue(env.db.proposals().between(0, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `manual dose log`() = runBlocking {
        val msg = env.nba.logDose(22.0, "long", noon.toEpochMilli(), null)
        assertTrue(msg, msg.startsWith("Logged 22"))
        assertEquals("long", env.db.doses().between(0, Long.MAX_VALUE).single().insulin)
    }
}
