package app.meanwhile.domain.factors

import app.meanwhile.domain.profile.ActiveFactor
import app.meanwhile.domain.profile.DecayRule
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.Profile

/** Pure profile edits for turning factors on and off. */
object Activations {

    /**
     * Activates [factorId]. Weight priority: explicit [weight] → preset → definition default.
     * Non-stacking factors replace any existing activation ("a new drink resets").
     */
    fun activate(
        profile: Profile,
        factorId: String,
        startedAt: Long,
        source: String,
        weight: Double? = null,
        preset: String? = null,
        windowMinutes: Int? = null,
        decay: DecayRule? = null,
        eventId: String? = null,
        note: String? = null,
    ): Profile {
        val def = requireNotNull(profile.factor(factorId)) { "Unknown factor $factorId" }
        require(def.kind == FactorKind.MULTIPLIER || def.kind == FactorKind.AUTO_MULTIPLIER) { "$factorId is not a multiplier" }
        val w = weight ?: preset?.let { def.presets[it] } ?: def.defaultWeight
        val kept = if (def.window.stacks) profile.active else profile.active.filterNot { it.factorId == factorId }
        val activation = ActiveFactor(
            factorId = factorId, weight = w, startedAt = startedAt, windowMinutes = windowMinutes, decay = decay,
            source = source, eventId = eventId, preset = preset, note = note,
        )
        return profile.copy(active = kept + activation)
    }

    fun deactivate(profile: Profile, factorId: String): Profile =
        profile.copy(active = profile.active.filterNot { it.factorId == factorId })

    /** Weight that [activate] would use, for showing the default before applying. */
    fun defaultWeight(profile: Profile, factorId: String, preset: String?): Double? {
        val def = profile.factor(factorId) ?: return null
        return preset?.let { def.presets[it] } ?: def.defaultWeight
    }
}
