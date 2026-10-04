package app.meanwhile.misc

import app.meanwhile.data.ai.LearnCycleDto
import app.meanwhile.data.ai.LearnFactorChangeDto
import app.meanwhile.data.ai.LearnSettingChangeDto
import app.meanwhile.data.ai.ProposedSettingsDto
import app.meanwhile.data.export.CsvExporter
import app.meanwhile.data.learn.LearnMapping
import app.meanwhile.data.learn.LearningEngine
import app.meanwhile.domain.profile.Profile
import app.meanwhile.ui.nav.Routes
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests for small pieces with outsized consequences. */
class PureLogicTest {

    @Test
    fun `only known screens can be opened from outside the app`() {
        listOf("main", "settings", "morning", "stats", "learning", "log", "profile", "setup",
            "review/0192f1a2-3b4c-7d5e-8f60-123456789abc", "profile-version/0192f1a2-3b4c-7d5e-8f60-123456789abc")
            .forEach { assertTrue(it, Routes.isExternalDestination(it)) }
        listOf(null, "", "export", "debug-dose", "review/../../etc", "review/not-a-uuid", "profile-json?path=dose", "settings/extra", "javascript:x")
            .forEach { assertFalse(it.toString(), Routes.isExternalDestination(it)) }
    }

    @Test
    fun `CSV cells can't become spreadsheet formulas`() {
        assertEquals("'=HYPERLINK(\"x\")".let { "\"" + it.replace("\"", "\"\"") + "\"" }, CsvExporter.escapeCell("=HYPERLINK(\"x\")"))
        assertEquals("'+cmd", CsvExporter.escapeCell("+cmd"))
        assertEquals("'@SUM(A1)", CsvExporter.escapeCell("@SUM(A1)"))
        assertEquals("-1.5", CsvExporter.escapeCell("-1.5")) // numbers stay numbers
        assertEquals("\"pizza, large\"", CsvExporter.escapeCell("pizza, large"))
        assertEquals("plain", CsvExporter.escapeCell("plain"))
    }

    @Test
    fun `learn-cycle results map to profile paths and nonsense is dropped`() {
        val dto = LearnCycleDto(
            proposedSettings = ProposedSettingsDto(icr = 9.0, isf = 0.0), // ISF 0 would break the math
            factorChanges = listOf(
                LearnFactorChangeDto("F4", "units_add", new = JsonPrimitive(1.5), evidence = "coffee ran high"),
                LearnFactorChangeDto("F99", "weight", new = JsonPrimitive(1.2)), // unknown factor
            ),
            settingChanges = listOf(LearnSettingChangeDto("split.firstFraction", new = JsonPrimitive(0.5))),
            summary = "s",
        )
        val changes = LearnMapping.changes(dto, Profile()).associateBy { it.path }
        assertEquals(setOf("dose.icr", "factors.F4.unitsPerEvent", "split.firstFraction"), changes.keys)
        assertEquals(JsonPrimitive(9.0), changes.getValue("dose.icr").new)
        assertEquals("coffee ran high", changes.getValue("factors.F4.unitsPerEvent").reason)
    }

    @Test
    fun `learning labels and core paths`() {
        assertEquals("ICR", LearningEngine.label("dose.icr"))
        assertEquals("Caffeine units each", LearningEngine.label("factors.F4.unitsPerEvent", Profile()))
        assertTrue(LearningEngine.isCore("dose.isf"))
        assertTrue(LearningEngine.isCore("iob.peakMin"))
        assertFalse(LearningEngine.isCore("factors.F4.unitsPerEvent"))
        assertEquals("8.8", LearningEngine.fmt(8.8))
        assertEquals("10", LearningEngine.fmt(10.0))
    }
}
