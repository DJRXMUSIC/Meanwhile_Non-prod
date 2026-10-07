package app.meanwhile.dose

import app.meanwhile.data.db.ConversationLogEntity
import app.meanwhile.data.input.AiHooks
import app.meanwhile.data.input.ConverseResult
import app.meanwhile.data.input.DoseLoggedCard
import app.meanwhile.data.input.InfoCard
import app.meanwhile.data.input.InputSession
import app.meanwhile.data.input.MealLoggedCard
import app.meanwhile.data.input.MealMacrosCard
import app.meanwhile.data.input.NbaCard
import app.meanwhile.data.input.ReplyCard
import app.meanwhile.data.input.Step
import app.meanwhile.data.input.StepState
import app.meanwhile.domain.nba.ActionKind
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.router.DoseIntent
import app.meanwhile.domain.router.RouteResult
import app.meanwhile.testing.TestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** Talking to the app (1.4): what Danny says is logged, corrected and answered — offline, deterministic. */
@RunWith(RobolectricTestRunner::class)
class ConversationTest {
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    private val noon: Instant get() = env.day(1).plus(Duration.ofHours(12))
    private fun at(min: Long): Instant = noon.plus(Duration.ofMinutes(min))

    private suspend fun say(text: String, min: Long, steps: MutableList<Step>? = null): InputSession =
        env.inputs.process(text, "voice", now = at(min), onStep = { steps?.add(it) })

    private suspend fun effective() = env.db.doses().effectiveSince(0)

    @Test
    fun `saying a dose logs it, and a correction replaces it`() = runBlocking {
        val first = say("took 6 units", 0).cards.single() as DoseLoggedCard
        assertEquals(6.0, first.dose.units, 0.0)
        assertTrue(first.message, first.message.startsWith("Logged 6 u rapid"))

        val fixed = say("never mind I only took 5", 2).cards.single() as DoseLoggedCard
        assertEquals(5.0, fixed.dose.units, 0.0)
        assertEquals(first.dose.id, fixed.previous!!.id)
        assertEquals(first.dose.id, fixed.dose.supersedesId)
        assertEquals("the original time is kept", first.dose.givenAt, fixed.dose.givenAt)
        assertEquals(listOf(5.0), effective().map { it.units })
        assertEquals("both rows stay (append-only)", 2, env.db.doses().between(0, Long.MAX_VALUE).size)

        val iob = env.doseContext.build(at(3)).iob
        assertTrue("IOB counts 5 u, not 11 ($iob)", iob in 4.0..5.0)

        val cancelled = say("actually I didn't take any", 4).cards.single() as DoseLoggedCard
        assertEquals(0.0, cancelled.dose.units, 0.0)
        assertTrue(cancelled.message, cancelled.message.startsWith("Removed the 5 u rapid dose"))
        assertEquals(0.0, env.doseContext.build(at(5)).iob, 0.0)

        val again = say("wait, I did take 5", 6).cards.single() as DoseLoggedCard
        assertEquals(5.0, again.dose.units, 0.0)
        assertEquals(listOf(5.0), effective().map { it.units })
    }

    @Test
    fun `a correction never reaches back further than two hours`() = runBlocking {
        say("took 6 units", 0)
        val late = say("never mind only 5", 150).cards.single()
        assertTrue(late is InfoCard)
        assertEquals(listOf(6.0), effective().map { it.units })
    }

    @Test
    fun `cancel with nothing logged says so`() = runBlocking {
        val card = say("cancel that", 0).cards.single() as InfoCard
        assertTrue(card.message, card.message.startsWith("Nothing to cancel"))
    }

    @Test
    fun `took it logs the suggestion with its meal`() = runBlocking {
        val nba = say("BG 150 and 60 carbs", 0).cards.single() as NbaCard
        assertEquals(ActionKind.TAKE_INSULIN, nba.action.kind)
        assertEquals("Take 8 u", nba.action.headline)
        assertEquals(150.0, nba.input.bg!!, 0.0)

        val logged = say("took it", 1).cards.single() as DoseLoggedCard
        assertEquals(8.0, logged.dose.units, 0.0)
        assertEquals(nba.proposalId, logged.dose.proposalId)
        assertEquals(1, env.db.meals().between(0, Long.MAX_VALUE).size)

        // Nothing open any more.
        assertTrue(say("done", 2).cards.single() is InfoCard)
    }

