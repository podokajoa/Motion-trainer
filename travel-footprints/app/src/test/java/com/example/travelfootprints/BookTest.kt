package com.example.travelfootprints

import org.junit.Assert.*
import org.junit.Test

class BookTest {
 private fun p(id: Long) = PhotoPoint(id, "content://t/$id", 0.0, 0.0, id * 1000)
 private val day1 = TripDay(1, 0, (1L..10L).map { p(it) })
 private val day2 = TripDay(2, 0, (11L..12L).map { p(it) })
 private fun pages(book: TripBook, days: List<TripDay> = listOf(day1, day2)) =
  Book.pages("울릉도", "2025.08", "2일", p(5), days, { "DAY ${it.dayNumber}" }, { "" }, book)

 @Test fun withoutStarsAFewPhotosPerDayAreChosenAutomatically() {
  val pg = pages(TripBook())
  assertEquals(listOf(PageKind.COVER, PageKind.DAY, PageKind.DAY), pg.map { it.kind })
  assertNotNull(pg[1].hero)
  assertEquals(2, pg[1].small.size)
 }

 @Test fun starredPhotosLeadAndExtraPhotosGoToMorePages() {
  val b = TripBook(stars = linkedSetOf(1, 2, 3, 4, 5, 6, 7, 8))
  val pg = pages(b, listOf(day1))
  assertEquals(listOf(PageKind.COVER, PageKind.DAY, PageKind.MORE, PageKind.MORE), pg.map { it.kind })
  assertEquals(1L, pg[1].hero?.id)
  assertEquals(listOf(2L, 3L), pg[1].small.map { it.id })
  assertEquals(listOf(4L, 5L, 6L, 7L), pg[2].small.map { it.id })
  assertEquals(listOf(8L), pg[3].small.map { it.id })
 }

 @Test fun textOnlyDayStillGetsAPageAndEmptyDayIsSkipped() {
  val b = TripBook(stars = linkedSetOf(1), notes = linkedMapOf(2 to DayNote(a = "바람이 달랐다")))
  val pg = pages(b)
  assertEquals(3, pg.size)
  assertNull(pg[2].hero)
  assertEquals("바람이 달랐다", pg[2].text)
 }

 @Test fun jsonRoundTrip() {
  val b = TripBook(stars = linkedSetOf(3, 9), notes = linkedMapOf(1 to DayNote("가", "나", "다")))
  val back = Book.fromJson(Book.toJson(mapOf("t" to b)))["t"]!!
  assertEquals(setOf(3L, 9L), back.stars)
  assertEquals("가\n\n나\n\n다", back.notes[1]!!.text())
 }
}
