package app.meanwhile.domain.router

import app.meanwhile.domain.profile.CoffeeSettings
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2.0: a coffee comes with 1/8 cup whole milk per cup unless Danny says otherwise. */
class CoffeeMilkTest {
    private val s = CoffeeSettings()

    private fun near(expected: Double, actual: Double) = assertTrue(kotlin.math.abs(expected - actual) < 1e-9, "expected $expected, was $actual")

    @Test
    fun `one coffee is 1-8 cup whole milk`() {
        val f = assertNotNull(CoffeeMilk.food("had a coffee", null, s))
        assertEquals("coffee (1/8 cup whole milk each)", f.description)
        near(1.5, f.carbsG)
        near(1.0, f.fatG)
        near(1.0, f.proteinG)
    }

    @Test
    fun `each cup brings its milk`() {
        val f = assertNotNull(CoffeeMilk.food("2 coffees", 2.0, s))
        assertEquals("2 coffees (1/8 cup whole milk each)", f.description)
        near(3.0, f.carbsG)
        near(0.25, f.milkCups)
    }

    @Test
    fun `black means no milk`() {
        val f = assertNotNull(CoffeeMilk.food("2 coffees, black", 2.0, s))
        assertEquals("2 coffees, black", f.description)
        near(0.0, f.carbsG)
        near(0.0, f.fatG)
        assertNotNull(CoffeeMilk.food("coffee no milk", null, s)).let { near(0.0, it.proteinG) }
    }

    @Test
    fun `a stated amount replaces the default`() {
        val total = assertNotNull(CoffeeMilk.food("coffee with 1/4 cup milk", null, s))
        near(0.25, total.milkCups)
        near(3.0, total.carbsG)
        assertEquals("coffee (1/4 cup whole milk)", total.description)
        val each = assertNotNull(CoffeeMilk.food("2 coffees with half a cup of whole milk each", 2.0, s))
        near(1.0, each.milkCups)
        near(12.0, each.carbsG)
    }

    @Test
    fun `anything else in the cup is estimated like food`() {
        listOf("coffee with oat milk", "a latte", "coffee with cream and sugar", "iced coffee with vanilla syrup").forEach {
            assertNull(CoffeeMilk.food(it, null, s), it)
        }
    }

    @Test
    fun `the amounts are profile values`() {
        val f = assertNotNull(CoffeeMilk.food("coffee", null, CoffeeSettings(milkCupsPerCoffee = 0.25)))
        near(3.0, f.carbsG)
        val bad = Profile(coffee = CoffeeSettings(milkCupsPerCoffee = -1.0))
        assertTrue(ProfileValidation.problems(bad).any { it.startsWith("coffee.milkCupsPerCoffee") })
    }

    @Test
    fun `words about the cup are not a separate meal`() {
        assertTrue(CoffeeMilk.aboutTheCup("milk"))
        assertTrue(CoffeeMilk.aboutTheCup("black"))
        assertTrue(CoffeeMilk.aboutTheCup("with a splash of whole milk"))
        assertTrue(CoffeeMilk.aboutTheCup("1/4 milk"))
        assertFalse(CoffeeMilk.aboutTheCup("bagel"))
        assertFalse(CoffeeMilk.aboutTheCup("croissant and milk"))
    }
}
