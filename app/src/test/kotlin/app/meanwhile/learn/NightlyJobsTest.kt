package app.meanwhile.learn

import app.meanwhile.testing.TestEnv
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class NightlyJobsTest {
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    @Test
    fun `outcomes are tagged once per dose even when callers race`() = runBlocking {
        val noon = env.day(3).plus(Duration.ofHours(12))
        env.meal(noon, end = 140, min = 95)
        val later = noon.plus(Duration.ofHours(5))
        // The CGM service, the watchdog and app start can all tag at the same moment.
        (1..4).map { async { env.nightly.tagOutcomes(later) } }.awaitAll()
        env.nightly.tagOutcomes(later)
        val outcomes = env.db.outcomes().since(0)
        assertEquals(1, outcomes.size)
        assertEquals(140, outcomes.single().bg4h)
        assertEquals(95, outcomes.single().min4h)
    }

    @Test
    fun `nothing is tagged before 4 h have passed`() = runBlocking {
        val noon = env.day(3).plus(Duration.ofHours(12))
        env.meal(noon, end = 140)
        env.nightly.tagOutcomes(noon.plus(Duration.ofHours(3)))
        assertTrue(env.db.outcomes().since(0).isEmpty())
    }
}
