package com.example.travelfootprints

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoGroupsTest {
 private fun point(id: Long, day: Long) = PhotoPoint(id,"content://photo/$id",37.0,127.0,day * 86_400_000L)

 @Test fun nearDatesGroupedAndLargeGapSplit() {
  val groups = PhotoGroups.byTime(listOf(point(4,12),point(2,2),point(3,11),point(1,1)))
  assertEquals(2,groups.size)
  assertEquals(listOf(3L,4L),groups[0].photos.map { it.id })
  assertEquals(listOf(1L,2L),groups[1].photos.map { it.id })
 }

 @Test fun undatedExcludedAndLongSpanCapped() {
  val p = (1L..31L).map { point(it,it) } + point(100,0)
  val groups = PhotoGroups.byTime(p)
  assertEquals(2,groups.size)
  assertEquals(31,groups.sumOf { it.photos.size })
 }
}
