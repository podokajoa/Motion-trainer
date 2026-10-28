package com.example.travelfootprints

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.net.Uri
import android.graphics.Color
import android.util.Log
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import org.maplibre.android.MapLibre
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), OnMapReadyCallback {
 // Production scans the local gallery and reads the existing points.json cache.
 private val demoMode = false
 private lateinit var mapView: MapView
 private lateinit var status: TextView
 private lateinit var gallery: LinearLayout
 private lateinit var bottomScroll: ScrollView
 private lateinit var summary: TextView
 private lateinit var mapPanel: FrameLayout
 private var map: MapLibreMap? = null
 private var cameraInitialized = false
 private val placeNames = mutableMapOf<String,String>()
 private val resolvingPlaces = mutableSetOf<String>()
 private var selectedYear: Int? = null
 private var selectedRegion = "all"
 private var showRoutineCandidates = false
 private lateinit var yearsRow: LinearLayout
 private lateinit var regionRow: LinearLayout
 private val worker = Executors.newSingleThreadExecutor()
 private var scanning = false
 private val points = mutableListOf<PhotoPoint>()
 private var candidates = emptyList<TravelCandidate>()
 private val decisions = mutableMapOf<String,Boolean>()
 private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
  if (hasImageAccess() && hasLocationAccess()) scanGallery() else status.text = "필요한 권한 없음: 사진 전체 접근=${hasImageAccess()}, 위치 메타데이터 접근=${hasLocationAccess()}. 설정에서 모두 허용해줘."
 }
 private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) exportBackup(uri) }
 private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) importBackup(uri) }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  MapLibre.getInstance(this); TileNetwork.configure(this)
  selectedYear = savedInstanceState?.getInt("year", 0)?.takeIf { it != 0 }
  selectedRegion = savedInstanceState?.getString("region") ?: "all"
  val green = Color.rgb(48,111,83)
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(252,249,242)) }
  val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22),dp(19),dp(22),dp(14)) }
  header.addView(TextView(this).apply {
   text="MY TRAVEL JOURNAL"; textSize=10f; letterSpacing=0.20f
   setTextColor(Color.rgb(111,153,131)); setTypeface(null,Typeface.BOLD)
  })
  val heading = TextView(this).apply { text = "여행 발자국"; textSize = 28f; setTextColor(Color.rgb(37,72,59)); setTypeface(null,Typeface.BOLD); setPadding(0,dp(2),0,0) }
  if(demoMode) heading.text="여행 발자국 · TEST"
  summary = TextView(this).apply { text = "사진이 기억하는 나의 여행"; textSize = 12f; setTextColor(Color.rgb(104,122,110)); setPadding(0,dp(5),0,dp(15)) }
  yearsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(5),dp(4),dp(5),dp(4)); background = rounded(Color.rgb(241,241,230),25) }
  val yearScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(yearsRow) }
  regionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
  header.addView(heading); header.addView(summary); header.addView(yearScroll); header.addView(regionRow)
  root.addView(header)
  mapView = MapView(this)
  mapPanel = FrameLayout(this)
  mapPanel.addView(mapView,FrameLayout.LayoutParams(-1,-1))
  val scan = TextView(this).apply {
   text = if(demoMode) "↻ 예시 위치로 이동" else "↻ 사진 다시 분석"; textSize = 12f; setTextColor(green); gravity = Gravity.CENTER
   setPadding(dp(13),dp(10),dp(13),dp(10)); background = rounded(Color.rgb(255,252,244),24)
   elevation = dp(3).toFloat(); setOnClickListener { if(demoMode) focusPhotosOnMap() else requestScan() }
  }
  mapPanel.addView(scan,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply { setMargins(0,dp(12),dp(14),0) })
  root.addView(mapPanel,LinearLayout.LayoutParams(-1,0,1f))
  val copyright = TextView(this).apply {
   text = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"; textSize = 10f; setTextColor(green)
   setPadding(dp(14),dp(5),dp(10),dp(5))
   setOnClickListener { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }
  }
  root.addView(copyright)
  val bottom = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(15),dp(18),dp(13))
   background = rounded(Color.rgb(255,253,247),28); elevation = dp(8).toFloat()
  }
  val grip = View(this).apply { background = rounded(Color.rgb(207,207,201),8) }
  bottom.addView(grip,LinearLayout.LayoutParams(dp(34),dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
  status = TextView(this).apply { text = "사진 GPS를 지도에 기록해요"; textSize = 12f; setTextColor(Color.rgb(93,106,99)); maxLines = 3 }
  gallery = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  bottom.addView(gallery)
  bottom.addView(status,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
  bottomScroll=ScrollView(this).apply { isFillViewport=false; addView(bottom) }
  root.addView(bottomScroll,LinearLayout.LayoutParams(-1,dp(295)))
  setContentView(root)
  androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
   val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
   view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
   insets
  }
  mapView.onCreate(savedInstanceState); mapView.getMapAsync(this)
  loadDecisions()
  loadPlaceNames()
  loadCache()
  if(demoMode) installDemoPoints()
  updateYearButtons()
  updateRegionButtons()
 }
 private fun dp(value:Int):Int = (value*resources.displayMetrics.density+0.5f).toInt()
 private fun rounded(color:Int,radius:Int):GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
 private fun loadDecisions() {
  try { val o=JSONObject(openFileInput("trip_decisions.json").bufferedReader().use { it.readText() }); o.keys().forEach { k -> decisions[k]=o.getBoolean(k) } } catch (_:Exception) { }
 }
 private fun saveDecisions() {
  val o=JSONObject(); decisions.forEach { (k,v) -> o.put(k,v) }
  openFileOutput("trip_decisions.json",MODE_PRIVATE).bufferedWriter().use { it.write(o.toString()) }
 }
 private fun loadPlaceNames() {
  try {
   val obj=JSONObject(openFileInput("places.json").bufferedReader().use { it.readText() })
   obj.keys().forEach { key -> placeNames[key]=obj.getString(key) }
  } catch (_:Exception) { }
 }
 private fun savePlaceNames() {
  val obj=JSONObject(); placeNames.forEach { (key,value) -> obj.put(key,value) }
  openFileOutput("places.json",MODE_PRIVATE).bufferedWriter().use { it.write(obj.toString()) }
 }
 private fun installDemoPoints() {
  points.clear()
  points.addAll(DemoPoints.generate())
  candidates=TravelClassifier.classify(points)
  candidates.forEach { placeNames[it.key]=DemoPoints.exampleName(it.latitude,it.longitude) }
  status.text="가상 GPS 위치 ${points.size}개 · 실제 갤러리 분석 없음 · 기존 앱과 별도 설치"
  render()
 }
 private fun requestOnlinePlace(candidate:TravelCandidate) {
  if(!resolvingPlaces.add(candidate.key)) return
  // The user explicitly taps this button: ONE representative coordinate is sent to
  // the device's geocoding provider. The photos and complete GPS index stay local.
  Toast.makeText(this,"대표 위치 1곳의 지역명 조회 중…",Toast.LENGTH_SHORT).show()
  worker.execute {
   var result:String?=null
   try {
    if(Geocoder.isPresent()) {
     @Suppress("DEPRECATION")
     val address=Geocoder(this,Locale.KOREAN).getFromLocation(candidate.latitude,candidate.longitude,1)?.firstOrNull()
     if(address!=null) {
      val country=address.countryCode?.let { Locale("",it).getDisplayCountry(Locale.KOREAN) } ?: address.countryName
      val city=address.locality ?: address.subAdminArea ?: address.adminArea
      val detail=address.subLocality ?: address.thoroughfare
      result=listOfNotNull(country,city,detail).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { null }
     }
    }
   } catch (_:Exception) { }
   val value=result
   runOnUiThread {
    resolvingPlaces.remove(candidate.key)
    if(value!=null) {
     placeNames[candidate.key]=value
     try { savePlaceNames() } catch (_:Exception) { }
     render()
    } else Toast.makeText(this,"지역명을 찾지 못했어. 연결 상태를 확인해줘.",Toast.LENGTH_LONG).show()
   }
  }
 }
 private fun hasImageAccess(): Boolean = ContextCompat.checkSelfPermission(this,(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE))==PackageManager.PERMISSION_GRANTED
 private fun hasLocationAccess(): Boolean = ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_MEDIA_LOCATION)==PackageManager.PERMISSION_GRANTED
 private fun requestScan() {
  val perms = mutableListOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
  if (Build.VERSION.SDK_INT >= 34) perms.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
  if (!hasImageAccess() || !hasLocationAccess()) permissionLauncher.launch(perms.toTypedArray()) else scanGallery()
 }
 private fun scanGallery() {
  if (!hasImageAccess() || !hasLocationAccess()) { status.text = "사진 전체 접근과 위치 메타데이터 접근 권한이 모두 필요해"; return }
  if (scanning) return
  scanning = true
  status.text = "갤러리 검색 시작…"
  worker.execute {
   val found = mutableListOf<PhotoPoint>()
   var checked = 0; var noGps = 0; var failed = 0; var total = 0
   var firstError = ""; var fatalError = ""
   val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
   try {
    val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val cursor = contentResolver.query(queryUri, columns, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")
    if (cursor == null) throw IllegalStateException("MediaStore 검색 결과가 null")
    cursor.use { c ->
     total = c.count
     val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
     val dateIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
     while (c.moveToNext()) {
      checked++
      val id = c.getLong(idIdx)
      val date = if (c.isNull(dateIdx)) 0L else c.getLong(dateIdx)
      val uri = ContentUris.withAppendedId(queryUri, id)
      try {
       val original = MediaStore.setRequireOriginal(uri)
       val stream = contentResolver.openInputStream(original)
       if (stream == null) throw IllegalStateException("사진 스트림 열기 실패")
       stream.use {
        val ll = ExifInterface(it).latLong
        if (ll != null && ll.size == 2 && ll[0].isFinite() && ll[1].isFinite() && ll[0] in -90.0..90.0 && ll[1] in -180.0..180.0)
         found.add(PhotoPoint(id, uri.toString(), ll[0], ll[1], date))
        else noGps++
       }
      } catch (e: Exception) {
       failed++
       if (firstError.isEmpty()) firstError = "${e.javaClass.simpleName}: ${e.message ?: "설명 없음"}"
      }
      if (checked % 100 == 0) {
       val progress = "분석 중 ${checked}/${total}장 · GPS ${found.size}장 · GPS 없음 ${noGps}장 · 읽기 실패 ${failed}장"
       runOnUiThread { status.text = progress }
      }
     }
    }
   } catch (e: Exception) {
    fatalError = "${e.javaClass.simpleName}: ${e.message ?: "설명 없음"}"
   }
   if (fatalError.isEmpty()) {
    try { saveCache(found) } catch (e: Exception) {
     fatalError = "저장 실패: ${e.javaClass.simpleName}: ${e.message ?: "설명 없음"}"
    }
   }
   val result = if (fatalError.isNotEmpty()) "분석 오류: ${fatalError}" else
    "분석 완료 · 전체 ${total}장 · 검사 ${checked}장 · GPS ${found.size}장 · GPS 없음 ${noGps}장 · 읽기 실패 ${failed}장" +
    (if (firstError.isNotEmpty()) "\n첫 오류: ${firstError}" else "")
   val classified = if(fatalError.isEmpty()) TravelClassifier.classify(found) else emptyList()
   runOnUiThread {
    if (fatalError.isEmpty()) {
     points.clear(); points.addAll(found); candidates=classified; updateYearButtons(); render()
    }
    scanning = false
    status.text = result
   }
  }
 }
 private fun saveCache(data:List<PhotoPoint>) {
  val a=JSONArray(); data.forEach { p -> a.put(JSONObject().put("id",p.id).put("uri",p.uri).put("lat",p.lat).put("lng",p.lng).put("date",p.date)) }
  openFileOutput("points.json",MODE_PRIVATE).bufferedWriter().use { it.write(a.toString()) }
 }
 private fun loadCache() {
  try {
   val a=JSONArray(openFileInput("points.json").bufferedReader().use { it.readText() })
   for(i in 0 until a.length()) { val o=a.getJSONObject(i); points.add(PhotoPoint(o.getLong("id"),o.getString("uri"),o.getDouble("lat"),o.getDouble("lng"),o.getLong("date"))) }
   status.text="저장된 GPS 사진 ${points.size}장"
   val snapshot=points.toList()
   worker.execute { val classified=TravelClassifier.classify(snapshot); runOnUiThread { candidates=classified; render() } }
  } catch (_:Exception) { }
 }
 /**
  * One JSON file holding the scan cache, trip decisions and place names, so a
  * reinstall never forces a fresh scan of the whole gallery. Photo URIs only
  * work on the same phone; the file stays wherever the user saves it.
  */
 private fun exportBackup(uri:Uri) {
  try {
   val backup=JSONObject().put("app","travel-footprints").put("version",1)
   listOf("points.json","trip_decisions.json","places.json").forEach { name ->
    val text=try { openFileInput(name).bufferedReader().use { it.readText() } } catch (_:Exception) { null }
    if(text!=null) backup.put(name,text)
   }
   contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(backup.toString()) } ?: throw IllegalStateException("파일 열기 실패")
   Toast.makeText(this,"기록 백업 완료 · GPS 사진 ${points.size}장",Toast.LENGTH_LONG).show()
  } catch (e:Exception) { Toast.makeText(this,"백업 실패: ${e.message}",Toast.LENGTH_LONG).show() }
 }
 private fun importBackup(uri:Uri) {
  try {
   val backup=JSONObject(contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: throw IllegalStateException("파일 열기 실패"))
   if(backup.optString("app")!="travel-footprints" || !backup.has("points.json")) throw IllegalArgumentException("여행 발자국 백업 파일이 아니에요")
   JSONArray(backup.getString("points.json"))
   listOf("points.json","trip_decisions.json","places.json").forEach { name ->
    if(backup.has(name)) openFileOutput(name,MODE_PRIVATE).bufferedWriter().use { it.write(backup.getString(name)) }
   }
   points.clear(); decisions.clear(); placeNames.clear()
   loadDecisions(); loadPlaceNames(); loadCache(); updateYearButtons(); render()
   Toast.makeText(this,"백업 불러오기 완료 · GPS 사진 ${points.size}장",Toast.LENGTH_LONG).show()
  } catch (e:Exception) { Toast.makeText(this,"불러오기 실패: ${e.message}",Toast.LENGTH_LONG).show() }
 }
 private fun yearOf(p:PhotoPoint):Int? = PhotoYears.yearOf(p.date)
 private fun updateYearButtons() {
  yearsRow.removeAllViews()
  val available=points.mapNotNull { yearOf(it) }.distinct().sortedDescending()
  val choices=listOf<Int?>(null)+(listOf(2026,2025)+available).distinct().sortedDescending()
  choices.forEach { y ->
   val active=selectedYear==y
   val b=TextView(this).apply {
    text=y?.toString() ?: "전체"
    textSize=13f; gravity=Gravity.CENTER
    setPadding(dp(17),dp(9),dp(17),dp(9))
    setTextColor(if(active) Color.WHITE else Color.rgb(33,47,41))
    background=rounded(if(active) Color.rgb(48,111,83) else Color.TRANSPARENT,24)
    setOnClickListener { selectedYear=y; updateYearButtons(); render(); focusPhotosOnMap() }
   }
   yearsRow.addView(b,LinearLayout.LayoutParams(-2,-2))
  }
 }
 private fun updateRegionButtons() {
  regionRow.removeAllViews()
  listOf("all" to "전체 지역","korea" to "국내 추정","other" to "그 외 지역").forEach { (key,label) ->
   val active=selectedRegion==key
   val button=TextView(this).apply {
    text=label; textSize=11f; gravity=Gravity.CENTER
    setPadding(dp(13),dp(8),dp(13),dp(8))
    setTextColor(if(active) Color.WHITE else Color.rgb(45,83,65))
    background=rounded(if(active) Color.rgb(48,111,83) else Color.rgb(231,240,229),20)
    setOnClickListener {
     selectedRegion=key; updateRegionButtons(); render(); focusPhotosOnMap()
    }
   }
   regionRow.addView(button,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(10),dp(3),0) })
  }
 }
 override fun onMapReady(g:MapLibreMap) {
  map=g
  Log.i("TravelFootprintsMap","map ready; cached GPS=${points.size}")
  // Place the camera at a recorded location before the map style draws.
  // A newly opened map should not visibly fly in from an unrelated continent.
  if(points.isNotEmpty() && !cameraInitialized) focusPhotosOnMap()
  g.addOnMapClickListener { coordinate ->
   val pixel=g.projection.toScreenLocation(coordinate)
   val clusters=g.queryRenderedFeatures(android.graphics.RectF(pixel.x-22,pixel.y-22,pixel.x+22,pixel.y+22),"photo-clusters")
   if(clusters.isNotEmpty()) {
    g.animateCamera(CameraUpdateFactory.newLatLngZoom(coordinate,(g.cameraPosition.zoom+2).coerceAtMost(15.0)))
    return@addOnMapClickListener true
   }
   val hits=g.queryRenderedFeatures(android.graphics.RectF(pixel.x-18,pixel.y-18,pixel.x+18,pixel.y+18),"photo-points")
   val uri=hits.firstOrNull()?.getStringProperty("uri")
   if(uri!=null) try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,Uri.parse(uri)).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch(_:Exception) { Toast.makeText(this,"사진을 열 수 없어요",Toast.LENGTH_SHORT).show() }
   uri!=null
  }
  g.setPrefetchZoomDelta(0)
  g.setStyle(org.maplibre.android.maps.Style.Builder().fromUri(TileNetwork.STYLE_URL)) { style ->
   TileNetwork.koreanize(style)
   Log.i("TravelFootprintsMap","style loaded; rendering ${points.size} cached coordinates")
   render()
  }
 }
 private fun visiblePoints():List<PhotoPoint> = points.filter {
  (selectedYear==null || yearOf(it)==selectedYear) && when(selectedRegion) {
   "korea" -> TravelClassifier.koreaNearby(it.lat,it.lng)
   "other" -> !TravelClassifier.koreaNearby(it.lat,it.lng)
   else -> true
  }
 }
 /** Choose the most photographed approximate area, not a hardcoded continent. */
 private fun focusPhotosOnMap() {
  val g=map ?: return
  val visible=visiblePoints()
  if(visible.isEmpty()) return
  // When Korean photos exist in significant numbers, open there rather than
  // showing an overseas cluster before the user selects a different region.
  val korea=visible.filter { TravelClassifier.koreaNearby(it.lat,it.lng) }
  val focusPool=if(selectedRegion=="all" && korea.size>=visible.size/5) korea else visible
  val dominant=focusPool.groupBy { Pair(floor(it.lat).toInt(),floor(it.lng).toInt()) }
   .maxByOrNull { it.value.size }?.value ?: return
  val lat=dominant.map { it.lat }.sorted()[dominant.size/2]
  val lng=dominant.map { it.lng }.sorted()[dominant.size/2]
  val camera=CameraUpdateFactory.newLatLngZoom(
   org.maplibre.android.geometry.LatLng(lat,lng),if(selectedRegion=="korea") 6.5 else 5.0)
  if(cameraInitialized) g.animateCamera(camera) else g.moveCamera(camera)
  cameraInitialized=true
 }
 private fun render() {
  val visible=visiblePoints()
  gallery.removeAllViews()
  val yearText=selectedYear?.let { "${it}년" } ?: "전체"
  val locations=visible.map { "%.1f,%.1f".format(Locale.US,it.lat,it.lng) }.distinct().size
  val regionText=when(selectedRegion) { "korea"->"국내 추정";"other"->"그 외 지역";else->"전체 지역" }
  summary.text="${yearText} · ${regionText} · GPS 사진 ${visible.size}장 · 위치 약 ${locations}곳"
  val g=map
  g?.getStyle { style ->
   Log.i("TravelFootprintsMap","render: year=$selectedYear region=$selectedRegion features=${visible.size}")
   val features=visible.map { p ->
    org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(p.lng,p.lat)).apply {
     addStringProperty("uri",p.uri)
    }
   }
   val data=org.maplibre.geojson.FeatureCollection.fromFeatures(features)
   val source=style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("photos")
   if(source==null) {
    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("photos", data,
     GeoJsonOptions().withCluster(true).withClusterRadius(64).withClusterMaxZoom(15)))
    style.addLayer(org.maplibre.android.style.layers.CircleLayer("photo-points","photos").withProperties(
     org.maplibre.android.style.layers.PropertyFactory.circleColor("#619889"),
     org.maplibre.android.style.layers.PropertyFactory.circleRadius(6f),
     org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(2f),
     org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor("#FFFDF5")
    ).withFilter(Expression.not(Expression.has("point_count"))))
    style.addLayer(org.maplibre.android.style.layers.CircleLayer("photo-clusters","photos").withProperties(
     org.maplibre.android.style.layers.PropertyFactory.circleColor("#00523D"),
     org.maplibre.android.style.layers.PropertyFactory.circleRadius(19f),
     org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(3f),
     org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor("#FFFDF5")
    ).withFilter(Expression.has("point_count")))
    style.addLayer(org.maplibre.android.style.layers.SymbolLayer("photo-counts","photos").withProperties(
     org.maplibre.android.style.layers.PropertyFactory.textField(Expression.toString(Expression.get("point_count_abbreviated"))),
     org.maplibre.android.style.layers.PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
     org.maplibre.android.style.layers.PropertyFactory.textSize(12f),
     org.maplibre.android.style.layers.PropertyFactory.textColor("#FFFFFF"),
     org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap(true)
    ).withFilter(Expression.has("point_count")))
   } else source.setGeoJson(data)
   Log.i("TravelFootprintsMap","source=${style.getSource("photos")!=null}, clusters=${style.getLayer("photo-clusters")!=null}, singles=${style.getLayer("photo-points")!=null}, counts=${style.getLayer("photo-counts")!=null}")
   if(visible.isNotEmpty()) {
    if(!cameraInitialized) {
     mapPanel.post { if(!cameraInitialized) focusPhotosOnMap() }
    }
   }
  }
  val titleRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
  titleRow.addView(TextView(this).apply {
   text="${yearText}의 순간들"; textSize=21f; setTypeface(null,Typeface.BOLD); setTextColor(Color.rgb(37,72,59))
  },LinearLayout.LayoutParams(0,-2,1f))
  titleRow.addView(TextView(this).apply { text="GPS 사진 ${visible.size}장"; textSize=12f; setTextColor(Color.rgb(80,96,86)) })
  gallery.addView(titleRow)
  val groups=PhotoGroups.byTime(visible)
  if(groups.isEmpty()) {
   gallery.addView(TextView(this).apply { text="이 연도의 날짜 있는 GPS 사진이 없어"; textSize=13f; setPadding(0,dp(12),0,dp(12)) })
  } else {
   gallery.addView(TextView(this).apply {
    text="사진으로 남긴 ${groups.size}개의 기록  ·  옆으로 넘겨보기"
    textSize=11f; setTextColor(Color.rgb(101,115,106)); setPadding(0,dp(5),0,0)
   })
   val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(0,dp(11),0,0) }
   groups.take(12).forEachIndexed { index, group ->
    val p=group.cover
    val item=LinearLayout(this).apply {
     orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(9))
     background=rounded(Color.rgb(242,247,239),16)
    }
    val preview=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=rounded(Color.rgb(219,234,224),12); clipToOutline=true }
    item.addView(preview,LinearLayout.LayoutParams(-1,dp(114)))
    try { preview.setImageBitmap(contentResolver.loadThumbnail(Uri.parse(p.uri),android.util.Size(dp(150),dp(125)),null)) } catch (_:Exception) { }
    val dateFormat=SimpleDateFormat("M.d",Locale.KOREA)
    val date="${dateFormat.format(Date(group.start))}–${dateFormat.format(Date(group.end))}"
    item.addView(TextView(this).apply { text="기록 ${index+1}"; textSize=13f; setTypeface(null,Typeface.BOLD); setTextColor(Color.rgb(37,72,59)); setPadding(dp(3),dp(8),0,0) })
    item.addView(TextView(this).apply { text="$date  ·  ${group.photos.size}장"; textSize=10f; setTextColor(Color.rgb(98,122,108)); setPadding(dp(3),dp(3),0,0) })
    item.setOnClickListener { try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,Uri.parse(p.uri)).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_:Exception) { Toast.makeText(this,"사진을 열 수 없어요",Toast.LENGTH_SHORT).show() } }
    row.addView(item,LinearLayout.LayoutParams(dp(157),-2).apply { rightMargin=dp(11) })
   }
   gallery.addView(HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled=false
    addView(row)
   })
  }
  renderCandidateReviews()
  renderBackupActions()
 }
 private fun renderBackupActions() {
  val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(0,dp(16),0,dp(4)) }
  listOf("기록 백업하기" to { exportLauncher.launch("여행발자국_백업_${SimpleDateFormat("yyyyMMdd",Locale.KOREA).format(Date())}.json") },
   "백업 불러오기" to { importLauncher.launch(arrayOf("application/json","application/octet-stream","text/plain")) }).forEach { (label,action) ->
   row.addView(TextView(this).apply {
    text=label; gravity=Gravity.CENTER; textSize=11f; setTextColor(Color.rgb(25,82,58))
    setPadding(dp(8),dp(9),dp(8),dp(9)); background=rounded(Color.rgb(237,245,232),18)
    setOnClickListener { action() }
   },LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),0,dp(3),0) })
  }
  gallery.addView(row)
 }
 override fun onStart(){super.onStart();mapView.onStart()}
 private fun renderCandidateReviews() {
  val shown=candidates.filter { candidate ->
   (selectedYear==null || candidate.photos.any { PhotoYears.yearOf(it.date)==selectedYear }) &&
   when(selectedRegion) {
    "korea"->candidate.region==ApproxRegion.KOREA_NEARBY
    "other"->candidate.region!=ApproxRegion.KOREA_NEARBY
    else->true
   }
  }
  if(shown.isEmpty()) return
  val likely=shown.filter { decisions[it.key]==true || (decisions[it.key]==null && it.guess==TravelGuess.LIKELY_TRIP) }
  val review=shown.filter { decisions[it.key]==null && it.guess==TravelGuess.REVIEW }
  val routine=shown.filter { decisions[it.key]==false || (decisions[it.key]==null && it.guess==TravelGuess.LIKELY_ROUTINE) }
  gallery.addView(TextView(this).apply {
   text="여행 후보 자동 분류 (추정)"; textSize=17f; setTypeface(null,Typeface.BOLD)
   setTextColor(Color.rgb(24,62,46)); setPadding(0,dp(17),0,dp(4))
  })
  gallery.addView(TextView(this).apply {
   text="여행 가능성 ${likely.size}개 · 확인 필요 ${review.size}개 · 일상 가능성 ${routine.size}개\n국경과 생활권은 GPS로 추정하므로 정확하지 않을 수 있어."
   textSize=11f; setTextColor(Color.rgb(101,115,106))
  })
  val toShow=(likely.filter { decisions[it.key]==null } + review).take(4) +
   likely.filter { decisions[it.key]==true }.take(2) +
   if(showRoutineCandidates) routine.take(4) else emptyList()
  toShow.distinctBy { it.key }.forEach { candidate ->
   val accepted=decisions[candidate.key]
   val name=when(accepted) { true->"여행 확정";false->"일상으로 지정";else->when(candidate.guess) {
    TravelGuess.LIKELY_TRIP->"여행 가능성 높음"
    TravelGuess.REVIEW->"확인 필요"
    TravelGuess.LIKELY_ROUTINE->"일상 가능성 높음"
   } }
   val card=LinearLayout(this).apply {
    orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(10),dp(10),dp(10))
    background=rounded(Color.rgb(237,245,232),18)
   }
   card.addView(TextView(this).apply { text="${name} · GPS 사진 ${candidate.photos.size}장 · ${candidate.dayCount}일"; textSize=12f; setTypeface(null,Typeface.BOLD); setTextColor(Color.rgb(18,72,49)) })
   val coarse=CoarsePlaceLabel.forPoint(candidate.latitude,candidate.longitude)
   val place=placeNames[candidate.key] ?: if(coarse!=null) "$coarse · 정확한 지역 미확인" else "국가·지역 미확인"
   card.addView(TextView(this).apply {
    text=place; textSize=13f; setTypeface(null,Typeface.BOLD)
    setTextColor(Color.rgb(23,76,57)); setPadding(0,dp(5),0,0)
   })
   card.addView(TextView(this).apply {
    text="지도에서 위치 보기"; textSize=11f; setTextColor(Color.rgb(0,82,61))
    setPadding(0,dp(6),0,dp(5))
    setOnClickListener {
     map?.animateCamera(CameraUpdateFactory.newLatLngZoom(
      org.maplibre.android.geometry.LatLng(candidate.latitude,candidate.longitude),11.0))
     bottomScroll.smoothScrollTo(0,0)
    }
   })
   if(!placeNames.containsKey(candidate.key)) card.addView(TextView(this).apply {
    text=if(resolvingPlaces.contains(candidate.key)) "위치 조회 중…" else "정확한 지역명 조회 (대표 GPS 좌표 1개 전송)"
    textSize=11f; setTextColor(Color.rgb(0,82,61)); setPadding(0,dp(6),0,dp(4))
    setOnClickListener { requestOnlinePlace(candidate) }
   })
   card.addView(TextView(this).apply { text=candidate.reason; textSize=11f; setTextColor(Color.rgb(88,104,94)); setPadding(0,dp(5),0,dp(7)) })
   val actions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
   listOf(true to "여행 맞아",false to "일상 방문").forEach { (isTrip,label) ->
    actions.addView(TextView(this).apply {
     text=label; gravity=Gravity.CENTER; textSize=11f
     setTextColor(if(accepted==isTrip) Color.WHITE else Color.rgb(25,82,58))
     setPadding(dp(8),dp(9),dp(8),dp(9))
     background=rounded(if(accepted==isTrip) Color.rgb(0,82,61) else Color.WHITE,18)
     setOnClickListener {
      val previousScroll=bottomScroll.scrollY
      decisions[candidate.key]=isTrip
      try { saveDecisions() } catch(e:Exception) { Toast.makeText(this@MainActivity,"분류 저장 실패",Toast.LENGTH_SHORT).show() }
      render()
      bottomScroll.post { bottomScroll.scrollTo(0,previousScroll) }
     }
    },LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),0,dp(3),0) })
   }
   card.addView(actions)
   gallery.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(9) })
  }
  if(routine.isNotEmpty()) gallery.addView(TextView(this).apply {
   text=if(showRoutineCandidates) "일상 후보 접기" else "일상 가능성 ${routine.size}개 검토하기"
   textSize=11f; gravity=Gravity.CENTER; setTextColor(Color.rgb(0,82,61)); setPadding(0,dp(12),0,dp(5))
   setOnClickListener { val previousScroll=bottomScroll.scrollY; showRoutineCandidates=!showRoutineCandidates; render(); bottomScroll.post { bottomScroll.scrollTo(0,previousScroll) } }
  })
 }
 override fun onResume(){super.onResume();mapView.onResume()}
 override fun onPause(){mapView.onPause();super.onPause()}
 override fun onStop(){mapView.onStop();super.onStop()}
 override fun onLowMemory(){super.onLowMemory();mapView.onLowMemory()}
 override fun onDestroy(){mapView.onDestroy();worker.shutdown();super.onDestroy()}
 override fun onSaveInstanceState(outState:Bundle){super.onSaveInstanceState(outState);mapView.onSaveInstanceState(outState);outState.putInt("year",selectedYear ?: 0);outState.putString("region",selectedRegion)}
}



