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
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import java.io.File

/** Viewport-only map requests. No offline packs or tile prefetch. */
object TileNetwork {
 fun configure(context: Context) {
  val client=OkHttpClient.Builder()
   .cache(Cache(File(context.cacheDir,"map-http"),128L*1024*1024))
   .addInterceptor { chain ->
    chain.proceed(chain.request().newBuilder()
     .header("User-Agent","TravelFootprints/0.15 (Android; com.example.travelfootprints)")
     .build())
   }.build()
  // OkHttp honors Cache-Control, Expires, ETag and Last-Modified from the tile server.
  HttpRequestUtil.setOkHttpClient(client)
 }
 /** OpenFreeMap vector tiles (no API key). Labels are switched to Korean in [koreanize]. */
 const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

 /**
  * Recolor the light base map to the 트래블 파스텔 palette and show place names in
  * Korean where OpenStreetMap has a name:ko, falling back to the local name.
  * Road number shields keep their original "ref" text.
  */
 fun koreanize(style: Style) {
  val koreanName = Expression.coalesce(Expression.get("name:ko"), Expression.get("name"))
  style.layers.forEach { layer ->
   when {
    layer is SymbolLayer && !layer.id.contains("shield") -> layer.setProperties(PropertyFactory.textField(koreanName))
    layer is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor("#FAF8F1"))
    layer is FillLayer && layer.id == "water" -> layer.setProperties(PropertyFactory.fillColor("#CFE3EC"))
    layer is FillLayer && layer.id == "park" -> layer.setProperties(PropertyFactory.fillColor("#E3EFDD"))
    layer is LineLayer && layer.id == "waterway" -> layer.setProperties(PropertyFactory.lineColor("#BFD9E6"))
   }
  }
 }
}
