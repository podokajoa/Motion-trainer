package com.example.travelfootprints

import java.time.LocalDate
import java.time.ZoneOffset

/** Hand-authored illustrative points. Never reads gallery photos or a user location. */
object DemoPoints {
 private data class Area(val name:String,val lat:Double,val lng:Double,val year:Int,val month:Int,val day:Int,val count:Int)
 private val areas=listOf(
  Area("대한민국 · 서울 (가상)",37.5665,126.9780,2026,10,12,5),
  Area("일본 · 오사카 (가상)",34.6937,135.5023,2026,5,9,5),
  Area("대한민국 · 부산 (가상)",35.1796,129.0756,2025,9,18,4),
  Area("미국 · 괌 (가상)",13.4443,144.7937,2025,7,22,4)
 )
 fun generate():List<PhotoPoint> = buildList {
  var id=900000L
  for(area in areas) {
   val base=LocalDate.of(area.year,area.month,area.day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
   repeat(area.count) { offset ->
    // Spread points by a few streets so clustering and unclustered dots are testable.
    val d=(offset-2)*0.005
    add(PhotoPoint(id++,"content://com.example.travelfootprints.demo/illustration/$id",
     area.lat+d,area.lng-d,base+offset*4L*60L*60L*1000L))
   }
  }
 }
 fun exampleName(lat:Double,lng:Double):String = areas.minByOrNull {
  TravelClassifier.distanceKm(lat,lng,it.lat,it.lng)
 }?.name ?: "예시 위치"
}
