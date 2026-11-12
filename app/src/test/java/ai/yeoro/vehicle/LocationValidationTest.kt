package ai.yeoro.vehicle

import org.junit.Assert.*
import org.junit.Test

class LocationValidationTest {
    @Test fun zeroAndNearZero_areInvalid() {
        assertFalse(isValidLocation(0.0, 0.0))
        assertFalse(isValidLocation(5e-7, -5e-7))
        assertFalse(isValidLocation(Double.NaN, 127.0))
    }
    @Test fun realCoordinates_areValid() {
        assertTrue(isValidLocation(37.5665, 126.9780))
        assertTrue(isValidLocation(0.0, 127.0))   // 한쪽만 0인 좌표는 실제로 있을 수 있다
    }
}
