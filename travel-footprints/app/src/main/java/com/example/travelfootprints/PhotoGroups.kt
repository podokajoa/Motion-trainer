package com.example.travelfootprints

/**
 * A cautious *time-window* grouping, not a verified trip detector.
 * Never assigns country/place names or assumes every group is a vacation.
 * Photos without usable dates are intentionally excluded.
 */
data class PhotoGroup(val photos: List<PhotoPoint>) {
 val start: Long get() = photos.first().date
 val end: Long get() = photos.last().date
 val cover: PhotoPoint get() = photos.last()
}

object PhotoGroups {
 private const val GAP_MS = 3L * 24 * 60 * 60 * 1000
 private const val MAX_SPAN_MS = 21L * 24 * 60 * 60 * 1000

 fun byTime(points: List<PhotoPoint>): List<PhotoGroup> {
  val dated = points.filter { it.date > 0 }.sortedWith(compareBy<PhotoPoint> { it.date }.thenBy { it.id })
  if (dated.isEmpty()) return emptyList()
  val groups = mutableListOf<PhotoGroup>()
  var current = mutableListOf(dated.first())
  for (photo in dated.drop(1)) {
   if (photo.date - current.last().date > GAP_MS || photo.date - current.first().date > MAX_SPAN_MS) {
    groups.add(PhotoGroup(current))
    current = mutableListOf()
   }
   current.add(photo)
  }
  groups.add(PhotoGroup(current))
  return groups.asReversed()
 }
}
