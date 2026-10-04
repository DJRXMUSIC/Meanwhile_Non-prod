package app.meanwhile.data.learn

import app.meanwhile.data.db.DoseEntity
import app.meanwhile.domain.stats.Follow
import app.meanwhile.domain.stats.ProposalFollowRule

/** How Danny acted on a Next Best Action proposal (rule: [ProposalFollowRule]). */
object ProposalFollow {
    const val FOLLOWED = "followed"
    const val OVERRIDDEN = "overridden"
    const val NOT_LOGGED = "not_logged"

    fun classify(finalUnits: Int, doses: List<DoseEntity>): String =
        when (ProposalFollowRule.classify(finalUnits, doses.map { it.units to it.proposedUnits })) {
            Follow.FOLLOWED -> FOLLOWED
            Follow.OVERRIDDEN -> OVERRIDDEN
            Follow.NOT_LOGGED -> NOT_LOGGED
        }
}
