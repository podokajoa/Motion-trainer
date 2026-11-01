package com.example.travelfootprints

import org.junit.Assert.*
import org.junit.Test

class PlaceNamesTest {
 @Test fun koreanMetropolitanCityIsShortened() {
  assertEquals("서울 강남구", PlaceNames.label("KR", "대한민국", "서울특별시", null, null, "강남구", "역삼동"))
  assertEquals("서울 강남구", PlaceNames.label("KR", "대한민국", "서울특별시", "서울특별시", null, "강남구", null))
 }

 @Test fun koreanProvinceUsesCity() {
  assertEquals("수원시 영통구", PlaceNames.label("KR", "대한민국", "경기도", "수원시", null, "영통구", null))
  assertEquals("제주 애월읍", PlaceNames.label("KR", "대한민국", "제주특별자치도", null, null, "애월읍", null))
 }

 @Test fun foreignPlaceShowsCountryAndCity() {
  assertEquals("일본 · 오사카시", PlaceNames.label("JP", "일본", "오사카부", "오사카시", null, null, null))
 }

 @Test fun emptyAddressGivesNull() {
  assertNull(PlaceNames.label("KR", null, null, null, null, null, null))
 }

 @Test fun keyRoundsToAboutOneKilometre() {
  assertEquals("geo:37.57,126.98", PlaceNames.key(37.5665, 126.9780))
 }
}
