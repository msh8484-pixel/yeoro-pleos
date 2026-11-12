package ai.yeoro.engine

class NotificationPolicy {
    private var lastProposalDay: String? = null
    private val declines = mutableMapOf<String, Int>()
    fun canPropose(dayKey: String, gear: Gear): Boolean = gear == Gear.P && lastProposalDay != dayKey
    fun recordProposal(dayKey: String) { lastProposalDay = dayKey }
    fun recordDecline(kind: String) { declines[kind] = (declines[kind] ?: 0) + 1 }
    fun boostFor(kind: String): Int = if ((declines[kind] ?: 0) >= 3) 10 else 0
}
