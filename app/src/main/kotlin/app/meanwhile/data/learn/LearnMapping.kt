package app.meanwhile.data.learn

import app.meanwhile.data.ai.LearnCycleDto
import app.meanwhile.domain.profile.FactorDefinition
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileChange
import app.meanwhile.domain.profile.ProfileJson
import app.meanwhile.domain.profile.ProfilePatch
import app.meanwhile.domain.profile.ProfileValidation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a learn-cycle result (spec §10.3) into path-addressed profile changes. Each change is checked
 * against the current profile on its own; ones that can't apply (bad path/type) are dropped.
 */
object LearnMapping {

    fun changes(dto: LearnCycleDto, profile: Profile): List<ProfileChange> {
        val out = mutableListOf<ProfileChange>()
        val s = dto.proposedSettings
        fun setting(path: String, value: Double?, asInt: Boolean = false, why: String) {
            if (value == null) return
            val new: JsonElement = if (asInt) JsonPrimitive(Math.round(value)) else JsonPrimitive(value)
            out += ProfileChange(path, ProfilePatch.get(profile, path), new, why)
        }
        val why = dto.summary
        setting("dose.icr", s.icr, why = why)
        setting("dose.isf", s.isf, why = why)
        setting("dose.target", s.target, why = why)
        setting("leadTime.baseMin", s.leadTimeMin, asInt = true, why = why)
        setting("iob.peakMin", s.iobPeakMin, why = why)
        setting("iob.durationMin", s.iobDurationMin, why = why)

        for (fc in dto.factorChanges) {
            val base = "factors.${fc.factorId}"
            val new = fc.new ?: JsonNull
            when (fc.field) {
                "units_add" -> out += change(profile, "$base.unitsPerEvent", number(new), fc.evidence)
                "weight", "default_weight" -> out += change(profile, "$base.defaultWeight", number(new), fc.evidence)
                "bounds" -> {
                    val (min, max) = bounds(new)
                    if (min != null) out += change(profile, "$base.minWeight", min, fc.evidence)
                    if (max != null) out += change(profile, "$base.maxWeight", max, fc.evidence)
                }
                "window" -> out += if (new is JsonObject) {
                    change(profile, "$base.window", camel(new), fc.evidence)
                } else {
                    change(profile, "$base.window.minutes", new.jsonPrimitive.intOrNull?.let { JsonPrimitive(it) } ?: new, fc.evidence)
                }
                "decay" -> out += change(profile, "$base.decay", decay(new), fc.evidence)
            }
        }
        for (nf in dto.newFactors) {
            val def: FactorDefinition = nf.definition.toDomain().copy(id = nf.id.ifBlank { nf.definition.id }, name = nf.name.ifBlank { nf.definition.name })
            out += ProfileChange("factors.${def.id}", ProfilePatch.get(profile, "factors.${def.id}"), ProfileJson.json.encodeToJsonElement(FactorDefinition.serializer(), def), nf.evidence)
        }
        for (sc in dto.settingChanges) {
            out += ProfileChange(sc.path, ProfilePatch.get(profile, sc.path), sc.new ?: JsonNull, sc.evidence)
        }
        // Also dropped: a change that on its own leaves values the dose math can't use (e.g. ICR 0).
        return out.filter { ch ->
            ch.old != ch.new && ProfilePatch.apply(profile, listOf(ch)).getOrNull()?.let { ProfileValidation.problems(it).isEmpty() } == true
        }
    }

    private fun change(profile: Profile, path: String, new: JsonElement, why: String) =
        ProfileChange(path, ProfilePatch.get(profile, path), new, why)

    private fun number(e: JsonElement): JsonElement = (e as? JsonPrimitive)?.doubleOrNull?.let { JsonPrimitive(it) } ?: e

    private fun bounds(e: JsonElement): Pair<JsonElement?, JsonElement?> = when (e) {
        is JsonObject -> (e["min"] ?: e["min_weight"] ?: e["minWeight"]) to (e["max"] ?: e["max_weight"] ?: e["maxWeight"])
        is JsonArray -> e.getOrNull(0) to e.getOrNull(1)
        else -> null to null
    }

    /** snake_case keys from the AI → the profile's camelCase. */
    private fun camel(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapKeys { (k, _) -> k.split('_').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar(Char::uppercase) }.joinToString("") }.mapValues { camel(it.value) })
        is JsonArray -> JsonArray(e.map { camel(it) })
        else -> e
    }

    private fun decay(e: JsonElement): JsonElement = when (e) {
        is JsonArray -> camel(JsonObject(mapOf("steps" to e)))
        is JsonObject -> camel(e)
        else -> e
    }

}
