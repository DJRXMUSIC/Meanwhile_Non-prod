package app.meanwhile.profile

import app.meanwhile.data.ai.ChangeDecision
import app.meanwhile.data.ai.ProposalReview
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.domain.profile.DoseSettings
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileChange
import app.meanwhile.testing.TestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileAndReviewTest {
    private val env = TestEnv()
    private val review = ProposalReview(env.db, env.records, env.profiles, env.onWrite) { v, applied -> env.learning.trackReviewed(v, applied) }
    @After fun tearDown() = env.close()

    @Test
    fun `current profile follows each new version (cache never goes stale)`() = runBlocking {
        assertEquals(10.0, env.profiles.current().profile.dose.icr, 0.0)
        env.profiles.saveVersion(Profile(dose = DoseSettings(icr = 9.0)), ProfileSource.MANUAL, ProfileStatus.ACCEPTED, "a")
        assertEquals(9.0, env.profiles.current().profile.dose.icr, 0.0)
        val v2 = env.profiles.saveVersion(Profile(dose = DoseSettings(icr = 8.0)), ProfileSource.MANUAL, ProfileStatus.ACCEPTED, "b")
        assertEquals(2, v2.version)
        assertEquals(8.0, env.profiles.current().profile.dose.icr, 0.0)
        // A pending proposal never becomes the profile by itself.
        env.profiles.saveVersion(Profile(dose = DoseSettings(icr = 5.0)), ProfileSource.LEARN_CYCLE, ProfileStatus.PENDING, "c")
        assertEquals(8.0, env.profiles.current().profile.dose.icr, 0.0)
    }

    @Test
    fun `accepting a learned proposal applies it and starts watching it`() = runBlocking {
        val change = ProfileChange("dose.icr", JsonPrimitive(10.0), JsonPrimitive(9.0), reason = "evidence")
        val pending = env.profiles.saveVersion(Profile(dose = DoseSettings(icr = 9.0)), ProfileSource.LEARN_CYCLE, ProfileStatus.PENDING, "p", changes = listOf(change))
        val saved = review.decide(pending, listOf(ChangeDecision(change, "accepted"))).getOrThrow()
        assertEquals(ProfileStatus.ACCEPTED, saved.status)
        assertEquals(9.0, env.profiles.current().profile.dose.icr, 0.0)
        val watched = env.db.learningLog().openChanges().single()
        assertTrue(watched.summary, watched.summary.startsWith("ICR 10 → 9"))
    }

    @Test
    fun `a decision that would break the dose math is refused`() = runBlocking {
        val change = ProfileChange("dose.isf", JsonPrimitive(25.0), JsonPrimitive(0.0))
        val pending = env.profiles.saveVersion(Profile(), ProfileSource.LEARN_CYCLE, ProfileStatus.PENDING, "p", changes = listOf(change))
        val result = review.decide(pending, listOf(ChangeDecision(change, "accepted")))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("dose.isf"))
        assertEquals(25.0, env.profiles.current().profile.dose.isf, 0.0)
    }

    @Test
    fun `rejecting changes nothing and records the decision`() = runBlocking {
        val change = ProfileChange("dose.icr", JsonPrimitive(10.0), JsonPrimitive(9.0))
        val pending = env.profiles.saveVersion(Profile(), ProfileSource.AUTO_TUNE, ProfileStatus.PENDING, "p", changes = listOf(change))
        val saved = review.decide(pending, listOf(ChangeDecision(change, "rejected"))).getOrThrow()
        assertEquals(ProfileStatus.REJECTED, saved.status)
        assertEquals(10.0, env.profiles.current().profile.dose.icr, 0.0)
        assertTrue(env.db.profileVersions().pendingUndecided().isEmpty())
        assertTrue(env.db.learningLog().openChanges().isEmpty())
    }
}
