package com.example.travelfootprints

import org.junit.Assert.*
import org.junit.Test

class CoarsePlaceLabelTest {
 @Test fun offlineLabelsForKnownApproximateCoordinates() {
  assertEquals("대한민국 부근",CoarsePlaceLabel.forPoint(37.5665,126.9780))
  assertEquals("일본 부근",CoarsePlaceLabel.forPoint(35.6762,139.6503))
  assertEquals("괌 부근",CoarsePlaceLabel.forPoint(13.4443,144.7937))
  assertEquals("대만 부근",CoarsePlaceLabel.forPoint(25.0330,121.5654))
 }

 @Test fun unknownCoordinatesStayUnknownInsteadOfInventingPlaces() {
  assertNull(CoarsePlaceLabel.forPoint(0.0,0.0))
  assertNull(CoarsePlaceLabel.forPoint(-33.8688,151.2093))
 }
}
