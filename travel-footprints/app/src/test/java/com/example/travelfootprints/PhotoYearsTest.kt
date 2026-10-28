package com.example.travelfootprints
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone
class PhotoYearsTest {
 @Test fun missingDateStaysOutOfYearFilters() { assertNull(PhotoYears.yearOf(0)); assertNull(PhotoYears.yearOf(-1)) }
 @Test fun seoulNewYearBoundary() {
  val zone=TimeZone.getTimeZone("Asia/Seoul")
  // 2025-12-31T15:00:00Z is 2026-01-01T00:00:00+09:00
  assertEquals(2025,PhotoYears.yearOf(1767193199999L,zone))
  assertEquals(2026,PhotoYears.yearOf(1767193200000L,zone))
 }
}
