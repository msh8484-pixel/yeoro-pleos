package ai.yeoro.ux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test fun allowedWhenNotRestrictedAndNotReduced() { assertTrue(motionAllowed(restricted = false, reducedMotionSetting = false)) }
    @Test fun restrictedDisablesMotion() { assertFalse(motionAllowed(restricted = true, reducedMotionSetting = false)) }
    @Test fun reducedSettingDisablesMotion() { assertFalse(motionAllowed(restricted = false, reducedMotionSetting = true)) }
    @Test fun bothDisableMotion() { assertFalse(motionAllowed(restricted = true, reducedMotionSetting = true)) }
}
