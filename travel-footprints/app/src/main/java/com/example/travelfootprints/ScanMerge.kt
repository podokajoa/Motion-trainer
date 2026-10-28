package com.example.travelfootprints

object ScanMerge {
 /**
  * A photo that could not be read during a rescan keeps its previously saved point,
  * so a revoked permission or a flaky read never wipes the existing GPS cache.
  * Photos that were read fine replace their old point; deleted photos drop out.
  */
 fun keepUnreadable(previous: List<PhotoPoint>, found: List<PhotoPoint>, failedIds: Set<Long>): List<PhotoPoint> {
  if (failedIds.isEmpty()) return found
  val foundIds = found.mapTo(HashSet()) { it.id }
  return found + previous.filter { it.id in failedIds && it.id !in foundIds }
 }
}
