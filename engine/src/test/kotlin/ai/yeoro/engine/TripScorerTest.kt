package ai.yeoro.engine
import org.junit.Assert.assertEquals
import org.junit.Test

class TripScorerTest {
    private val sat = CalendarInfo(weekend = true, holiday = false, hour = 8)
    private val tueMorning = CalendarInfo(weekend = false, holiday = false, hour = 7)
    private val family = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_RIGHT)

    @Test fun plannedFamilyTrip_isConfident100() {
        val r = TripScorer.score(ScoreInput(Destination("강릉 경포해변", 238.0, PlaceKind.TOURIST), family, 2, sat, false, 0.0, 0))
        assertEquals(100, r.total); assertEquals(Band.CONFIDENT, r.band)
    }
    @Test fun spontaneousDrive_startsAsCandidate45_thenRisesTo80() {
        val seats = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT)
        val start = TripScorer.score(ScoreInput(null, seats, 1, sat, true, 0.0, 0))
        assertEquals(45, start.total); assertEquals(Band.CANDIDATE, start.band)
        val far = TripScorer.score(ScoreInput(null, seats, 1, sat, true, 86.0, 0))
        assertEquals(60, far.total)
        val parked = TripScorer.score(ScoreInput(null, seats, 1, sat, true, 86.0, 60))
        assertEquals(80, parked.total); assertEquals(Band.CONFIDENT, parked.band)
    }
    @Test fun commute_isZeroDaily() {
        val r = TripScorer.score(ScoreInput(Destination("회사", 9.0, PlaceKind.WORK, frequent = true), setOf(Seat.DRIVER), 0, tueMorning, false, 0.0, 0))
        assertEquals(0, r.total); assertEquals(Band.DAILY, r.band)
    }
    @Test fun soloBusinessTrip_isDaily() {
        val r = TripScorer.score(ScoreInput(Destination("부산 벡스코", 392.0, PlaceKind.WORK), setOf(Seat.DRIVER), 0, CalendarInfo(false, false, 6), false, 0.0, 0))
        assertEquals(Band.DAILY, r.band)
    }
    @Test fun grandmaHoliday_isConfidentAndCapped() {
        val r = TripScorer.score(ScoreInput(Destination("할머니댁", 142.0, PlaceKind.OTHER, regular = true), family, 2, CalendarInfo(false, true, 7), false, 0.0, 0))
        assertEquals(100, r.total)
    }
    @Test fun thresholdBoost_raisesTheBar() {
        val r = TripScorer.score(ScoreInput(Destination("가평", 60.0, PlaceKind.TOURIST), family, 0, sat, false, 0.0, 0), thresholdBoost = 10)
        assertEquals(75, r.total); assertEquals(Band.CANDIDATE, r.band)
    }
}
