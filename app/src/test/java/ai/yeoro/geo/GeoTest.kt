package ai.yeoro.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test fun seoulToBusanIsAbout325Km() {
        assertEquals(325.0, haversineKm(37.5665 to 126.9780, 35.1796 to 129.0756), 5.0)
    }
    @Test fun identicalPointsAreZero() {
        assertEquals(0.0, haversineKm(37.5665 to 126.9780, 37.5665 to 126.9780), 0.0)
    }
    @Test fun shortDistanceIsAbout1Point06Km() {
        assertEquals(1.06, haversineKm(37.5665 to 126.9780, 37.5665 to 126.9900), 0.05)
    }
}
