package com.example.travelfootprints
import java.util.Calendar
import java.util.TimeZone
object PhotoYears {
 fun yearOf(date: Long, zone: TimeZone = TimeZone.getDefault()): Int? {
  if (date <= 0) return null
  return Calendar.getInstance(zone).apply { timeInMillis=date }.get(Calendar.YEAR)
 }
}
