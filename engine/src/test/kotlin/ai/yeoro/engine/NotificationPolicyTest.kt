package ai.yeoro.engine
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun onlyInParkAndOncePerDay() {
        val p = NotificationPolicy()
        assertFalse(p.canPropose("2026-10-24", Gear.D))
        assertTrue(p.canPropose("2026-10-24", Gear.P))
        p.recordProposal("2026-10-24")
        assertFalse(p.canPropose("2026-10-24", Gear.P))
        assertTrue(p.canPropose("2026-10-25", Gear.P))
    }
    @Test fun threeDeclinesRaiseThreshold() {
        val p = NotificationPolicy()
        repeat(2) { p.recordDecline("departure") }; assertEquals(0, p.boostFor("departure"))
        p.recordDecline("departure"); assertEquals(10, p.boostFor("departure"))
    }
}
