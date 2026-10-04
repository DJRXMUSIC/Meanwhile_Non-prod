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
}
