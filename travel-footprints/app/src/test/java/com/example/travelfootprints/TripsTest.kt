package com.example.travelfootprints

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class TripsTest {
 private val utc = TimeZone.getTimeZone("UTC")
 private val day = 86_400_000L
 private fun p(id: Long, t: Long, lat: Double = 37.5, lng: Double = 130.8) = PhotoPoint(id, "content://t/$id", lat, lng, t)
 private fun c(key: String, photos: List<PhotoPoint>, guess: TravelGuess, region: ApproxRegion = ApproxRegion.KOREA_NEARBY) =
  TravelCandidate(key, photos, photos[0].lat, photos[0].lng, region, guess, "", null)

 @Test fun overseasReviewCountsAsTripButKoreanReviewIsAsked() {
  val abroad = c("a", (1L..5L).map { p(it, it * 1000, 16.0, 108.2) }, TravelGuess.REVIEW, ApproxRegion.OTHER_OR_UNKNOWN)
  val dayTrip = c("b", (1L..12L).map { p(it, it * 1000) }, TravelGuess.REVIEW)
  assertEquals(true, Trips.verdict(abroad, emptyMap()))
  assertNull(Trips.verdict(dayTrip, emptyMap()))
  assertEquals(listOf("b"), Trips.toAsk(listOf(abroad, dayTrip), emptyMap()).map { it.key })
  assertEquals(false, Trips.verdict(abroad, mapOf("a" to false)))
 }

 @Test fun backToBackCandidatesMergeIntoOneTrip() {
  val first = c("1", listOf(p(1, 10 * day), p(2, 11 * day)), TravelGuess.LIKELY_TRIP)
  val second = c("2", listOf(p(3, 12 * day), p(4, 13 * day)), TravelGuess.LIKELY_TRIP)
  val later = c("3", listOf(p(5, 40 * day), p(6, 41 * day)), TravelGuess.LIKELY_TRIP)
  val routine = c("4", listOf(p(7, 20 * day)), TravelGuess.LIKELY_ROUTINE)
  val trips = Trips.build(listOf(first, second, later, routine), emptyMap())
  assertEquals(2, trips.size)
  assertEquals("3", trips[0].key)
  assertEquals(4, trips[1].photos.size)
 }

 @Test fun daysAreNumberedFromTheFirstDay() {
  val trip = Trip("t", listOf(p(1, 10 * day + 3600_000), p(2, 10 * day + 7200_000), p(3, 12 * day + 1000)), 0.0, 0.0, false)
  val days = Trips.days(trip, utc)
  assertEquals(listOf(1, 3), days.map { it.dayNumber })
  assertEquals(2, days[0].photos.size)
  assertEquals(3, Trips.dayCount(trip, utc))
 }
}
