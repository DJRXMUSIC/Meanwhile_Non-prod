package app.meanwhile.advice

import app.meanwhile.data.input.MealDraft
import app.meanwhile.domain.nba.ActionKind
import app.meanwhile.domain.nba.AlertKind
import app.meanwhile.testing.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/** 2.0: the forecast in the live dose math, and suggestions the app sends on its own. */
@RunWith(RobolectricTestRunner::class)
class AdviceAndForecastTest {
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    private val noon: Instant get() = env.day(1).plus(Duration.ofHours(12))
    private fun at(min: Long): Instant = noon.plus(Duration.ofMinutes(min))

    @Test
    fun `a low is sent once, logged in the conversation, and not repeated for 30 min`() = runBlocking {
        env.readings(at(-30), 30) { 60 }
        val alert = env.advice.check(at(0))!!
        assertEquals(AlertKind.LOW, alert.kind)
        assertEquals(ActionKind.TREAT_LOW, alert.action.kind)
        assertEquals(listOf(alert), env.posted)
        val row = env.db.conversation().since(0).single { it.kind == "notification" }
        assertTrue(row.text, row.text.startsWith("Eat 16 g fast carbs — BG 60 →"))
        assertTrue(row.details, row.details.contains("\"alert\":\"LOW\""))

        env.readings(at(5), 0) { 58 }
        assertNull("repeat within 30 min", env.advice.check(at(5)))
        env.readings(at(35), 0) { 58 }
        assertEquals(AlertKind.LOW, env.advice.check(at(35))?.kind)
        assertEquals(2, env.posted.size)
    }

    @Test
    fun `a steady high gets a correction once the last dose has had time`() = runBlocking {
        env.readings(at(-30), 30) { 250 }
        val alert = env.advice.check(at(0))!!
        assertEquals(AlertKind.CORRECTION, alert.kind)
        assertEquals("BG 250 → — take 6 u", alert.title)
    }

    @Test
    fun `nothing to say in range`() = runBlocking {
        env.readings(at(-30), 30) { 115 }
        assertNull(env.advice.check(at(0)))
        assertTrue(env.posted.isEmpty())
    }

    @Test
    fun `a logged meal's carbs still absorbing offset the insulin given for it`() = runBlocking {
        env.readings(at(-30), 30) { 120 }
        val card = env.nba.propose(MealDraft("pasta", 60.0), null, at(0), bgOverride = 120.0)
        env.nba.logProposal(card, card.result.finalUnits, 0, null, now = at(0))
        env.readings(at(5), 25) { m -> 120 + m }   // rising with the meal, as it should
        val state = env.nba.preview(at(30))
        assertTrue("carbs on board ${state.context.forecast.cobUnits}", state.context.forecast.cobUnits > 4.0)
        assertTrue("insulin on board ${state.context.iob}", state.context.iob > 5.0)
        // Insulin on board is for the pasta: no "eat carbs", no low alert.
        assertTrue(state.action.sentence, state.action.kind != ActionKind.EAT_CARBS && state.action.kind != ActionKind.TREAT_LOW)
        assertNull(env.advice.check(at(30)))
    }

    @Test
    fun `a rise nothing logged explains raises the dose and says why`() = runBlocking {
        env.readings(at(-30), 30) { m -> 150 + 2 * m }   // 150 → 210, +2/min, nothing logged
        val state = env.nba.preview(at(0))
        val f = state.context.forecast
        assertEquals(2.0, f.unexplainedRate!!, 0.01)
        assertEquals(120.0 / 25, f.unexplainedUnits, 0.05)
        assertEquals(f.unexplainedUnits, state.result.unexplainedUnits, 0.0)
        assertTrue(state.action.why.toString(), state.action.why.any { it.startsWith("Rising faster than logged insulin and food explain") })
    }
}
