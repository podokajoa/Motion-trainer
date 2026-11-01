package com.example.travelfootprints

import org.json.JSONObject

/** The three questions that help 미나 remember; the answers become the page text, in their own words. */
data class DayNote(val a: String = "", val b: String = "", val c: String = "") {
 fun isEmpty() = a.isBlank() && b.isBlank() && c.isBlank()
 fun text(): String = listOf(a, b, c).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
}

/** What 미나 chose for one trip: starred photos and the day notes. */
class TripBook(val stars: MutableSet<Long> = linkedSetOf(), val notes: MutableMap<Int, DayNote> = linkedMapOf())

enum class PageKind { COVER, DAY, MORE }

data class BookPage(val kind: PageKind, val heading: String, val sub: String, val hero: PhotoPoint?, val small: List<PhotoPoint>, val text: String)

object Book {
 const val AUTO_PICK = 3

 /** Starred photos of the day in time order; with no stars anywhere, a few spread-out photos so a first PDF is never empty. */
 fun picks(day: TripDay, stars: Set<Long>, autoWhenNone: Boolean): List<PhotoPoint> {
  val ph = day.photos.sortedBy { it.date }
  val starred = ph.filter { it.id in stars }
  if (starred.isNotEmpty() || !autoWhenNone || ph.isEmpty()) return starred
  val n = minOf(AUTO_PICK, ph.size)
  return (0 until n).map { ph[(((it + 0.5) * ph.size / n).toInt()).coerceAtMost(ph.size - 1)] }
 }

 /**
  * One cover, then one page per day (big photo, text, up to two small photos). A day with more
  * than three photos continues on photo pages of four. A day with nothing chosen and no text is skipped.
  */
 fun pages(title: String, dateText: String, summary: String, coverPhoto: PhotoPoint?, days: List<TripDay>,
           dayHeading: (TripDay) -> String, dayPlaces: (TripDay) -> String, book: TripBook): List<BookPage> {
  val none = days.none { d -> d.photos.any { it.id in book.stars } }
  val out = ArrayList<BookPage>()
  out.add(BookPage(PageKind.COVER, title, "$dateText · $summary", coverPhoto, emptyList(), ""))
  days.forEach { d ->
   val picks = picks(d, book.stars, none)
   val text = book.notes[d.dayNumber]?.text().orEmpty()
   if (picks.isEmpty() && text.isBlank()) return@forEach
   val heading = dayHeading(d); val places = dayPlaces(d)
   out.add(BookPage(PageKind.DAY, heading, places, picks.firstOrNull(), picks.drop(1).take(2), text))
   picks.drop(3).chunked(4).forEach { out.add(BookPage(PageKind.MORE, heading, places, null, it, "")) }
  }
  return out
 }

 fun toJson(all: Map<String, TripBook>): String {
  val root = JSONObject()
  all.forEach { (key, b) ->
   val o = JSONObject()
   o.put("stars", org.json.JSONArray(b.stars.toList()))
   val n = JSONObject()
   b.notes.forEach { (day, note) -> if (!note.isEmpty()) n.put(day.toString(), JSONObject().put("a", note.a).put("b", note.b).put("c", note.c)) }
   o.put("notes", n)
   root.put(key, o)
  }
  return root.toString()
 }

 fun fromJson(text: String): MutableMap<String, TripBook> {
  val out = linkedMapOf<String, TripBook>()
  val root = JSONObject(text)
  root.keys().forEach { key ->
   val o = root.getJSONObject(key); val b = TripBook()
   o.optJSONArray("stars")?.let { a -> for (i in 0 until a.length()) b.stars.add(a.getLong(i)) }
   o.optJSONObject("notes")?.let { n -> n.keys().forEach { d -> n.getJSONObject(d).let { x -> b.notes[d.toInt()] = DayNote(x.optString("a"), x.optString("b"), x.optString("c")) } } }
   out[key] = b
  }
  return out
 }
}
