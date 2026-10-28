package com.example.travelfootprints

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File

/** Viewport-only map requests. No offline packs or tile prefetch. */
object TileNetwork {
 fun configure(context: Context) {
  val client=OkHttpClient.Builder()
   .cache(Cache(File(context.cacheDir,"map-http"),128L*1024*1024))
   .addInterceptor { chain ->
    chain.proceed(chain.request().newBuilder()
     .header("User-Agent","TravelFootprints/0.16 (Android; com.example.travelfootprints)")
     .build())
   }.build()
  // OkHttp honors Cache-Control, Expires, ETag and Last-Modified from the tile server.
  HttpRequestUtil.setOkHttpClient(client)
 }
 /** OpenFreeMap vector tiles (no API key). Labels are switched to Korean in [koreanize]. */
 const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

 /** Rough outline of South Korea incl. Jeju/Ulleung/Dokdo, excluding Tsushima and Kyushu. */
 private val koreaOutline: Polygon = Polygon.fromLngLats(listOf(listOf(
  Point.fromLngLat(124.5,33.0), Point.fromLngLat(127.2,33.0), Point.fromLngLat(128.7,34.6),
  Point.fromLngLat(129.4,34.95), Point.fromLngLat(129.8,35.5), Point.fromLngLat(129.8,36.8),
  Point.fromLngLat(132.0,36.8), Point.fromLngLat(132.0,38.7), Point.fromLngLat(124.5,38.7),
  Point.fromLngLat(124.5,33.0))))

 /** Dense small labels that crowd the map when zoomed into Korean cities. */
 private val hiddenLayers = setOf("label_other","highway-name-path","highway-name-minor","waterway_line_label")

 /**
  * Recolor the light base map to the 트래블 파스텔 palette and pick label languages:
  * Korean inside Korea, Korean country names everywhere, and English/latin names
  * for other foreign places (OSM's name:ko for Chinese/Japanese streets and towns
  * is often a raw transliteration). Road number shields keep their "ref" text.
  */
 fun koreanize(style: Style) {
  val koreanName = Expression.coalesce(Expression.get("name:ko"), Expression.get("name"))
  val foreignName = Expression.coalesce(Expression.get("name_en"), Expression.get("name:latin"), Expression.get("name"))
  val placeName = Expression.switchCase(Expression.within(koreaOutline), koreanName, foreignName)
  val countryName = Expression.coalesce(Expression.get("name:ko"), Expression.get("name_en"), Expression.get("name"))
  style.layers.forEach { layer ->
   when {
    layer is SymbolLayer && layer.id in hiddenLayers -> layer.setProperties(PropertyFactory.visibility(Property.NONE))
    layer is SymbolLayer && layer.id.startsWith("label_country") -> layer.setProperties(PropertyFactory.textField(countryName))
    layer is SymbolLayer && !layer.id.contains("shield") -> layer.setProperties(PropertyFactory.textField(placeName), PropertyFactory.textPadding(8f))
    layer is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor("#FAF8F1"))
    layer is FillLayer && layer.id == "water" -> layer.setProperties(PropertyFactory.fillColor("#CFE3EC"))
    layer is FillLayer && layer.id == "park" -> layer.setProperties(PropertyFactory.fillColor("#E3EFDD"))
    layer is LineLayer && layer.id == "waterway" -> layer.setProperties(PropertyFactory.lineColor("#BFD9E6"))
   }
  }
 }
}
