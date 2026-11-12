package ai.yeoro.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PurgeCutoffTest {
    private val h = 3_600_000L
    @Test fun noCandidate_cutoffIs24hAgo() = assertEquals(100 * h - 24 * h, purgeCutoff(100 * h, null))
    @Test fun oldCandidate_protectsItsMoments() = assertEquals(30 * h, purgeCutoff(100 * h, 30 * h))
    @Test fun recentCandidate_keeps24hRule() = assertEquals(76 * h, purgeCutoff(100 * h, 90 * h))
}
