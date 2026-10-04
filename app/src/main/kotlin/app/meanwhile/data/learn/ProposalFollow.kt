package app.meanwhile.data.learn

import app.meanwhile.data.db.DoseEntity

/** How Danny acted on a Next Best Action proposal. */
object ProposalFollow {
    const val FOLLOWED = "followed"
    const val OVERRIDDEN = "overridden"
    const val NOT_LOGGED = "not_logged"

    /**
     * Followed = every injection logged against the proposal matched what it proposed for that
     * injection and the total equals the proposal. Anything else (edited amount, skipped second part)
     * is an override; no injection logged = not logged (dismissed or ignored).
     */
    fun classify(finalUnits: Int, doses: List<DoseEntity>): String {
        if (doses.isEmpty()) return NOT_LOGGED
        val total = Math.round(doses.sumOf { it.units }).toInt()
        val each = doses.all { d -> d.proposedUnits == null || Math.round(d.units) == Math.round(d.proposedUnits) }
        return if (total == finalUnits && each) FOLLOWED else OVERRIDDEN
    }
}
