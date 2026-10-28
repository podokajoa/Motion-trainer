package com.example.travelfootprints
import org.junit.Assert.*
import org.junit.Test
class ScanMergeTest {
 private fun p(id: Long) = PhotoPoint(id, "content://media/$id", 37.0, 127.0, 1L)
 @Test fun noFailuresReplacesCache() {
  assertEquals(listOf(p(2)), ScanMerge.keepUnreadable(listOf(p(1), p(2)), listOf(p(2)), emptySet()))
 }
 @Test fun unreadablePhotoKeepsOldPoint() {
  val merged = ScanMerge.keepUnreadable(listOf(p(1), p(2)), listOf(p(2)), setOf(1L))
  assertEquals(setOf(1L, 2L), merged.map { it.id }.toSet())
 }
 @Test fun allReadsFailedKeepsWholeCache() {
  val previous = listOf(p(1), p(2), p(3))
  assertEquals(previous, ScanMerge.keepUnreadable(previous, emptyList(), setOf(1L, 2L, 3L)))
 }
 @Test fun failedPhotoWithoutOldPointAddsNothing() {
  assertEquals(listOf(p(5)), ScanMerge.keepUnreadable(listOf(p(1)), listOf(p(5)), setOf(9L)))
 }
 @Test fun readPhotoIsNotDuplicated() {
  assertEquals(1, ScanMerge.keepUnreadable(listOf(p(1)), listOf(p(1)), setOf(1L)).size)
 }
}
