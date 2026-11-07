package com.example.travelfootprints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TripEditsTest {
 private val utc = TimeZone.getTimeZone("UTC")
 private val day = 86_400_000L
 private fun p(id: Long, t: Long) = PhotoPoint(id, "content://t/$id", 37.5, 130.8, t)
 // days 16-18 and 21-22 stuck together as one trip
 private val trip = Trip("t", listOf(p(1, 16 * day + 1000), p(2, 17 * day + 1000), p(3, 18 * day + 1000), p(4, 21 * day + 1000), p(5, 22 * day + 1000)), 37.5, 130.8, false)

 @Test fun noEditsLeavesTheTripAlone() {
  val r = TripEditing.apply(listOf(trip), TripEdits(), utc)
  assertEquals(listOf("t"), r.shown.map { it.key })
  assertTrue(r.hidden.isEmpty())
 }

 @Test fun cutSplitsIntoTwoTripsNewestFirst() {
  val r = TripEditing.apply(listOf(trip), TripEdits(cuts = linkedSetOf(19 * day)), utc)
  assertEquals(listOf("t@${19 * day}", "t"), r.shown.map { it.key })
  assertEquals(listOf(4L, 5L), r.shown[0].photos.map { it.id })
  assertEquals(listOf(1L, 2L, 3L), r.shown[1].photos.map { it.id })
  assertEquals(19 * day, r.shown[0].cutAt)
  assertNull(r.shown[1].cutAt)
  assertEquals("t", r.shown[0].baseKey)
 }

 @Test fun cutOnTheFirstDayOrOutsideTheTripDoesNothing() {
  val r = TripEditing.apply(listOf(trip), TripEdits(cuts = linkedSetOf(16 * day, 30 * day, 2 * day)), utc)
  assertEquals(1, r.shown.size)
 }

 @Test fun twoCutsMakeThreeTrips() {
  val r = TripEditing.apply(listOf(trip), TripEdits(cuts = linkedSetOf(17 * day, 21 * day)), utc)
  assertEquals(3, r.shown.size)
  assertEquals(listOf(listOf(4L, 5L), listOf(2L, 3L), listOf(1L)), r.shown.map { t -> t.photos.map { it.id } })
 }

 @Test fun hiddenTripsAreSetAside() {
  val r = TripEditing.apply(listOf(trip), TripEdits(cuts = linkedSetOf(19 * day), hidden = linkedSetOf("t")), utc)
  assertEquals(listOf("t@${19 * day}"), r.shown.map { it.key })
  assertEquals(listOf("t"), r.hidden.map { it.key })
 }

 @Test fun titleAndCoverAreLaidOverTheTrip() {
  val r = TripEditing.apply(listOf(trip), TripEdits(titles = linkedMapOf("t" to "울릉도"), covers = linkedMapOf("t" to 3L)), utc)
  assertEquals("울릉도", r.shown[0].title)
  assertEquals(3L, r.shown[0].cover.id)
 }

 @Test fun aCoverThatIsNoLongerInTheTripFallsBackToTheMiddlePhoto() {
  val r = TripEditing.apply(listOf(trip), TripEdits(covers = linkedMapOf("t" to 99L)), utc)
  assertEquals(3L, r.shown[0].cover.id)
 }

 @Test fun jsonRoundTrip() {
  val e = TripEdits(linkedSetOf(5L), linkedSetOf("a"), linkedMapOf("a" to "제목"), linkedMapOf("a" to 7L))
  val back = TripEdits.fromJson(e.toJson())
  assertEquals(e.cuts, back.cuts); assertEquals(e.hidden, back.hidden); assertEquals(e.titles, back.titles); assertEquals(e.covers, back.covers)
 }
}
