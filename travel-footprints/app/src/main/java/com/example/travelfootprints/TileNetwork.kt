package com.example.travelfootprints

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File

/** Viewport-only OSM requests. No offline packs or tile prefetch. */
object TileNetwork {
 fun configure(context: Context) {
  val client=OkHttpClient.Builder()
   .cache(Cache(File(context.cacheDir,"osm-http"),128L*1024*1024))
   .addInterceptor { chain ->
    chain.proceed(chain.request().newBuilder()
     .header("User-Agent","TravelFootprints/0.14 (Android; com.example.travelfootprints)")
     .build())
   }.build()
  // OkHttp honors Cache-Control, Expires, ETag and Last-Modified from OSM.
  HttpRequestUtil.setOkHttpClient(client)
 }
 fun style(): String = """
 {"version":8,"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
 "sources":{"osm":{"type":"raster",
 "tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
 "tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},
 "layers":[{"id":"paper","type":"background","paint":{"background-color":"#FAF8F1"}},
 {"id":"osm","type":"raster","source":"osm","paint":{"raster-saturation":-0.35,"raster-opacity":0.9}}]}
 """.trimIndent()
}
