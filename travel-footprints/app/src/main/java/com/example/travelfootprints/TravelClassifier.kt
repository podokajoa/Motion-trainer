package com.example.travelfootprints

import kotlin.math.*

enum class TravelGuess { LIKELY_TRIP, REVIEW, LIKELY_ROUTINE }
enum class ApproxRegion { KOREA_NEARBY, OTHER_OR_UNKNOWN }

data class TravelCandidate(
 val key: String,
 val photos: List<PhotoPoint>,
 val latitude: Double,
 val longitude: Double,
 val region: ApproxRegion,
 val guess: TravelGuess,
 val reason: String,
 val distanceFromRoutineKm: Int?
) {
 val firstDate: Long get() = photos.first().date
 val lastDate: Long get() = photos.last().date
 val dayCount: Int get() = ((lastDate / DAY_MS) - (firstDate / DAY_MS) + 1).toInt()
 val cover: PhotoPoint get() = photos.last()
}

private const val DAY_MS = 86_400_000L

/**
 * Local-only, deliberately conservative heuristics. It is NOT geocoding, AI,
 * proof of travel, or a precise national boundary. Never uploads coordinates.
 */
object TravelClassifier {
 fun koreaNearby(lat: Double, lng: Double): Boolean =
  lat in 33.0..38.65 && lng in 124.5..132.1

 fun distanceKm(aLat:Double,aLng:Double,bLat:Double,bLng:Double):Double {
  val dLat=Math.toRadians(bLat-aLat)
  val dLng=Math.toRadians(bLng-aLng)
  val h=sin(dLat/2).pow(2.0)+cos(Math.toRadians(aLat))*cos(Math.toRadians(bLat))*sin(dLng/2).pow(2.0)
  return 6371.0 * 2 * atan2(sqrt(h),sqrt((1-h).coerceAtLeast(0.0)))
 }

 fun classify(points: List<PhotoPoint>): List<TravelCandidate> {
  val valid=points.filter { it.date>0 && it.lat.isFinite() && it.lng.isFinite() && it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }
   .sortedWith(compareBy<PhotoPoint> { it.date }.thenBy { it.id })
  if(valid.isEmpty()) return emptyList()
  val routineAreas=inferRoutineAreas(valid)
  val routine=routineAreas.firstOrNull()
  val groups=mutableListOf<List<PhotoPoint>>()
  var current=mutableListOf<PhotoPoint>()
  for(p in valid) {
   if(current.isNotEmpty() && (p.date-current.last().date>36*60*60*1000L ||
       p.date-current.first().date>14*DAY_MS ||
       distanceKm(p.lat,p.lng,current.first().lat,current.first().lng)>80.0)) {
    groups.add(current); current=mutableListOf()
   }
   current.add(p)
  }
  if(current.isNotEmpty()) groups.add(current)
  return groups.asReversed().map { group ->
   val lat=group.map { it.lat }.sorted()[group.size/2]
   val lng=group.map { it.lng }.sorted()[group.size/2]
   val region=if(koreaNearby(lat,lng)) ApproxRegion.KOREA_NEARBY else ApproxRegion.OTHER_OR_UNKNOWN
   val distance= routine?.let { distanceKm(lat,lng,it.first,it.second).roundToInt() }
   val repeatedArea=routineAreas.any { distanceKm(lat,lng,it.first,it.second)<20.0 }
   val days=(group.last().date/DAY_MS-group.first().date/DAY_MS+1).toInt()
   val guess=when {
    region!=ApproxRegion.KOREA_NEARBY -> TravelGuess.REVIEW
    routine==null -> TravelGuess.REVIEW
    repeatedArea -> TravelGuess.LIKELY_ROUTINE
    distance!=null && distance>=70 && days>=2 && group.size>=12 -> TravelGuess.LIKELY_TRIP
    distance!=null && distance<20 -> TravelGuess.LIKELY_ROUTINE
    else -> TravelGuess.REVIEW
   }
   val reason=when {
    region!=ApproxRegion.KOREA_NEARBY -> "국내 여부를 정확히 확인할 수 없는 위치"
    routine==null -> "반복 생활권을 추정할 만큼 장기간 기록이 부족함"
    guess==TravelGuess.LIKELY_TRIP -> "평소 활동권에서 약 ${distance}km · ${days}일 · 사진 ${group.size}장"
    guess==TravelGuess.LIKELY_ROUTINE && repeatedArea -> "여러 날짜에 반복 방문한 장소 (여행 여부 직접 확인)"
    guess==TravelGuess.LIKELY_ROUTINE -> "추정 생활권에서 약 ${distance}km 이내"
    else -> "생활권에서 약 ${distance}km · ${days}일 · 사진 ${group.size}장, 여행 확인 필요"
   }
   // A stable ID across yearly filters and rescan, assuming the MediaStore ID remains stable.
   TravelCandidate("${group.first().id}:${group.first().date}",group,lat,lng,region,guess,reason,distance)
  }
 }

 private fun inferRoutineAreas(points:List<PhotoPoint>):List<Pair<Double,Double>> {
  val cells=points.filter { koreaNearby(it.lat,it.lng) }
   .groupBy { Pair(floor(it.lat*10).toInt(),floor(it.lng*10).toInt()) }
  // Count distinct days, not photo volume: 300 holiday photos are not a routine.
  // Retain several recurrent areas (e.g. home + workplace), ordered by frequency.
  return cells.mapNotNull { (cell,photos) ->
   val dates=photos.map { it.date/DAY_MS }.distinct().sorted()
   if(dates.size<10 || dates.last()-dates.first()<45) null
   else Pair(Pair((cell.first+0.5)/10.0,(cell.second+0.5)/10.0),dates.size)
  }.sortedByDescending { it.second }.take(8).map { it.first }
 }
}