    @Test
    fun `a stated dose right after a suggestion is linked to it`() = runBlocking {
        val nba = say("BG 150 and 60 carbs", 0).cards.single() as NbaCard
        val logged = say("took 7 units", 1).cards.single() as DoseLoggedCard
        assertEquals(nba.proposalId, logged.dose.proposalId)
        assertEquals(8.0, logged.dose.proposedUnits!!, 0.0)
        assertNotNull("an override says why", logged.dose.overrideReason)
    }

    @Test
    fun `a low gets carbs, and ate it logs carbs with no insulin`() = runBlocking {
        val nba = say("BG 60", 0).cards.single() as NbaCard
        assertEquals(ActionKind.TREAT_LOW, nba.action.kind)
        assertEquals("Eat 16 g fast carbs", nba.action.headline)
        val logged = say("ate it", 1).cards.single() as MealLoggedCard
        assertTrue(logged.message, logged.message.startsWith("Logged 16 g carbs · no insulin"))
        val meal = env.db.meals().between(0, Long.MAX_VALUE).single()
        assertEquals(16.0, meal.carbsG, 0.0)
        assertTrue(effective().isEmpty())
    }

    @Test
    fun `asking what to do gives a Next Best Action with no food`() = runBlocking {
        val nba = say("bg 250 what should I do", 0).cards.single() as NbaCard
        assertEquals("Take 6 u", nba.action.headline)
        assertEquals("correction — no food needed", nba.action.detail)
    }

    @Test
    fun `every step is reported and the conversation is logged word for word`() = runBlocking {
        val steps = mutableListOf<Step>()
        val session = say("took 6 units", 0, steps)
        // Read by code with certainty: understood at once, then the dose is logged.
        assertEquals(listOf("understand", "log", "log"), steps.map { it.id })
        assertEquals(listOf(StepState.DONE, StepState.RUNNING, StepState.DONE), steps.map { it.state })

        val rows = env.db.conversation().forInput(session.inputId)
        assertEquals(listOf("message", "step", "step", "reply"), rows.map { it.kind })
        assertEquals("took 6 units", rows.first().text)
        assertTrue(rows.first().details, rows.first().details.contains("\"via\":\"voice\""))
        assertTrue(rows.last().text, rows.last().text.startsWith("Logged 6 u rapid"))
        assertTrue(rows.last().details, rows.last().details.contains("DoseLoggedCard"))
        assertEquals(rows.sortedBy { it.recordedAt }, rows)
    }

    @Test
    fun `cancelling the first part of a split cancels its reminder`() = runBlocking {
        val nba = say("BG 100 and 60 carbs 45 fat 30 protein", 0).cards.single() as NbaCard
        assertEquals(ActionKind.SPLIT_INSULIN, nba.action.kind)
        say("took it", 1)
        assertEquals(1, env.scheduled.size)
        assertEquals(1, env.nba.pendingSeconds(at(2)).size)
        say("I didn't take it", 3)
        assertEquals(listOf(nba.proposalId), env.cancelled)
        assertTrue(env.nba.pendingSeconds(at(4)).isEmpty())
    }

    @Test
    fun `the open suggestion survives a restart`() = runBlocking {
        val nba = say("BG 150 and 60 carbs", 0).cards.single() as NbaCard
        val rebuilt = env.nba.openProposal(at(5))!!
        assertEquals(nba.proposalId, rebuilt.proposalId)
        assertEquals(nba.action, rebuilt.action)
        assertNull(env.nba.openProposal(at(120)))
    }

    /** A stand-in AI that answers in words and records what it was asked. */
    private class TalkingAi(private val words: String, private val route: RouteResult? = RouteResult(emptyList(), "ai")) : AiHooks {
        val asked = mutableListOf<String>()
        val done = mutableListOf<List<String>>()
        var state: JsonObject? = null
        override val online: Boolean = true
        override suspend fun converse(
            text: String, profile: Profile, inputId: String, state: JsonObject, done: List<String>, history: List<ConversationLogEntity>,
        ): ConverseResult {
            asked += text
            this.done += done
            this.state = state
            return ConverseResult(words, if (done.isEmpty()) route else null, "test-model")
        }
    }

    @Test
    fun `certain messages skip the AI, food in words goes to it as conversation`() = runBlocking {
        val ai = TalkingAi("Sounds good.", route = null)
        env.aiHooks = ai
        say("took 6 units", 0)
        say("BG 140 and 60 carbs", 1)
        say("what should I do", 2)
        assertTrue("no AI round trip before acting: ${ai.asked}", ai.asked.isEmpty())
        say("ate a turkey sandwich", 3)
        assertEquals(listOf("ate a turkey sandwich"), ai.asked)
    }

