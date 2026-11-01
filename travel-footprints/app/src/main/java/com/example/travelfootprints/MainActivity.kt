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
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.sources.GeoJsonSource

class MainActivity : ComponentActivity(), OnMapReadyCallback {
 // Production scans the local gallery and reads the existing points.json cache.
 private val demoMode = false
 private lateinit var mapView: MapView
 private lateinit var status: TextView
 private lateinit var gallery: LinearLayout
 private lateinit var bottomScroll: NestedScrollView
 private lateinit var sheet: BottomSheetBehavior<NestedScrollView>
 private lateinit var header: LinearLayout
 private lateinit var selection: LinearLayout
 private val geoWorker = Executors.newSingleThreadExecutor()
 private val thumbWorker = Executors.newFixedThreadPool(2)
 private val geoFailed = mutableSetOf<String>()
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
  // v0.17: the map fills the screen; filters float on top and the records sit
  // in a bottom sheet the user can drag up, instead of squeezing the map.
  val root = CoordinatorLayout(this).apply { setBackgroundColor(Color.rgb(252,249,242)) }
  mapView = MapView(this)
  mapPanel = FrameLayout(this)
  mapPanel.addView(mapView,FrameLayout.LayoutParams(-1,-1))
  root.addView(mapPanel,CoordinatorLayout.LayoutParams(-1,-1))
  header = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(12)); elevation = dp(4).toFloat()
   background = GradientDrawable().apply {
    setColor(Color.argb(244,252,249,242))
    cornerRadii = floatArrayOf(0f,0f,0f,0f,dp(24).toFloat(),dp(24).toFloat(),dp(24).toFloat(),dp(24).toFloat())
   }
  }
  val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
  val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  val heading = TextView(this).apply { text = "여행 발자국"; textSize = 22f; setTextColor(Color.rgb(37,72,59)); setTypeface(null,Typeface.BOLD) }
  if(demoMode) heading.text="여행 발자국 · TEST"
  summary = TextView(this).apply { text = "사진이 기억하는 나의 여행"; textSize = 11f; setTextColor(Color.rgb(104,122,110)); setPadding(0,dp(2),0,0) }
  titles.addView(heading); titles.addView(summary)
  titleRow.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
  val scan = TextView(this).apply {
   text = if(demoMode) "↻ 예시 위치" else "↻ 다시 분석"; textSize = 11f; setTextColor(green); gravity = Gravity.CENTER
   setPadding(dp(11),dp(8),dp(11),dp(8)); background = rounded(Color.rgb(237,245,232),20)
   setOnClickListener { if(demoMode) focusPhotosOnMap() else requestScan() }
  }
  titleRow.addView(scan)
  yearsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4),dp(3),dp(4),dp(3)); background = rounded(Color.rgb(241,241,230),22) }
  val yearScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(yearsRow) }
  regionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
  header.addView(titleRow)
  header.addView(yearScroll,LinearLayout.LayoutParams(-2,-2).apply { topMargin = dp(10) })
  header.addView(regionRow)
  root.addView(header,CoordinatorLayout.LayoutParams(-1,-2).apply { gravity = Gravity.TOP })
  val bottom = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(10),dp(18),dp(24))
  }
  val grip = View(this).apply { background = rounded(Color.rgb(207,207,201),8) }
  bottom.addView(grip,LinearLayout.LayoutParams(dp(40),dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
  selection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
  bottom.addView(selection)
  status = TextView(this).apply { text = "사진 GPS를 지도에 기록해요"; textSize = 11f; setTextColor(Color.rgb(93,106,99)); maxLines = 3 }
  gallery = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  bottom.addView(gallery)
  bottom.addView(status,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
  bottom.addView(TextView(this).apply {
   text = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"; textSize = 10f; setTextColor(green)
   setPadding(0,dp(8),0,0)
   setOnClickListener { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }
  })
  bottomScroll = NestedScrollView(this).apply {
   isFillViewport = true; addView(bottom); elevation = dp(10).toFloat()
   background = GradientDrawable().apply {
    setColor(Color.rgb(255,253,247))
    cornerRadii = floatArrayOf(dp(26).toFloat(),dp(26).toFloat(),dp(26).toFloat(),dp(26).toFloat(),0f,0f,0f,0f)
   }
  }
  sheet = BottomSheetBehavior<NestedScrollView>().apply {
   isFitToContents = false; peekHeight = dp(250); halfExpandedRatio = 0.55f; isHideable = false
   state = BottomSheetBehavior.STATE_COLLAPSED
  }
  root.addView(bottomScroll,CoordinatorLayout.LayoutParams(-1,-1).apply { behavior = sheet })
  setContentView(root)
  androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
   val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
   view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
   insets
  }
  // Fully expanded, the sheet stops under the filters so they stay usable.
  header.addOnLayoutChangeListener { _,_,top,_,bottomY,_,_,_,_ -> sheet.expandedOffset = bottomY }
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
 private fun center(photos:List<PhotoPoint>):Pair<Double,Double> =
  photos.map { it.lat }.sorted()[photos.size/2] to photos.map { it.lng }.sorted()[photos.size/2]
 private fun placeFor(lat:Double,lng:Double):String? = placeNames[PlaceNames.key(lat,lng)]
 /**
  * Looks up short place names for record cards. Only one representative
  * coordinate per card goes to the phone's geocoder; photos never leave the phone.
  * Results are cached in places.json so each spot is looked up once.
  */
 private fun requestPlaces(spots:List<Pair<Double,Double>>) {
  if(demoMode || !Geocoder.isPresent()) return
  val todo=spots.map { PlaceNames.key(it.first,it.second) to it }.distinctBy { it.first }
   .filter { (k,_) -> !placeNames.containsKey(k) && k !in resolvingPlaces && k !in geoFailed }
  if(todo.isEmpty()) return
  todo.forEach { resolvingPlaces.add(it.first) }
  geoWorker.execute {
   val results=todo.map { (k,ll) ->
    val label=try {
     @Suppress("DEPRECATION")
     Geocoder(this,Locale.KOREAN).getFromLocation(ll.first,ll.second,1)?.firstOrNull()?.let { a ->
      PlaceNames.label(a.countryCode,a.countryName,a.adminArea,a.locality,a.subAdminArea,a.subLocality,a.thoroughfare)
     }
    } catch(_:Exception) { null }
    k to label
   }
   runOnUiThread {
    results.forEach { (k,v) -> resolvingPlaces.remove(k); if(v!=null) placeNames[k]=v else geoFailed.add(k) }
    if(results.any { it.second!=null }) {
     try { savePlaceNames() } catch(_:Exception) { }
     val y=bottomScroll.scrollY; render(); bottomScroll.post { bottomScroll.scrollTo(0,y) }
    }
   }
  }
 }
 private fun loadThumb(view:ImageView,uri:String,size:Int) {
  view.tag=uri
  thumbWorker.execute {
   val bmp=try { contentResolver.loadThumbnail(Uri.parse(uri),android.util.Size(size,size),null) } catch(e:Exception) {
    Log.w("TravelFootprintsMap","thumbnail failed for $uri: ${e.javaClass.simpleName}"); null
   }
   runOnUiThread {
    if(view.tag!=uri) return@runOnUiThread
    if(bmp!=null) view.setImageBitmap(bmp) else {
     // Missing or unreadable photo (e.g. deleted from the gallery): show an icon instead of an empty box.
     view.scaleType=ImageView.ScaleType.CENTER; view.setImageResource(android.R.drawable.ic_menu_gallery)
    }
   }
  }
 }
 private fun handleMapTap(g:MapLibreMap,coordinate:LatLng):Boolean {
  val pixel=g.projection.toScreenLocation(coordinate)
  val box=android.graphics.RectF(pixel.x-dp(24),pixel.y-dp(24),pixel.x+dp(24),pixel.y+dp(24))
  val cluster=g.queryRenderedFeatures(box,"photo-clusters").firstOrNull()
  val source=g.style?.getSourceAs<GeoJsonSource>("photos")
  if(cluster!=null && source!=null) {
   val expansion=try { source.getClusterExpansionZoom(cluster) } catch(_:Exception) { 99 }
   if(expansion<=14) {
    g.animateCamera(CameraUpdateFactory.newLatLngZoom(coordinate,expansion.toDouble()))
   } else {
    // Photos taken at (almost) the same spot never split apart: list them instead of zooming forever.
    val count=cluster.getNumberProperty("point_count")?.toLong() ?: 50L
    val leaves=try { source.getClusterLeaves(cluster,count.coerceAtMost(300L),0L).features().orEmpty() } catch(_:Exception) { emptyList() }
    val uris=leaves.mapNotNull { it.getStringProperty("uri") }.toSet()
    val photos=points.filter { it.uri in uris }.sortedByDescending { it.date }
    if(photos.isNotEmpty()) showSelection(placeFor(coordinate.latitude,coordinate.longitude) ?: "이 자리의 사진",photos)
    requestPlaces(listOf(coordinate.latitude to coordinate.longitude))
   }
   return true
  }
  val hits=g.queryRenderedFeatures(box,"photo-points")
  val uris=hits.mapNotNull { it.getStringProperty("uri") }.toSet()
  if(uris.isEmpty()) return false
  val photos=points.filter { it.uri in uris }.sortedByDescending { it.date }
  showSelection(placeFor(coordinate.latitude,coordinate.longitude) ?: "이 자리의 사진",photos)
  requestPlaces(listOf(coordinate.latitude to coordinate.longitude))
  return true
 }
 /** Moves the map so these photos fit between the filters and the sheet. */
 private fun showOnMap(photos:List<PhotoPoint>) {
  val g=map ?: return
  if(photos.isEmpty()) return
  val distinct=photos.map { LatLng(it.lat,it.lng) }.distinctBy { "%.5f,%.5f".format(Locale.US,it.latitude,it.longitude) }
  val top=header.height+dp(30); val bottomPad=dp(250)+dp(30)
  if(distinct.size<2) g.animateCamera(CameraUpdateFactory.newLatLngZoom(distinct.first(),13.0))
  else {
   val bounds=LatLngBounds.Builder().includes(distinct).build()
   g.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds,dp(40),top,dp(40),bottomPad))
  }
  sheet.state=BottomSheetBehavior.STATE_COLLAPSED
 }
 /** A strip of photos at the top of the sheet: a tapped spot, record or trip. */
 private fun showSelection(title:String,photos:List<PhotoPoint>) {
  selection.removeAllViews()
  val titleRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
  titleRow.addView(TextView(this).apply {
   text="$title · ${photos.size}장"; textSize=16f; setTypeface(null,Typeface.BOLD); setTextColor(Color.rgb(37,72,59))
   maxLines=2
  },LinearLayout.LayoutParams(0,-2,1f))
  titleRow.addView(TextView(this).apply {
   text="닫기"; textSize=12f; setTextColor(Color.rgb(0,82,61)); setPadding(dp(12),dp(6),dp(4),dp(6))
   setOnClickListener { selection.visibility=View.GONE; selection.removeAllViews() }
  })
  selection.addView(titleRow)
  val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(0,dp(10),0,dp(14)) }
  val shown=photos.take(60)
  shown.forEachIndexed { i,p ->
   val img=ImageView(this).apply {
    scaleType=ImageView.ScaleType.CENTER_CROP; background=rounded(Color.rgb(219,234,224),12); clipToOutline=true
    setOnClickListener { openViewer(shown,i) }
   }
   loadThumb(img,p.uri,dp(110))
   row.addView(img,LinearLayout.LayoutParams(dp(104),dp(104)).apply { rightMargin=dp(8) })
  }
  selection.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(row) })
  if(photos.size>shown.size) selection.addView(TextView(this).apply { text="최근 ${shown.size}장만 보여요"; textSize=10f; setTextColor(Color.rgb(101,115,106)) })
  selection.addView(View(this).apply { setBackgroundColor(Color.rgb(231,236,226)) },LinearLayout.LayoutParams(-1,dp(1)).apply { bottomMargin=dp(12) })
  selection.visibility=View.VISIBLE
  bottomScroll.scrollTo(0,0)
 }
 /** Full-screen viewer inside the app; tap the left/right half for previous/next. */
 private fun openViewer(photos:List<PhotoPoint>,startIndex:Int) {
  var index=startIndex
  val dialog=android.app.Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen)
  val frame=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
  val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
  frame.addView(image,FrameLayout.LayoutParams(-1,-1))
  val caption=TextView(this).apply { textSize=13f; setTextColor(Color.WHITE); setPadding(dp(18),dp(18),dp(18),dp(18)); setShadowLayer(4f,0f,0f,Color.BLACK) }
  frame.addView(caption,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
  val close=TextView(this).apply { text="✕"; textSize=22f; setTextColor(Color.WHITE); setPadding(dp(18),dp(14),dp(18),dp(14)); setOnClickListener { dialog.dismiss() } }
  frame.addView(close,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
  val size=maxOf(resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels)
  val format=SimpleDateFormat("yyyy.M.d HH:mm",Locale.KOREA)
  fun show() {
   val p=photos[index]
   image.setImageDrawable(null)
   caption.text="${index+1} / ${photos.size}" + (if(p.date>0) "  ·  ${format.format(Date(p.date))}" else "")
   thumbWorker.execute {
    val bmp=try { contentResolver.loadThumbnail(Uri.parse(p.uri),android.util.Size(size,size),null) } catch(_:Exception) { null }
    runOnUiThread { if(photos[index]==p) { if(bmp!=null) image.setImageBitmap(bmp) else image.setImageResource(android.R.drawable.ic_menu_gallery) } }
   }
  }
  image.setOnTouchListener { v,e ->
   if(e.action==android.view.MotionEvent.ACTION_UP) {
    if(e.x<v.width/2f) { if(index>0) { index--; show() } } else { if(index<photos.size-1) { index++; show() } }
    v.performClick()
   }
   true
  }
  dialog.setContentView(frame)
  show()
  dialog.show()
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
  val previous = points.toList()
  worker.execute {
   val found = mutableListOf<PhotoPoint>()
   val failedIds = mutableSetOf<Long>()
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
       failed++; failedIds.add(id)
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
   // Photos that failed to read keep their previous point instead of vanishing from the cache.
   val merged = ScanMerge.keepUnreadable(previous, found, failedIds)
   if (fatalError.isEmpty()) {
    try { saveCache(merged) } catch (e: Exception) {
     fatalError = "저장 실패: ${e.javaClass.simpleName}: ${e.message ?: "설명 없음"}"
    }
   }
   val result = if (fatalError.isNotEmpty()) "분석 오류: ${fatalError}" else
    "분석 완료 · 전체 ${total}장 · 검사 ${checked}장 · GPS ${merged.size}장 · GPS 없음 ${noGps}장 · 읽기 실패 ${failed}장" +
    (if (firstError.isNotEmpty()) "\n첫 오류: ${firstError}" else "")
   val classified = if(fatalError.isEmpty()) TravelClassifier.classify(merged) else emptyList()
   runOnUiThread {
    if (fatalError.isEmpty()) {
     points.clear(); points.addAll(merged); candidates=classified; updateYearButtons(); render()
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
  g.addOnMapClickListener { coordinate -> handleMapTap(g,coordinate) }
  g.setPrefetchZoomDelta(0)
  // The compass sat under the top-right scan button and showed as a dark half circle.
  // A travel map does not need rotation, so keep north up and hide the compass.
  g.uiSettings.isCompassEnabled=false
  g.uiSettings.isRotateGesturesEnabled=false
  g.uiSettings.isTiltGesturesEnabled=false
  // The sheet covers the bottom-left corner; attribution text lives inside the sheet.
  g.uiSettings.isLogoEnabled=false
  g.uiSettings.isAttributionEnabled=false
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
   val shownGroups=groups.take(12)
   requestPlaces(shownGroups.map { center(it.photos) })
   shownGroups.forEachIndexed { index, group ->
    val p=group.cover
    val item=LinearLayout(this).apply {
     orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(9))
     background=rounded(Color.rgb(242,247,239),16)
    }
    val preview=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=rounded(Color.rgb(219,234,224),12); clipToOutline=true }
    item.addView(preview,LinearLayout.LayoutParams(-1,dp(114)))
    loadThumb(preview,p.uri,dp(150))
    val (cLat,cLng)=center(group.photos)
    val dateFormat=SimpleDateFormat("M.d",Locale.KOREA)
    val date="${dateFormat.format(Date(group.start))}–${dateFormat.format(Date(group.end))}"
    item.addView(TextView(this).apply { text=placeFor(cLat,cLng) ?: "기록 ${index+1}"; textSize=13f; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD); setTextColor(Color.rgb(37,72,59)); setPadding(dp(3),dp(8),0,0) })
    item.addView(TextView(this).apply { text="$date  ·  ${group.photos.size}장"; textSize=10f; setTextColor(Color.rgb(98,122,108)); setPadding(dp(3),dp(3),0,0) })
    item.setOnClickListener {
     showOnMap(group.photos)
     showSelection("${placeFor(cLat,cLng) ?: "기록 ${index+1}"} · $date",group.photos.asReversed())
    }
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
  requestPlaces(shown.filter { !placeNames.containsKey(it.key) }.take(12).map { it.latitude to it.longitude })
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
   val place=placeNames[candidate.key] ?: placeFor(candidate.latitude,candidate.longitude) ?: when {
    resolvingPlaces.contains(PlaceNames.key(candidate.latitude,candidate.longitude)) -> "지역 이름 찾는 중…"
    coarse!=null -> coarse
    else -> "지역 미확인"
   }
   card.addView(TextView(this).apply {
    text=place; textSize=13f; setTypeface(null,Typeface.BOLD)
    setTextColor(Color.rgb(23,76,57)); setPadding(0,dp(5),0,0)
   })
   card.addView(TextView(this).apply {
    text="지도에서 위치 보기"; textSize=11f; setTextColor(Color.rgb(0,82,61))
    setPadding(0,dp(6),0,dp(5))
    setOnClickListener {
     showOnMap(candidate.photos)
     showSelection(place,candidate.photos.asReversed())
    }
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
 override fun onDestroy(){mapView.onDestroy();worker.shutdown();geoWorker.shutdown();thumbWorker.shutdown();super.onDestroy()}
 override fun onSaveInstanceState(outState:Bundle){super.onSaveInstanceState(outState);mapView.onSaveInstanceState(outState);outState.putInt("year",selectedYear ?: 0);outState.putString("region",selectedRegion)}
}



