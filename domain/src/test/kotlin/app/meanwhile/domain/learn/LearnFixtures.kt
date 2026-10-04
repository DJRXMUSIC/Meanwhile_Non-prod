package app.meanwhile.domain.learn

import app.meanwhile.domain.dose.DoseEngine
import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.stats.DoseRow
import app.meanwhile.domain.stats.OutcomeRow
import java.time.ZoneId
import java.time.ZonedDateTime

/** Builds realistic proposal → dose → outcome histories through the real dose engine. */
class LearnFixtures(val profile: Profile = Profile()) {
    val zone: ZoneId = ZoneId.of("America/New_York")
    val proposals = mutableListOf<ProposalFacts>()
    val doses = mutableListOf<DoseRow>()
    val outcomes = mutableListOf<OutcomeRow>()
    val meals = mutableListOf<MealRow>()
    private var n = 0

    /** Day 0 = 2026-10-01 in [zone]. */
    fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 10, 1, hour, minute, 0, 0, zone).plusDays(day.toLong()).toInstant().toEpochMilli()

    /**
     * A proposal for [input] at [atMillis], logged as [given] units (default: as proposed), whose BG
     * ended at [end] (min [min]). Returns the proposal id.
     */
    fun dose(
        atMillis: Long,
        input: DoseInput,
        end: Int?,
        min: Int? = end,
        given: Double? = null,
        profileUsed: Profile = profile,
        withMeal: Boolean = input.carbsG > 0,
    ): String {
        val id = "p${++n}"
        val result = DoseEngine.compute(input, profileUsed)
        proposals += ProposalFacts(id, atMillis, input, result, profileUsed)
        val doseId = "d$n"
        doses += DoseRow(doseId, atMillis, "rapid", given ?: result.finalUnits.toDouble(), result.finalUnits.toDouble(), id)
        outcomes += OutcomeRow(doseId, bg2h = end, bg3h = end, bg4h = end, min4h = min, max4h = end)
        if (withMeal) meals += MealRow(atMillis + 12 * 60_000L, id)
        return id
    }

    fun lessons(): List<Lesson> = Lessons.build(proposals, doses, outcomes, meals, zone, profile.learning)
}
