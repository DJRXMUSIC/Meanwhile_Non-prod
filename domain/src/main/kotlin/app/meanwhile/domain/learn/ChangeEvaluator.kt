package app.meanwhile.domain.learn

import app.meanwhile.domain.profile.LearningSettings
import kotlinx.serialization.Serializable
import java.util.Locale

/** An applied profile change being watched: [atMillis] is when it took effect. */
@Serializable
data class TrackedChange(
    val id: String,
    val path: String,
    val atMillis: Long,
    /** True when the change makes doses bigger (null when the direction isn't clear). */
    val moreInsulin: Boolean?,
)

@Serializable
enum class Verdict { PENDING, KEEP, REVERT }

@Serializable
data class Evaluation(
    val changeId: String,
    val path: String,
    val verdict: Verdict,
    val scoreBefore: Double?,
    val scoreAfter: Double?,
    val lessonsBefore: Int,
    val lessonsAfter: Int,
    val reason: String,
)

/**
 * Closes the loop: every applied change (local tuner, AI, or Danny's) is judged on the clean lessons
 * that came after it versus the ones before it. Worse by more than
 * [LearningSettings.revertIfWorsePct] → revert. A severe low after a change that gives more insulin
 * reverts it at once.
 */
object ChangeEvaluator {
    private const val DAY_MS = 86_400_000L

    /** Which lessons speak to a change at [path]. */
    fun relevant(path: String, lesson: Lesson): Boolean {
        val factorId = Regex("^(?:factors|active)\\.([^.]+)").find(path)?.groupValues?.get(1)
        return when {
            path == "dose.icr" -> lesson.kind == LessonKind.MEAL
            path == "dose.isf" -> lesson.kind == LessonKind.CORRECTION
            factorId != null -> factorId in lesson.factors
            else -> true
        }
    }

    fun evaluate(change: TrackedChange, lessons: List<Lesson>, s: LearningSettings): Evaluation {
        val usable = lessons.filter { it.clean && it.score != null && relevant(change.path, it) }
        val before = usable.filter { it.atMillis < change.atMillis && it.atMillis >= change.atMillis - s.lookbackDays * DAY_MS }
        val after = usable.filter { it.atMillis >= change.atMillis }
        val sb = before.takeIf { it.isNotEmpty() }?.map { it.score!! }?.average()
        val sa = after.takeIf { it.isNotEmpty() }?.map { it.score!! }?.average()
        fun result(v: Verdict, why: String) = Evaluation(change.id, change.path, v, sb, sa, before.size, after.size, why)

        if (change.moreInsulin == true) {
            after.filter { it.min4h != null && it.min4h < s.severeLowMgDl }.minByOrNull { it.min4h!! }?.let { low ->
                return result(Verdict.REVERT, "A ${low.min4h} mg/dL low followed this change, which gives more insulin")
            }
        }
        if (after.size < s.evaluateAfterLessons) {
            return result(Verdict.PENDING, "${after.size} of ${s.evaluateAfterLessons} lessons since the change")
        }
        if (sb == null) return result(Verdict.KEEP, "No earlier outcomes to compare against; ${after.size} since look fine")
        return if (sa!! > sb * (1 + s.revertIfWorsePct / 100)) {
            result(Verdict.REVERT, "Outcomes got worse: average miss ${fmt(sb)} → ${fmt(sa)} mg/dL")
        } else {
            result(Verdict.KEEP, "Outcomes held or improved: average miss ${fmt(sb)} → ${fmt(sa)} mg/dL")
        }
    }

    /** Does moving [path] from [old] to [new] make doses bigger? */
    fun moreInsulin(path: String, old: Double?, new: Double?): Boolean? {
        if (old == null || new == null || old == new) return null
        val leaf = path.substringAfterLast('.')
        return when (leaf) {
            "icr", "isf", "target", "fatGPerUnit", "proteinGPerUnit" -> new < old
            "unitsPerEvent", "weight", "defaultWeight", "kFatPerG", "kProteinPerG", "combinedCap", "maxWeight" -> new > old
            else -> null
        }
    }

    private fun fmt(x: Double) = String.format(Locale.US, "%.0f", x)
}
