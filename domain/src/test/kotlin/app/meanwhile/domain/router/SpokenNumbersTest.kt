package app.meanwhile.domain.router

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenNumbersTest {
    private fun n(s: String) = SpokenNumbers.normalize(s)

    @Test
    fun `plain words become digits`() {
        assertEquals("60 carbs 20 fat 30 protein", n("sixty carbs twenty fat thirty protein"))
        assertEquals("1 coffee", n("one coffee"))
        assertEquals("15 grams", n("fifteen grams"))
        assertEquals("ran 5 miles", n("ran five miles"))
    }

    @Test
    fun `compounds and hundreds`() {
        assertEquals("25 carbs", n("twenty five carbs"))
        assertEquals("25 carbs", n("twenty-five carbs"))
        assertEquals("bg is 250", n("bg is two hundred and fifty"))
        assertEquals("100", n("a hundred"))
        assertEquals("105", n("hundred and five"))
        assertEquals("125", n("one hundred twenty five"))
    }

    @Test
    fun `and is only eaten with a number`() {
        assertEquals("100 and then some", n("a hundred and then some"))
        assertEquals("coffee and 1 beer", n("coffee and one beer"))
        assertEquals("pizza and a coffee", n("pizza and a coffee"))
    }

    @Test
    fun `halves and decimals`() {
        assertEquals("took 6.5 units", n("took six and a half units"))
        assertEquals("2.5 units", n("two point five units"))
        assertEquals("6.5 units", n("6 and a half units"))
    }

    @Test
    fun `everything else passes through`() {
        assertEquals("had a cold brew", n("had a cold brew"))
        assertEquals("didn't sleep well", n("didn't sleep well"))
        assertEquals("60 carbs 20 fat", n("60 carbs 20 fat"))
        assertEquals("someone said hi", n("someone said hi"))
    }
}
