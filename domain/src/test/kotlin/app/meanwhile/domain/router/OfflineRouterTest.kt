package app.meanwhile.domain.router

import app.meanwhile.domain.profile.DefaultFactors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineRouterTest {
    private val router = OfflineRouter(DefaultFactors.all)
    private fun route(s: String) = router.route(s).intents

    @Test fun macrosAsNumbers() {
        val m = assertIs<MealIntent>(route("60 carbs 20 fat 30 protein").single())
        assertEquals(60.0, m.carbsG)
        assertEquals(20.0, m.fatG)
        assertEquals(30.0, m.proteinG)
    }

    @Test fun macroVariants() {
        val a = assertIs<MealIntent>(route("60c 20f 30p").single())
        assertEquals(listOf(60.0, 20.0, 30.0), listOf(a.carbsG, a.fatG, a.proteinG))
        val b = assertIs<MealIntent>(route("carbs: 45, protein 12g").single())
        assertEquals(listOf(45.0, 0.0, 12.0), listOf(b.carbsG, b.fatG, b.proteinG))
        val c = assertIs<MealIntent>(route("75 grams of carbs and a glass of orange juice").first { it is MealIntent })
        assertEquals(75.0, c.carbsG)
        assertTrue(c.liquidOrSugary)
    }

    @Test fun coffees() {
        val f = assertIs<FactorIntent>(route("2 coffees").single())
        assertEquals("F4", f.factorId)
        assertEquals(2.0, f.amount)
        assertEquals(1.0, assertIs<FactorIntent>(route("had a coffee").single()).amount)
        assertEquals(2.0, assertIs<FactorIntent>(route("two lattes").single()).amount)
    }

    @Test fun alcohol() {
        val beer = assertIs<FactorIntent>(route("beer").single())
        assertEquals("F5", beer.factorId)
        assertEquals(1.0, beer.amount)
        val wine = assertIs<FactorIntent>(route("glass of wine").single())
        assertEquals("F5", wine.factorId)
        assertEquals("wine", wine.details["drink"])
        assertEquals(3.0, assertIs<FactorIntent>(route("3 beers").single()).amount)
    }

    @Test fun exercise() {
        val run = assertIs<FactorIntent>(route("ran 5 miles").single())
        assertEquals("F7", run.factorId)
        assertEquals("run", run.details["type"])
        assertEquals("5 miles", run.details["distance"])
        val lift = assertIs<FactorIntent>(route("lifted").single())
        assertEquals("strength", lift.details["type"])
        val walk = assertIs<FactorIntent>(route("walked 45 minutes, finished 20 minutes ago").single())
        assertEquals("45", walk.details["duration_min"])
        assertEquals(20, walk.minutesAgo)
    }

    @Test fun doses() {
        val rapid = assertIs<DoseIntent>(route("took 7 units").single())
        assertEquals(7.0, rapid.units)
        assertEquals("rapid", rapid.insulin)
        val long = assertIs<DoseIntent>(route("took my long-acting 22").single())
        assertEquals(22.0, long.units)
        assertEquals("long", long.insulin)
        assertEquals("long", assertIs<DoseIntent>(route("took 22 units of lantus").single()).insulin)
        assertEquals(6.0, assertIs<DoseIntent>(route("6 units").single()).units)
        val ago = assertIs<DoseIntent>(route("bolused 5u 20 minutes ago").single())
        assertEquals(20, ago.minutesAgo)
    }

    @Test fun combinedFactorFirstThenMeal() {
        val r = route("pizza and a coffee")
        assertEquals(listOf("factor_update", "meal"), r.map { it.type })
        assertEquals("F4", (r[0] as FactorIntent).factorId)
        val meal = r[1] as MealIntent
        assertEquals("pizza", meal.description)
        assertTrue(!meal.hasMacros)
    }

    @Test fun combinedWithMacros() {
        val r = route("50 carbs and 2 beers")
        assertEquals(listOf("factor_update", "meal"), r.map { it.type })
        assertEquals(2.0, (r[0] as FactorIntent).amount)
        assertEquals(50.0, (r[1] as MealIntent).carbsG)
    }

    @Test fun feedbackPrefixes() {
        listOf("feedback the card is too long", "App note: add a graph", "idea - log water faster", "bug mic stops").forEach {
            assertIs<FeedbackIntent>(route(it).single(), it)
        }
        assertEquals("add a graph", (route("App note: add a graph").single() as FeedbackIntent).text)
    }

    @Test fun otherFactors() {
        assertEquals("illness", assertIs<FactorIntent>(route("feeling sick").single()).preset)
        assertEquals("stress", assertIs<FactorIntent>(route("really stressed today").single()).preset)
        assertEquals("F12", assertIs<FactorIntent>(route("got sunburned").single()).factorId)
        assertEquals("dehydrated", assertIs<FactorIntent>(route("dehydrated").single()).preset)
        assertEquals("deactivate", assertIs<FactorIntent>(route("drank water").single()).action)
        assertEquals("poor", assertIs<FactorIntent>(route("slept badly").single()).preset)
        assertEquals("poor", assertIs<FactorIntent>(route("didn't sleep well").single()).preset)
        assertEquals("good", assertIs<FactorIntent>(route("slept great").single()).preset)
    }

    @Test fun foodDescriptionNeedsMacros() {
        val m = assertIs<MealIntent>(route("ate a turkey sandwich").single())
        assertTrue(!m.hasMacros)
        assertEquals("turkey sandwich", m.description)
    }

    @Test fun coldBrewIsCoffeeNotIllness() {
        val r = route("had a cold brew")
        assertEquals(listOf("F4"), r.filterIsInstance<FactorIntent>().map { it.factorId })
        assertNull(r.filterIsInstance<MealIntent>().firstOrNull())
    }

    @Test
    fun `spoken numbers parse like typed ones`() {
        val meal = router.route("sixty carbs twenty fat thirty protein").intents.filterIsInstance<MealIntent>().single()
        assertEquals(60.0, meal.carbsG)
        assertEquals(20.0, meal.fatG)
        assertEquals(30.0, meal.proteinG)

        val dose = router.route("took six and a half units").intents.filterIsInstance<DoseIntent>().single()
        assertEquals(6.5, dose.units)

        val coffee = router.route("two coffees").intents.filterIsInstance<FactorIntent>().single()
        assertEquals("F4", coffee.factorId)
    }

    // --- 1.4: talking to it like a person -------------------------------------------------------

    private fun correction(s: String) = assertIs<DoseCorrectionIntent>(route(s).single(), s)

    @Test
    fun `corrections with a new amount`() {
        assertEquals(5.0, correction("never mind I only took 5").units)
        assertEquals(5.0, correction("nevermind, I only took five units").units)
        assertEquals(4.0, correction("make that 4").units)
        assertEquals(5.0, correction("actually 5").units)
        assertEquals(4.0, correction("no, 4").units)
        assertEquals(5.0, correction("I took 5 not 6").units)
        assertEquals(5.0, correction("I took 6 earlier, actually only 5").units)
        assertEquals(5.0, correction("sorry it was 5 units").units)
        assertEquals(5.0, correction("I didn't take 6, I took 5").units)
        assertEquals(3.5, correction("I meant three and a half").units)
    }

    @Test
    fun `corrections that cancel the dose`() {
        listOf("I didn't take any", "never mind I didn't take it", "cancel that", "scratch that", "undo that",
            "I never took it", "I haven't taken it", "never mind").forEach {
            assertEquals(0.0, correction(it).units, it)
        }
        val long = correction("I didn't take my long acting")
        assertEquals(0.0, long.units)
        assertEquals("long", long.insulin)
    }

    @Test
    fun `a correction can move the time`() {
        val c = correction("actually it was 5 units 20 minutes ago")
        assertEquals(5.0, c.units)
        assertEquals(20, c.minutesAgo)
        val t = correction("sorry I took it 15 minutes ago")
        assertNull(t.units)
        assertEquals(15, t.minutesAgo)
    }

    @Test
    fun `things that are not dose corrections`() {
        assertEquals(listOf("meal"), route("actually it was 60 carbs").map { it.type })
        assertEquals(listOf("factor_update"), route("actually 2 beers").map { it.type })
        assertEquals(listOf("factor_update"), route("actually I also had a coffee").map { it.type })
        assertEquals(listOf("factor_update"), route("didn't sleep well").map { it.type })
        assertEquals(listOf("dose_given"), route("took 6 units").map { it.type })
        assertEquals(listOf("meal"), route("what should I take").map { it.type })
    }

    @Test
    fun `the AI's correction spans are read without trigger words`() {
        assertEquals(5.0, router.correctionFrom("only 5").units)
        assertEquals(0.0, router.correctionFrom("didn't take it").units)
        assertEquals(5.0, router.correctionFrom("five").units)
    }

    @Test
    fun `took it means the last suggestion was followed`() {
        listOf("took it", "ok took it", "I took it", "did it", "done", "all done", "ate it", "I took the dose", "ok done!").forEach {
            assertIs<FollowedIntent>(route(it).single(), it)
        }
        assertEquals(10, assertIs<FollowedIntent>(route("took it 10 minutes ago").single()).minutesAgo)
        // "done" inside a longer sentence is not a confirmation.
        assertTrue(route("done with my run").none { it is FollowedIntent })
        assertEquals(6.0, assertIs<DoseIntent>(route("took the 6").single()).units)
    }

    @Test
    fun `a spoken BG is used for this message`() {
        assertEquals(140.0, assertIs<BgIntent>(route("BG 140").single()).mgDl)
        assertEquals(85.0, assertIs<BgIntent>(route("my blood sugar is 85").single()).mgDl)
        assertEquals(210.0, assertIs<BgIntent>(route("I'm at 210").single()).mgDl)
        val withMeal = route("bg 180 and 45 carbs")
        assertEquals(listOf("bg_reading", "meal"), withMeal.map { it.type })
        assertEquals(45.0, (withMeal[1] as MealIntent).carbsG)
        val question = route("sugar's 250 what should I do")
        assertEquals(listOf("bg_reading", "meal"), question.map { it.type })
        assertEquals(OfflineRouter.CHECK_DESCRIPTION, (question[1] as MealIntent).description)
    }

    @Test
    fun `asking what to do is a Next Best Action with no food`() {
        listOf("what should I do", "what should I take?", "correction", "should I eat something", "check", "do I need insulin").forEach {
            val m = assertIs<MealIntent>(route(it).single(), it)
            assertEquals(OfflineRouter.CHECK_DESCRIPTION, m.description, it)
        }
        val food = assertIs<MealIntent>(route("should I take insulin for a sandwich").single())
        assertEquals("sandwich", food.description)
    }
}
