package app.meanwhile.ai

import app.meanwhile.data.ai.AiHooksImpl
import app.meanwhile.data.input.MealDraft
import app.meanwhile.data.input.toJson
import app.meanwhile.domain.profile.Profile
import app.meanwhile.testing.TestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/** What the AI is told when Danny talks to the app (converse job): live state, history, his words. */
@RunWith(RobolectricTestRunner::class)
class ConversePayloadTest {
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    private val hooks = AiHooksImpl(env.ai, env.db) { TestEnv.ZONE }
    private val noon: Instant get() = env.day(1).plus(Duration.ofHours(12))

    private suspend fun payload(text: String, done: List<String> = emptyList()): JsonObject {
        val state = env.nba.preview(noon.plus(Duration.ofMinutes(30))).toJson()
        return hooks.conversePayload(text, Profile(), state, done, env.conversation.transcriptSince(0), now = noon.plus(Duration.ofMinutes(30)))
    }

    @Test
    fun `the AI gets the live state, the last two days and what was said`() = runBlocking {
        env.readings(noon.minus(Duration.ofMinutes(30)), 60) { 140 }
        val card = env.nba.propose(MealDraft("pasta", 60.0), null, noon, bgOverride = 140.0)
        env.nba.logProposal(card, card.result.finalUnits, 0, null, now = noon)
        env.conversation.message("0190a000-0000-7000-8000-000000000001", "had pasta", "voice", null, noon)

        val p = payload("how am I doing?")
        assertEquals("how am I doing?", p["text"]!!.jsonPrimitive.content)
        val state = p["state"]!!.jsonObject
        assertEquals(140.0, state["bg"]!!.jsonPrimitive.double, 0.0)
        assertTrue(state.toString(), state["iob_u"]!!.jsonPrimitive.double > 6.0)
        assertTrue("forecast is there: $state", state["forecast"]!!.jsonObject["cobUnits"]!!.jsonPrimitive.double > 4.0)
        assertTrue(state.containsKey("current_action"))
        val recent = p["recent"]!!.jsonObject
        assertEquals("pasta", recent["meals"]!!.jsonArray.single().jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals(1, recent["doses"]!!.jsonArray.size)
        assertEquals("had pasta", p["recent_conversation"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue("the phone's own reading is included", p.containsKey("offline_guess"))
        assertTrue(p.containsKey("profile"))
    }

    @Test
    fun `after the app already acted, it says what it did and asks for words only`() = runBlocking {
        val p = payload("took 6 units", done = listOf("Logged 6 u rapid at 12:00 PM"))
        assertEquals("Logged 6 u rapid at 12:00 PM", p["done"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse(p.containsKey("offline_guess"))
    }

    @Test
    fun `without a network nothing is sent`() = runBlocking {
        assertNull(hooks.converse("hi", Profile(), "0190a000-0000-7000-8000-000000000002", JsonObject(emptyMap()), emptyList(), emptyList()))
    }
}
