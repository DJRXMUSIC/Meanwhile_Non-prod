package app.meanwhile.domain.profile

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileOpsTest {
    private val base = Profile()

    @Test fun roundTripsJson() {
        assertEquals(base, ProfileJson.decode(ProfileJson.encode(base)))
    }

    @Test fun patchesScalarsListsAndMaps() {
        val p = ProfilePatch.apply(
            base,
            listOf(
                ProfileChange("dose.icr", new = JsonPrimitive(12.0)),
                ProfileChange("factors.F7.maxWeight", new = JsonPrimitive(1.2)),
                ProfileChange("factors.F11.params.perHour", new = JsonPrimitive(0.06)),
                ProfileChange("leadTime.factorMin.F12", new = JsonPrimitive(3)),
            ),
        ).getOrThrow()
        assertEquals(12.0, p.dose.icr)
        assertEquals(1.2, p.factor("F7")!!.maxWeight)
        assertEquals(0.06, p.factor("F11")!!.params["perHour"])
        assertEquals(3, p.leadTime.factorMin["F12"])
        assertEquals(JsonPrimitive(12.0), ProfilePatch.get(p, "dose.icr"))
    }

    @Test fun addsAWholeNewFactor() {
        val dawn = FactorDefinition(
            id = "F13", name = "Dawn phenomenon", kind = FactorKind.MULTIPLIER, defaultWeight = 1.15,
            window = WindowRule(WindowType.FIXED, minutes = 180),
        )
        val tree = ProfileJson.json.encodeToJsonElement(FactorDefinition.serializer(), dawn)
        val p = ProfilePatch.apply(base, listOf(ProfileChange("factors.F13", new = tree))).getOrThrow()
        assertNotNull(p.factor("F13"))
        assertEquals(13, p.factors.size)
    }

    @Test fun invalidChangesFailWithoutApplying() {
        assertTrue(ProfilePatch.apply(base, listOf(ProfileChange("factors.F99.maxWeight", new = JsonPrimitive(1.0)))).isFailure)
        assertTrue(ProfilePatch.apply(base, listOf(ProfileChange("dose.icr", new = JsonPrimitive("ten")))).isFailure)
    }

    @Test fun diffFindsLeafChanges() {
        val changed = base.copy(
            dose = base.dose.copy(isf = 30.0),
            active = listOf(ActiveFactor("F8", 1.25, 1L, source = "morning_report")),
        )
        val d = ProfileDiff.diff(base, changed)
        assertEquals(setOf("dose.isf", "active.F8"), d.map { it.path }.toSet())
        val roundTrip = ProfilePatch.apply(base, d).getOrThrow()
        assertEquals(changed, roundTrip)
        assertEquals(JsonPrimitive(30.0), d.first { it.path == "dose.isf" }.new)
        assertTrue(d.first { it.path == "active.F8" }.new!!.toString().contains("1.25"))
        assertTrue(ProfileJson.tree(changed).jsonObject.containsKey("factors"))
    }
}
