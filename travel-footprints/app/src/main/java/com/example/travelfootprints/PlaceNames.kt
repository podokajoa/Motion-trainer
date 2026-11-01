package com.example.travelfootprints

import java.util.Locale

/**
 * Turns a reverse-geocoded address into a short Korean label for cards,
 * e.g. "서울 강남구", "수원시 영통구", "일본 · 오사카시".
 */
object PlaceNames {
 private val shortRegions = mapOf(
  "서울특별시" to "서울", "부산광역시" to "부산", "대구광역시" to "대구", "인천광역시" to "인천",
  "광주광역시" to "광주", "대전광역시" to "대전", "울산광역시" to "울산", "세종특별자치시" to "세종",
  "경기도" to "경기", "강원도" to "강원", "강원특별자치도" to "강원",
  "충청북도" to "충북", "충청남도" to "충남", "전라북도" to "전북", "전북특별자치도" to "전북",
  "전라남도" to "전남", "경상북도" to "경북", "경상남도" to "경남",
  "제주도" to "제주", "제주특별자치도" to "제주"
 )

 fun shortRegion(admin: String?): String? = admin?.trim()?.takeIf { it.isNotEmpty() }?.let { shortRegions[it] ?: it }

 fun key(lat: Double, lng: Double): String = "geo:%.2f,%.2f".format(Locale.US, lat, lng)

 fun label(
  countryCode: String?, countryName: String?, admin: String?, locality: String?,
  subAdmin: String?, subLocality: String?, thoroughfare: String?
 ): String? {
  fun clean(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
  return if (countryCode.equals("KR", ignoreCase = true)) {
   val city = clean(locality) ?: clean(subAdmin)
   val parts = if (city != null) listOf(city, clean(subLocality) ?: clean(thoroughfare))
   else listOf(shortRegion(admin), clean(subLocality) ?: clean(thoroughfare))
   // Metropolitan cities: "서울 강남구" reads better than "서울특별시 강남구".
   val first = parts[0]?.let { shortRegions[it] ?: it }
   listOfNotNull(first, parts[1]).distinct().joinToString(" ").ifBlank { null }
  } else {
   val country = clean(countryCode)?.let { Locale("", it).getDisplayCountry(Locale.KOREAN) } ?: clean(countryName)
   val city = clean(locality) ?: clean(subAdmin) ?: clean(admin)
   listOfNotNull(country, city).distinct().joinToString(" · ").ifBlank { null }
  }
 }
}
