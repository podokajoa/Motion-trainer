package com.example.travelfootprints

import java.util.Calendar
import java.util.TimeZone

/** One trip in the album: one or more classifier candidates merged together. */
data class Trip(val key: String, val photos: List<PhotoPoint>, val lat: Double, val lng: Double, val abroad: Boolean) {
 val start: Long get() = photos.first().date
 val end: Long get() = photos.last().date
 /** A photo from the middle of the trip tends to show the place, not the departure gate. */
 val cover: PhotoPoint get() = photos[photos.size / 2]
}

data class TripDay(val dayNumber: Int, val dayStart: Long, val photos: List<PhotoPoint>)

object Trips {
 private const val DAY_MS = 86_400_000L

 /** true = trip, false = everyday, null = ask the user. */
 fun verdict(c: TravelCandidate, decisions: Map<String, Boolean>): Boolean? {
  decisions[c.key]?.let { return it }
  return when (c.guess) {
   TravelGuess.LIKELY_TRIP -> true
   TravelGuess.LIKELY_ROUTINE -> false
   // Photos taken abroad are almost always a trip for someone living in Korea.
   TravelGuess.REVIEW -> if (c.region != ApproxRegion.KOREA_NEARBY && c.photos.size >= 3) true else null
  }
 }

 /** Candidates worth asking about: undecided and with enough photos to matter. */
 fun toAsk(candidates: List<TravelCandidate>, decisions: Map<String, Boolean>): List<TravelCandidate> =
  candidates.filter { verdict(it, decisions) == null && it.photos.size >= 10 }

 /**
  * Trip candidates sorted newest first. Back-to-back candidates (the classifier splits
  * on a 36 h photo gap or 80 km move) are merged when they are at most 2 days apart
  * and on the same side of the border, so one holiday reads as one trip.
  */
 fun build(candidates: List<TravelCandidate>, decisions: Map<String, Boolean>): List<Trip> {
  val chosen = candidates.filter { verdict(it, decisions) == true }.sortedBy { it.firstDate }
  val merged = mutableListOf<MutableList<TravelCandidate>>()
  for (c in chosen) {
   val last = merged.lastOrNull()?.last()
   if (last != null && c.firstDate - last.lastDate <= 2 * DAY_MS &&
    (c.region == ApproxRegion.KOREA_NEARBY) == (last.region == ApproxRegion.KOREA_NEARBY)) merged.last().add(c)
   else merged.add(mutableListOf(c))
  }
  return merged.map { group ->
   val photos = group.flatMap { it.photos }.sortedWith(compareBy<PhotoPoint> { it.date }.thenBy { it.id })
   val lat = photos.map { it.lat }.sorted()[photos.size / 2]
   val lng = photos.map { it.lng }.sorted()[photos.size / 2]
   Trip(group.first().key, photos, lat, lng, group.first().region != ApproxRegion.KOREA_NEARBY)
  }.asReversed()
 }

 fun days(trip: Trip, zone: TimeZone = TimeZone.getDefault()): List<TripDay> {
  val cal = Calendar.getInstance(zone)
  fun dayStart(t: Long): Long {
   cal.timeInMillis = t
   cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
   return cal.timeInMillis
  }
  val first = dayStart(trip.start)
  return trip.photos.groupBy { dayStart(it.date) }.toSortedMap().map { (start, photos) ->
   TripDay(((start - first + DAY_MS / 2) / DAY_MS).toInt() + 1, start, photos)
  }
 }

 fun dayCount(trip: Trip, zone: TimeZone = TimeZone.getDefault()): Int =
  days(trip, zone).lastOrNull()?.dayNumber ?: 1
}
