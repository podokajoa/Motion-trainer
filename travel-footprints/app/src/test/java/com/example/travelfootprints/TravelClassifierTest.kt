package com.example.travelfootprints

import org.junit.Assert.*
import org.junit.Test

class TravelClassifierTest {
 private fun p(id:Long,day:Long,lat:Double,lng:Double)=PhotoPoint(id,"content://test/$id",lat,lng,day*86_400_000L)

 @Test fun routineAreaIsNotInferredFromOneHoliday() {
  val photos=(1L..20L).map { p(it,it,37.76,128.88) }
  assertTrue(TravelClassifier.classify(photos).all { it.guess==TravelGuess.REVIEW })
 }

 @Test fun nearbyTravelCanBeLikelyWhenRoutineIsEstablished() {
  val routine=(1L..15L).map { p(it,it*5,37.51,127.02) }
  val trip=(100L..119L).map { p(it,100+((it-100)/10),37.75,128.88) }
  val candidates=TravelClassifier.classify(routine+trip)
  assertTrue(candidates.any { it.region==ApproxRegion.KOREA_NEARBY && it.guess==TravelGuess.LIKELY_TRIP })
  assertTrue(candidates.any { it.guess==TravelGuess.LIKELY_ROUTINE })
 }

 @Test fun dayTripNeedsReviewAndUndatedIsIgnored() {
  val routine=(1L..15L).map { p(it,it*5,37.51,127.02) }
  val dayTrip=(100L..119L).map { p(it,100,37.75,128.88) }
  val results=TravelClassifier.classify(routine+dayTrip+p(999,0,37.75,128.88))
  assertTrue(results.any { it.guess==TravelGuess.REVIEW && it.photos.size==20 })
  assertEquals(35,results.sumOf { it.photos.size })
 }

 @Test fun distantRepeatedWorkplaceIsNotAutomaticallyCalledTravel() {
  val home=(1L..15L).map { p(it,it*5,37.51,127.02) }
  val workplace=(100L..111L).map { p(it,it*6,37.76,128.88) }
  val results=TravelClassifier.classify(home+workplace)
  assertTrue(results.filter { it.photos.any { photo -> photo.id>=100 } }
   .all { it.guess!=TravelGuess.LIKELY_TRIP })
 }

 @Test fun largeSingleHolidayIsNotInferredAsRoutineArea() {
  val home=(1L..15L).map { p(it,it*5,37.51,127.02) }
  val holiday=(100L..129L).map { p(it,100+((it-100)/10),37.76,128.88) }
  assertTrue(TravelClassifier.classify(home+holiday).any { it.guess==TravelGuess.LIKELY_TRIP })
 }
}
