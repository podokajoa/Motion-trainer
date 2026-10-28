package com.example.travelfootprints

/**
 * Offline, deliberately coarse bounding-box hints. These are NOT geocoding,
 * administrative boundaries, or evidence that the user traveled there.
 * No GPS coordinates are sent to a server by this helper.
 */
object CoarsePlaceLabel {
 fun forPoint(lat: Double, lng: Double): String? = when {
  lat in 33.0..39.5 && lng in 124.0..131.5 -> "대한민국 부근"
  lat in 13.0..14.0 && lng in 144.5..145.1 -> "괌 부근"
  lat in 21.8..25.5 && lng in 119.0..122.2 -> "대만 부근"
  lat in 30.0..46.5 && lng in 129.0..146.0 -> "일본 부근"
  lat in 24.0..30.0 && lng in 123.0..130.0 -> "일본 오키나와 부근"
  lat in 1.1..1.5 && lng in 103.5..104.1 -> "싱가포르 부근"
  else -> null
 }
}
