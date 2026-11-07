package com.example.travelfootprints

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * What the user changed by hand on the trip list: where one trip was cut in two, hidden trips,
 * titles and cover photos. The trips themselves are worked out from the photos again on every scan,
 * so these choices are kept apart and laid over the result.
 */
class TripEdits(
 /** Local midnight of a day that starts a new trip. */
 val cuts: MutableSet<Long> = linkedSetOf(),
 val hidden: MutableSet<String> = linkedSetOf(),
 val titles: MutableMap<String, String> = linkedMapOf(),
 val covers: MutableMap<String, Long> = linkedMapOf()
) {
 fun toJson(): String {
  val o = JSONObject()
  o.put("cuts", JSONArray(cuts.toList()))
  o.put("hidden", JSONArray(hidden.toList()))
  o.put("titles", JSONObject().also { j -> titles.forEach { (k, v) -> j.put(k, v) } })
  o.put("covers", JSONObject().also { j -> covers.forEach { (k, v) -> j.put(k, v) } })
  return o.toString()
 }

 companion object {
  fun fromJson(text: String): TripEdits {
   val o = JSONObject(text)
   val e = TripEdits()
   o.optJSONArray("cuts")?.let { a -> for (i in 0 until a.length()) e.cuts.add(a.getLong(i)) }
   o.optJSONArray("hidden")?.let { a -> for (i in 0 until a.length()) e.hidden.add(a.getString(i)) }
   o.optJSONObject("titles")?.let { j -> j.keys().forEach { k -> e.titles[k] = j.getString(k) } }
   o.optJSONObject("covers")?.let { j -> j.keys().forEach { k -> e.covers[k] = j.getLong(k) } }
   return e
  }
 }
}

class EditedTrips(val shown: List<Trip>, val hidden: List<Trip>)

object TripEditing {
 fun dayStart(t: Long, zone: TimeZone = TimeZone.getDefault()): Long {
  val cal = Calendar.getInstance(zone)
  cal.timeInMillis = t
  cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
  return cal.timeInMillis
 }

 /** Cuts the trips where the user said, then applies titles and covers and sets the hidden ones aside. Keeps the newest-first order. */
 fun apply(trips: List<Trip>, edits: TripEdits, zone: TimeZone = TimeZone.getDefault()): EditedTrips {
  val cuts = edits.cuts.sorted()
  val all = trips.flatMap { t ->
   val first = dayStart(t.start, zone); val last = dayStart(t.end, zone)
   // a cut on the first day would leave nothing before it
   val inside = cuts.filter { it > first && it <= last }
   if (inside.isEmpty()) listOf(t)
   else {
    val bySegment = t.photos.groupBy { ph -> val d = dayStart(ph.date, zone); inside.count { it <= d } }.toSortedMap()
    bySegment.map { (idx, photos) ->
     val cut = if (idx == 0) null else inside[idx - 1]
     val lat = photos.map { it.lat }.sorted()[photos.size / 2]
     val lng = photos.map { it.lng }.sorted()[photos.size / 2]
     Trip(if (cut == null) t.key else "${t.key}@$cut", photos, lat, lng, t.abroad, cutAt = cut, baseKey = t.key)
    }.asReversed()
   }
  }.map { t -> t.copy(coverId = edits.covers[t.key], title = edits.titles[t.key]) }
  val (hid, shown) = all.partition { it.key in edits.hidden }
  return EditedTrips(shown, hid)
 }
}