    @Test
    fun `chat gets words back, not a carbs form`() = runBlocking {
        env.aiHooks = TalkingAi("All working — BG is steady, nothing to do.")
        val session = say("just testing out the new features", 0)
        val reply = session.cards.single() as ReplyCard
        assertEquals("All working — BG is steady, nothing to do.", reply.text)
        assertEquals("test-model", reply.model)
        assertTrue(session.cards.none { it is MealMacrosCard || it is NbaCard })
        assertTrue("already answered", !session.needsReply)
        val rows = env.db.conversation().forInput(session.inputId)
        assertEquals("All working — BG is steady, nothing to do.", rows.last().text)
    }

    @Test
    fun `offline, words the phone can't read are not treated as a meal`() = runBlocking {
        val card = say("just testing out the new features", 0).cards.single() as InfoCard
        assertTrue(card.message, card.message.startsWith("I didn't catch"))
        // A meal cue still asks for the macros.
        assertTrue(say("ate a turkey sandwich", 1).cards.single() is MealMacrosCard)
    }

    @Test
    fun `the AI can say what to act on, and code reads the numbers`() = runBlocking {
        env.aiHooks = TalkingAi(
            "Logged — that'll keep working for a few hours.",
            RouteResult(listOf(DoseIntent("took 4 units", 4.0)), "ai"),
        )
        val session = say("did my usual for the pizza earlier", 0)
        assertTrue(session.cards.first() is ReplyCard)
        val logged = session.cards.filterIsInstance<DoseLoggedCard>().single()
        assertEquals(4.0, logged.dose.units, 0.0)
    }

    @Test
    fun `a message logged instantly gets the AI's words after, with the live state`() = runBlocking {
        val ai = TalkingAi("Got it — 6 u logged.")
        env.aiHooks = ai
        val session = say("took 6 units", 0)
        assertTrue(session.needsReply)
        assertTrue(session.cards.single() is DoseLoggedCard)
        assertTrue("nothing asked before logging", ai.asked.isEmpty())

        val reply = env.inputs.reply(session, now = at(0))!!
        assertEquals("Got it — 6 u logged.", reply.text)
        assertTrue(ai.done.single().toString(), ai.done.single().single().startsWith("Logged 6 u rapid"))
        val state = ai.state!!
        assertTrue(state.toString(), state["iob_u"]!!.jsonPrimitive.double > 5.0)
        assertTrue(state.toString(), state.containsKey("current_action"))
        assertEquals("Got it — 6 u logged.", env.db.conversation().forInput(session.inputId).last().text)
    }

    @Test
    fun `no reply is asked for when the AI is off`() = runBlocking {
        val session = say("took 6 units", 0)
        assertNull(env.inputs.reply(session, now = at(0)))
    }

    @Test
    fun `everything said reaches the nightly review word for word`() = runBlocking {
        env.aiHooks = TalkingAi("Have a good run — tell me when you're back.")
        // Real time: the log stamps replies with the clock, and the review window ends now.
        env.inputs.process("going for a run in an hour", "voice")
        env.inputs.process("took 6 units", "voice")
        val end = Instant.now().plusSeconds(60)
        val payload = app.meanwhile.data.learn.LearnPayload(env.db).build(Profile(), end, java.time.ZoneOffset.UTC, end)
        val talk = payload["last_24h"]!!.jsonObject["conversation"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertTrue(talk.toString(), "going for a run in an hour" in talk)
        assertTrue(talk.toString(), "Have a good run — tell me when you're back." in talk)
        assertTrue(talk.toString(), "took 6 units" in talk)
    }

    @Test
    fun `a coffee adds units to the next meal instead of asking for a dose now`() = runBlocking {
        val cards = say("had a coffee", 0).cards
        assertTrue(cards.toString(), cards.none { it is NbaCard })
        val update = cards.single() as app.meanwhile.data.input.FactorUpdateCard
        assertEquals("added to your next dose", update.changes.single().window)
        // The next meal's dose carries the coffee's unit.
        val nba = say("BG 100 and 60 carbs", 5).cards.single() as NbaCard
        assertEquals("Take 7 u", nba.action.headline)
    }
}
