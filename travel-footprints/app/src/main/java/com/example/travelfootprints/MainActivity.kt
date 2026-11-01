package com.example.travelfootprints

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * v0.18: a travel album in an old-computer look. Everyday photos are left out.
 * Home lists trips like desktop icons, a trip opens as an album window grouped by day,
 * and the map window shows one photo pin per trip. Look and feel lives in [Y2k].
 */
class MainActivity : ComponentActivity(), OnMapReadyCallback {
 private val ink = Y2k.INK
 private fun dp(v: Int) = Y2k.dp(v)
 private fun dpf(v: Float) = Y2k.dpf(v)

 private lateinit var homeList: RecyclerView
 private lateinit var mapView: MapView
 private lateinit var mapWindow: LinearLayout
 private lateinit var mapYears: LinearLayout
 private lateinit var abroadButton: TextView
 private lateinit var albumPanel: FrameLayout
 private lateinit var navAlbum: TextView
 private lateinit var navMap: TextView
 private var map: MapLibreMap? = null
 private var openTrip: Trip? = null
 private var albumRefresh: (() -> Unit)? = null
 private var showAbroad = false

 private val points = mutableListOf<PhotoPoint>()
 private var candidates = emptyList<TravelCandidate>()
 private var trips = emptyList<Trip>()
 private var asks = emptyList<TravelCandidate>()
 private val decisions = mutableMapOf<String, Boolean>()
 private val placeNames = mutableMapOf<String, String>()
 private val resolvingPlaces = mutableSetOf<String>()
 private val geoFailed = mutableSetOf<String>()
 private var selectedYear: Int? = null
 private var showAllAsks = false
 private var statusText = "맨 아래 '사진 다시 분석'을 누르면 여행이 만들어져요"
 private var statusView: TextView? = null
 private var scanning = false

 private val worker = Executors.newSingleThreadExecutor()
 private val geoWorker = Executors.newSingleThreadExecutor()
 private val thumbWorker = Executors.newFixedThreadPool(3)
 private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
  override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
 }
 private val pinImages = mutableSetOf<String>()

 private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
  if (hasImageAccess() && hasLocationAccess()) scanGallery() else setStatus("사진 전체 접근과 위치 정보 접근을 모두 허용해야 해요. 설정에서 허용해 주세요.")
 }
 private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) exportBackup(uri) }
 private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importBackup(uri) }

 // ---------- layout ----------
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  Y2k.init(this)
  MapLibre.getInstance(this); TileNetwork.configure(this)
  selectedYear = savedInstanceState?.getInt("year", 0)?.takeIf { it != 0 }

  val outer = FrameLayout(this)
  outer.addView(Y2k.SkyView(this), FrameLayout.LayoutParams(-1, -1))
  val root = FrameLayout(this)
  outer.addView(root, FrameLayout.LayoutParams(-1, -1))
  val taskHeight = dp(64)

  val content = FrameLayout(this)
  root.addView(content, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = taskHeight })

  homeList = RecyclerView(this).apply { clipToPadding = false; setPadding(0, 0, 0, dp(16)) }
  val grid = GridLayoutManager(this, 2)
  grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
   override fun getSpanSize(position: Int): Int = if ((homeList.adapter as HomeAdapter).rows.getOrNull(position) is Row.TripRow) 1 else 2
  }
  homeList.layoutManager = grid
  homeList.adapter = HomeAdapter()
  content.addView(homeList, FrameLayout.LayoutParams(-1, -1))

  val mw = Y2k.window(this, "여행 지도", onClose = { showTab(false) })
  mapWindow = mw.root; mapWindow.visibility = View.GONE
  val mapBox = FrameLayout(this).apply { background = Y2k.frame(Color.parseColor("#BFE3F2"), ink, 2, 0); setPadding(dp(2), dp(2), dp(2), dp(2)) }
  mapView = MapView(this)
  mapBox.addView(mapView, FrameLayout.LayoutParams(-1, -1))
  mapYears = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  mapBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(mapYears) },
   FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(8), dp(8), dp(8), 0) })
  abroadButton = Y2k.button(this, "", true, 13f)
  abroadButton.visibility = View.GONE
  mapBox.addView(abroadButton, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(10), dp(10)) })
  mw.body.addView(mapBox, FrameLayout.LayoutParams(-1, -1))
  content.addView(mapWindow, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), dp(8), dp(8), dp(4)) })

  albumPanel = FrameLayout(this).apply { visibility = View.GONE; isClickable = true }
  content.addView(albumPanel, FrameLayout.LayoutParams(-1, -1))

  // Taskbar: the two screens, like the buttons at the bottom of an old desktop.
  val taskbar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Y2k.GRAY) }
  taskbar.addView(View(this).apply { setBackgroundColor(ink) }, LinearLayout.LayoutParams(-1, dp(3)))
  val taskRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(4)) }
  navAlbum = Y2k.button(this, "앨범", true, 15f) { showTab(false) }
  navMap = Y2k.button(this, "지도", false, 15f) { showTab(true) }
  taskRow.addView(navAlbum, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
  taskRow.addView(navMap, LinearLayout.LayoutParams(0, -2, 1f))
  taskbar.addView(taskRow, LinearLayout.LayoutParams(-1, -1))
  root.addView(taskbar, FrameLayout.LayoutParams(-1, taskHeight, Gravity.BOTTOM))

  setContentView(outer)
  androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(outer) { _, insets ->
   val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
   root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
   insets
  }
  onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
   override fun handleOnBackPressed() {
    when {
     albumPanel.visibility == View.VISIBLE -> closeTrip()
     mapWindow.visibility == View.VISIBLE -> showTab(false)
     else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
    }
   }
  })
  mapView.onCreate(savedInstanceState); mapView.getMapAsync(this)
  loadDecisions(); loadPlaceNames(); loadCache()
  (homeList.adapter as HomeAdapter).submit()
 }

 private fun showTab(mapSelected: Boolean) {
  closeTrip()
  homeList.visibility = if (mapSelected) View.GONE else View.VISIBLE
  mapWindow.visibility = if (mapSelected) View.VISIBLE else View.GONE
  navAlbum.background = Y2k.frame(if (mapSelected) Y2k.GRAY else Y2k.YELLOW, ink, 2, 2)
  navMap.background = Y2k.frame(if (mapSelected) Y2k.YELLOW else Y2k.GRAY, ink, 2, 2)
  if (mapSelected) { renderMapYears(); updateMapPins(); fitTrips() }
 }

 // ---------- data ----------
 private fun visibleTrips() = trips.filter { t -> selectedYear == null || PhotoYears.yearOf(t.start) == selectedYear || PhotoYears.yearOf(t.end) == selectedYear }
 private fun visibleAsks() = asks.filter { c -> selectedYear == null || PhotoYears.yearOf(c.firstDate) == selectedYear }
 private fun tripYears() = trips.flatMap { listOfNotNull(PhotoYears.yearOf(it.start)) }.distinct().sortedDescending()

 private fun rebuild() {
  trips = Trips.build(candidates, decisions)
  asks = Trips.toAsk(candidates, decisions).sortedByDescending { it.photos.size }
  if (selectedYear != null && selectedYear !in tripYears()) selectedYear = null
  requestPlaces(trips.map { it.lat to it.lng } + asks.take(10).map { it.latitude to it.longitude })
  refresh()
 }

 private fun refresh() {
  albumRefresh?.invoke()
  (homeList.adapter as HomeAdapter).submit()
  if (mapWindow.visibility == View.VISIBLE) { renderMapYears(); updateMapPins() }
 }

 private fun tripTitle(t: Trip) = placeFor(t.lat, t.lng) ?: CoarsePlaceLabel.forPoint(t.lat, t.lng)?.removeSuffix(" 부근") ?: "여행"
 private fun askTitle(c: TravelCandidate) = placeFor(c.latitude, c.longitude) ?: CoarsePlaceLabel.forPoint(c.latitude, c.longitude) ?: "어딘가"
 private fun dateRange(start: Long, end: Long): String {
  val full = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA); val short = SimpleDateFormat("MM.dd", Locale.KOREA)
  return if (full.format(Date(start)) == full.format(Date(end))) full.format(Date(start)) else "${full.format(Date(start))} – ${short.format(Date(end))}"
 }

 // ---------- home ----------
 private sealed class Row {
  object Header : Row()
  class TripRow(val trip: Trip) : Row()
  object AskHeader : Row()
  class AskRow(val c: TravelCandidate) : Row()
  object MoreAsks : Row()
  object Empty : Row()
  object Footer : Row()
 }

 private inner class HomeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  var rows: List<Row> = listOf(Row.Header, Row.Footer)
  fun submit() {
   val t = visibleTrips(); val a = visibleAsks()
   val list = mutableListOf<Row>(Row.Header)
   if (t.isEmpty()) list.add(Row.Empty) else t.forEach { list.add(Row.TripRow(it)) }
   if (a.isNotEmpty()) {
    list.add(Row.AskHeader)
    (if (showAllAsks) a else a.take(5)).forEach { list.add(Row.AskRow(it)) }
    if (!showAllAsks && a.size > 5) list.add(Row.MoreAsks)
   }
   list.add(Row.Footer)
   rows = list; notifyDataSetChanged()
  }
  override fun getItemCount() = rows.size
  override fun getItemViewType(position: Int): Int = when (rows[position]) {
   is Row.TripRow -> 1
   is Row.AskRow -> 3
   else -> 0
  }
  override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder = when (type) {
   1 -> TripHolder()
   3 -> AskHolder()
   else -> object : RecyclerView.ViewHolder(FrameLayout(this@MainActivity).apply { layoutParams = RecyclerView.LayoutParams(-1, -2) }) {}
  }
  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
   when (val row = rows[position]) {
    is Row.TripRow -> (holder as TripHolder).bind(row.trip)
    is Row.AskRow -> (holder as AskHolder).bind(row.c)
    else -> { val box = holder.itemView as FrameLayout; box.removeAllViews(); box.addView(simpleRow(row)) }
   }
  }
 }

 private fun simpleRow(row: Row): View = when (row) {
  Row.Header -> headerView()
  Row.Empty -> Y2k.onSky(Y2k.text(this, if (points.isEmpty()) "아직 분석한 사진이 없어요.\n맨 아래 '사진 다시 분석'을 눌러 주세요." else "이 기간에는 찾은 여행이 없어요.", 15f, ink, true)).apply {
   gravity = Gravity.CENTER; setPadding(dp(24), dp(36), dp(24), dp(36)); setLineSpacing(0f, 1.2f)
  }
  Row.AskHeader -> LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(6))
   addView(Y2k.text(context, "여행이 맞나요?  ${visibleAsks().size}", 15f, Color.WHITE, true).apply {
    background = Y2k.frame(ink, Color.WHITE, 2, 3); setPadding(dp(14), dp(8), dp(17), dp(11))
   }, LinearLayout.LayoutParams(-2, -2))
   addView(Y2k.onSky(Y2k.text(context, "당일치기처럼 애매한 기록이에요. '여행 맞아'를 누르면 위 목록에 들어가요.", 12f, ink, true)),
    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
  }
  Row.MoreAsks -> Y2k.button(this, "애매한 기록 모두 보기", false, 13f) {
   showAllAsks = true; (homeList.adapter as HomeAdapter).submit()
  }.apply { layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8); bottomMargin = dp(8) } }
  Row.Footer -> footerView()
  else -> View(this)
 }

 private fun headerView(): View = LinearLayout(this).apply {
  orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(14), dp(16), dp(10))
  addView(Y2k.text(context, "여행 발자국", 28f, Color.WHITE, true).apply {
   background = Y2k.frame(ink, Color.WHITE, 3, 5); setPadding(dp(20), dp(11), dp(25), dp(16))
  }, LinearLayout.LayoutParams(-2, -2))
  if (trips.isNotEmpty()) {
   val vt = visibleTrips()
   addView(Y2k.onSky(Y2k.text(context, "여행 ${vt.size}번 · 사진 ${"%,d".format(vt.sumOf { it.photos.size })}장", 13f, ink, true)),
    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
   val chips = yearChips(false)
   addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
  } else {
   addView(Y2k.onSky(Y2k.text(context, "사진 속 여행을 모아 앨범으로 만들어요", 13f, ink, true)),
    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
  }
 }

 private fun footerView(): View {
  val w = Y2k.window(this, "시스템")
  val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
  val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  listOf<Pair<String, () -> Unit>>(
   "사진 다시 분석" to { requestScan() },
   "기록 백업" to { exportLauncher.launch("여행발자국_백업_${SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())}.json") },
   "백업 불러오기" to { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
  ).forEachIndexed { i, (label, action) ->
   actions.addView(Y2k.button(this, label, i == 0, 12f) { action() },
    LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) })
  }
  col.addView(actions)
  val status = Y2k.text(this, statusText, 12f, ink, false).apply { setPadding(0, dp(10), 0, 0); setLineSpacing(0f, 1.15f) }
  statusView = status
  col.addView(status)
  col.addView(Y2k.text(this, "지도 © OpenFreeMap © OpenMapTiles © OpenStreetMap contributors\n글꼴 Galmuri (SIL OFL)", 10f, Y2k.MUTED, false).apply { setPadding(0, dp(8), 0, 0) })
  w.body.addView(col)
  w.root.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(14), dp(12), dp(8)) }
  return FrameLayout(this).apply { addView(w.root, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(14), dp(12), dp(8)) }) }
 }

 private fun yearChips(onMap: Boolean): LinearLayout = LinearLayout(this).apply {
  orientation = LinearLayout.HORIZONTAL
  (listOf<Int?>(null) + tripYears()).forEach { y ->
   addView(Y2k.button(context, y?.toString() ?: "전체", selectedYear == y, 13f) {
    selectedYear = y; (homeList.adapter as HomeAdapter).submit()
    if (onMap) { renderMapYears(); updateMapPins(); fitTrips() }
   }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
  }
 }

 private fun photoBox(): ImageView = ImageView(this).apply {
  scaleType = ImageView.ScaleType.CENTER_CROP
  background = Y2k.frame(ink, ink, 0, 4)
  setPadding(dp(3), dp(3), dp(7), dp(7))
 }

 private inner class TripHolder : RecyclerView.ViewHolder(LinearLayout(this@MainActivity)) {
  private val box = itemView as LinearLayout
  private val image = photoBox()
  private val badge = Y2k.text(this@MainActivity, "", 11f, Y2k.YELLOW, true).apply { setBackgroundColor(ink); setPadding(dp(6), dp(3), dp(6), dp(3)) }
  private val label = Y2k.pressableLabel(this@MainActivity, "", 15f)
  private val sub = Y2k.onSky(Y2k.text(this@MainActivity, "", 12f, ink, true)).apply { gravity = Gravity.CENTER }
  private val size = resources.displayMetrics.widthPixels / 2 - dp(32)
  init {
   box.orientation = LinearLayout.VERTICAL; box.gravity = Gravity.CENTER_HORIZONTAL
   box.layoutParams = RecyclerView.LayoutParams(-1, -2)
   box.setPadding(dp(8), dp(10), dp(8), dp(12)); box.isClickable = true
   val frame = FrameLayout(this@MainActivity)
   frame.addView(image, FrameLayout.LayoutParams(size, size))
   frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(14)) })
   box.addView(frame, LinearLayout.LayoutParams(size, size))
   box.addView(label, LinearLayout.LayoutParams(size, -2).apply { topMargin = dp(8) })
   box.addView(sub, LinearLayout.LayoutParams(size, -2).apply { topMargin = dp(2) })
  }
  fun bind(t: Trip) {
   label.text = tripTitle(t)
   badge.text = "${Trips.dayCount(t)}일"
   sub.text = "${dateRange(t.start, t.start)} · ${"%,d".format(t.photos.size)}장"
   loadThumb(image, t.cover.uri, size)
   box.setOnClickListener { openTrip(t) }
  }
 }

 private inner class AskHolder : RecyclerView.ViewHolder(LinearLayout(this@MainActivity)) {
  private val row = itemView as LinearLayout
  private val image = photoBox()
  private val title = Y2k.text(this@MainActivity, "", 15f, ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
  private val sub = Y2k.text(this@MainActivity, "", 12f, Y2k.MUTED, false)
  private val yes = Y2k.button(this@MainActivity, "여행 맞아", true, 12f)
  private val no = Y2k.button(this@MainActivity, "아니야", false, 12f)
  init {
   row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
   row.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(6), dp(14), dp(6)) }
   row.background = Y2k.frame(Y2k.GRAY, ink, 2, 4)
   row.setPadding(dp(8), dp(8), dp(12), dp(12))
   row.addView(image, LinearLayout.LayoutParams(dp(78), dp(78)))
   val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
   texts.addView(title); texts.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
   val buttons = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
   buttons.addView(yes); buttons.addView(no, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
   texts.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
   row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
  }
  fun bind(c: TravelCandidate) {
   title.text = askTitle(c)
   sub.text = "${dateRange(c.firstDate, c.lastDate)} · 사진 ${c.photos.size}장"
   loadThumb(image, c.photos[c.photos.size / 2].uri, dp(78))
   image.setOnClickListener { openTrip(Trip(c.key, c.photos, c.latitude, c.longitude, c.region != ApproxRegion.KOREA_NEARBY)) }
   yes.setOnClickListener { decide(c, true) }
   no.setOnClickListener { decide(c, false) }
  }
 }

 private fun decide(c: TravelCandidate, isTrip: Boolean) {
  decisions[c.key] = isTrip
  try { saveDecisions() } catch (_: Exception) { Toast.makeText(this, "저장 실패", Toast.LENGTH_SHORT).show() }
  Toast.makeText(this, if (isTrip) "여행 목록에 넣었어요" else "목록에서 뺐어요", Toast.LENGTH_SHORT).show()
  rebuild()
 }

 // ---------- trip album window ----------
 private sealed class AlbumRow {
  object Cover : AlbumRow()
  class Day(val day: TripDay) : AlbumRow()
  class Photo(val p: PhotoPoint, val index: Int) : AlbumRow()
 }

 private fun openTrip(t: Trip) {
  openTrip = t
  val days = Trips.days(t)
  val plan = Trips.plan(days)
  requestPlaces(plan.stops.map { it.lat to it.lng })
  val rows = mutableListOf<AlbumRow>(AlbumRow.Cover)
  var i = 0
  days.forEach { d -> rows.add(AlbumRow.Day(d)); d.photos.forEach { rows.add(AlbumRow.Photo(it, i++)) } }
  val ordered = days.flatMap { it.photos }
  val dayPositions = rows.withIndex().filter { it.value is AlbumRow.Day }.map { it.index }

  val w = Y2k.window(this, "${tripTitle(t)}.${PhotoYears.yearOf(t.start) ?: ""}", onBack = { closeTrip() }, onClose = { closeTrip() })
  val inset = FrameLayout(this).apply { background = Y2k.frame(Y2k.PAPER, ink, 2, 0); setPadding(dp(2), dp(2), dp(2), dp(2)) }
  val list = RecyclerView(this).apply { clipToPadding = false; setPadding(0, 0, 0, dp(8)) }
  val grid = GridLayoutManager(this, 3)
  grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(p: Int) = if (rows[p] is AlbumRow.Photo) 1 else 3 }
  list.layoutManager = grid
  val cell = (resources.displayMetrics.widthPixels - dp(52)) / 3
  list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
   override fun getItemCount() = rows.size
   override fun getItemViewType(p: Int) = if (rows[p] is AlbumRow.Photo) 2 else 0
   override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder = object : RecyclerView.ViewHolder(
    if (type == 2) ImageView(this@MainActivity).apply {
     scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(ink); setPadding(dp(2), dp(2), dp(2), dp(2))
     layoutParams = RecyclerView.LayoutParams(-1, cell)
    } else FrameLayout(this@MainActivity).apply { layoutParams = RecyclerView.LayoutParams(-1, -2) }) {}
   override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
    when (val r = rows[p]) {
     is AlbumRow.Photo -> { val iv = h.itemView as ImageView; loadThumb(iv, r.p.uri, cell); iv.setOnClickListener { openViewer(ordered, r.index) } }
     is AlbumRow.Day -> { val box = h.itemView as FrameLayout; box.removeAllViews(); box.addView(dayHeader(r.day, plan)) }
     AlbumRow.Cover -> { val box = h.itemView as FrameLayout; box.removeAllViews(); box.addView(albumCover(t, days, plan) { pos -> grid.scrollToPositionWithOffset(dayPositions[pos], 0) }) }
    }
   }
  }
  albumRefresh = { list.adapter?.notifyDataSetChanged() }
  inset.addView(list, FrameLayout.LayoutParams(-1, -1))
  w.body.addView(inset, FrameLayout.LayoutParams(-1, -1))

  albumPanel.removeAllViews()
  albumPanel.addView(Y2k.SkyView(this), FrameLayout.LayoutParams(-1, -1))
  albumPanel.addView(w.root, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), dp(8), dp(8), dp(4)) })
  albumPanel.visibility = View.VISIBLE
 }

 private fun closeTrip() { albumPanel.visibility = View.GONE; albumPanel.removeAllViews(); openTrip = null; albumRefresh = null }

 private fun albumCover(t: Trip, days: List<TripDay>, plan: TripPlan, jump: (Int) -> Unit): View = LinearLayout(this).apply {
  orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(2), dp(2), dp(4))
  val top = FrameLayout(context)
  val img = photoBox().apply { background = Y2k.frame(ink, ink, 0, 0); setPadding(dp(2), dp(2), dp(2), dp(2)) }
  loadThumb(img, t.cover.uri, resources.displayMetrics.widthPixels)
  top.addView(img, FrameLayout.LayoutParams(-1, -1))
  top.addView(Y2k.text(context, dateRange(t.start, t.end), 12f, Color.WHITE, true).apply { setBackgroundColor(ink); setPadding(dp(8), dp(4), dp(8), dp(4)) },
   FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(10)) })
  addView(top, LinearLayout.LayoutParams(-1, dp(190)))
  val titleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), dp(4), dp(8)) }
  titleRow.addView(Y2k.text(context, tripTitle(t), 24f, ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
  titleRow.addView(Y2k.text(context, "${Trips.dayCount(t)}일 · 사진 ${"%,d".format(t.photos.size)}장", 12f, Y2k.MUTED, true))
  addView(titleRow)
  addView(statRow(listOf("${Trips.dayCount(t)}일" to "여행 기간", "${plan.stops.size}곳" to "방문한 곳", "${"%,d".format(t.photos.size)}장" to "사진")),
   LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
  if (plan.stops.isNotEmpty()) {
   addView(Y2k.text(context, "▶ 여행 경로", 14f, ink, true), LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), dp(4), 0, dp(6)) })
   val mapBox = FrameLayout(context).apply { background = Y2k.frame(Y2k.PAPER, ink, 2, 0); setPadding(dp(2), dp(2), dp(2), dp(2)) }
   val cached = routeBitmaps.get(t.key)
   when {
    cached != null -> mapBox.addView(ImageView(context).apply { setImageBitmap(cached); scaleType = ImageView.ScaleType.FIT_XY }, FrameLayout.LayoutParams(-1, -1))
    t.key in routeFailed -> mapBox.addView(Y2k.RouteView(context, plan.stops.map { it.lat to it.lng }), FrameLayout.LayoutParams(-1, -1))
    else -> {
     mapBox.addView(Y2k.text(context, "지도 불러오는 중…", 13f, Y2k.MUTED, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
     startRouteSnapshot(t, plan)
    }
   }
   if (cached != null) mapBox.addView(Y2k.text(context, "© OpenStreetMap · OpenFreeMap", 9f, ink, false).apply { setBackgroundColor(Color.argb(190, 255, 255, 255)); setPadding(dp(4), dp(1), dp(4), dp(1)) },
    FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
   addView(mapBox, LinearLayout.LayoutParams(-1, dp(230)))
   addView(Y2k.text(context, "▶ 방문한 곳", 14f, ink, true), LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), dp(14), 0, dp(6)) })
   plan.stops.forEachIndexed { i, s ->
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(3), dp(4), dp(3)) }
    row.addView(Y2k.text(context, "${i + 1}", 12f, ink, true).apply { gravity = Gravity.CENTER; background = Y2k.frame(if (i == 0) Y2k.YELLOW else Color.WHITE, ink, 2, 0) },
     LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(10) })
    row.addView(Y2k.text(context, stopName(s), 14f, ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(Y2k.text(context, "${s.days.joinToString("·")}일차 · ${s.photoCount}장", 12f, Y2k.MUTED, true))
    addView(row)
   }
   addView(View(context), LinearLayout.LayoutParams(-1, dp(8)))
  }
  if (days.size > 1) {
   val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
   days.forEachIndexed { i, d ->
    chips.addView(Y2k.button(context, "${d.dayNumber}일차", false, 12f) { jump(i) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
   }
   addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
  }
 }

 private fun dayHeader(d: TripDay, plan: TripPlan): View = LinearLayout(this).apply {
  orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(14), dp(6), dp(8))
  val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
  top.addView(Y2k.text(context, "▶ ${d.dayNumber}일차 — ${SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(Date(d.dayStart))}", 14f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
  top.addView(Y2k.text(context, "${d.photos.size}장", 12f, Y2k.MUTED, true))
  addView(top)
  val where = stopNames((plan.dayRoutes[d.dayNumber] ?: emptyList()).map { plan.stops[it] })
  if (where.isNotEmpty()) addView(Y2k.text(context, where.joinToString(" → "), 13f, Y2k.MUTED, false).apply { setPadding(dp(14), dp(5), 0, 0) })
 }

 private fun stopName(s: TripStop): String {
  val n = placeFor(s.lat, s.lng) ?: CoarsePlaceLabel.forPoint(s.lat, s.lng)?.removeSuffix(" 부근") ?: return "이름 찾는 중"
  // The geocoder only knows the country out at sea or on a ferry.
  return if (n == "대한민국") "이동 중 (바다·길)" else n
 }

 // ---------- route map picture ----------
 private val routeBitmaps = LruCache<String, Bitmap>(8)
 private val routeFailed = mutableSetOf<String>()
 private val routeLoading = mutableSetOf<String>()
 private var routeSnapshotter: org.maplibre.android.snapshotter.MapSnapshotter? = null
 private var routeStyleJson: String? = null

 /** The base map as JSON without any labels (we draw our own numbers), in the app's colors. */
 private fun fetchRouteStyle(): String? {
  routeStyleJson?.let { return it }
  return try {
   val req = okhttp3.Request.Builder().url(TileNetwork.STYLE_URL).header("User-Agent", "TravelFootprints/0.21 (Android; com.example.travelfootprints)").build()
   val body = okhttp3.OkHttpClient().newCall(req).execute().use { it.body?.string() } ?: return null
   val o = JSONObject(body); val layers = o.getJSONArray("layers"); val out = JSONArray()
   for (i in 0 until layers.length()) {
    val l = layers.getJSONObject(i)
    if (l.optString("type") == "symbol") continue
    val paint = l.optJSONObject("paint") ?: JSONObject().also { l.put("paint", it) }
    when {
     l.optString("type") == "background" -> paint.put("background-color", "#DCEFCB")
     l.optString("id") == "water" -> paint.put("fill-color", "#BFE3F2")
     l.optString("id") == "park" -> paint.put("fill-color", "#C8E6B6")
     l.optString("id") == "waterway" -> paint.put("line-color", "#A9D6EC")
    }
    out.put(l)
   }
   o.put("layers", out)
   o.toString().also { routeStyleJson = it }
  } catch (_: Exception) { null }
 }

 /** Draws the trip's real photo-by-photo track and numbered places on a still map picture. */
 private fun startRouteSnapshot(t: Trip, plan: TripPlan) {
  if (!routeLoading.add(t.key)) return
  fun fail() { routeLoading.remove(t.key); routeFailed.add(t.key); runOnUiThread { albumRefresh?.invoke() } }
  thumbWorker.execute {
   val json = fetchRouteStyle()
   runOnUiThread {
    if (json == null) { fail(); return@runOnUiThread }
    try {
     val w = resources.displayMetrics.widthPixels - dp(60); val h = dp(226)
     val pts = plan.trail + plan.dots + plan.stops.map { it.lat to it.lng }
     val minLat = pts.minOf { it.first }; val maxLat = pts.maxOf { it.first }
     val minLng = pts.minOf { it.second }; val maxLng = pts.maxOf { it.second }
     val padLat = max((maxLat - minLat) * 0.18, 0.012); val padLng = max((maxLng - minLng) * 0.18, 0.012)
     val bounds = LatLngBounds.from((maxLat + padLat).coerceAtMost(85.0), maxLng + padLng, (minLat - padLat).coerceAtLeast(-85.0), minLng - padLng)
     val opts = org.maplibre.android.snapshotter.MapSnapshotter.Options(w, h)
      .withStyleBuilder(Style.Builder().fromJson(json)).withRegion(bounds).withPixelRatio(1f).withLogo(false)
     val snap = org.maplibre.android.snapshotter.MapSnapshotter(this, opts)
     routeSnapshotter = snap
     snap.start({ shot ->
      try {
       val bmp = shot.bitmap.copy(Bitmap.Config.ARGB_8888, true)
       val c = Canvas(bmp)
       val line = Paint().apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; isAntiAlias = true }
       val path = android.graphics.Path()
       plan.trail.forEachIndexed { i, ll -> val q = shot.pixelForLatLng(LatLng(ll.first, ll.second)); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
       line.color = Color.argb(200, 255, 255, 255); line.strokeWidth = dpf(5f); c.drawPath(path, line)
       line.color = Y2k.BLUE; line.strokeWidth = dpf(2.5f); c.drawPath(path, line)
       val dot = Paint(); val dr = dpf(3f)
       plan.dots.forEach { ll -> val q = shot.pixelForLatLng(LatLng(ll.first, ll.second)); dot.color = ink; c.drawRect(q.x - dr - 1f, q.y - dr - 1f, q.x + dr + 1f, q.y + dr + 1f, dot); dot.color = Color.WHITE; c.drawRect(q.x - dr, q.y - dr, q.x + dr, q.y + dr, dot) }
       val fill = Paint(); val num = Paint().apply { color = ink; textAlign = Paint.Align.CENTER; isAntiAlias = true; typeface = Y2k.bold; textSize = dpf(12f) }
       val r = dpf(11f)
       plan.stops.forEachIndexed { i, s ->
        val q = shot.pixelForLatLng(LatLng(s.lat, s.lng))
        fill.color = ink; c.drawRect(q.x - r - 2f, q.y - r - 2f, q.x + r + 2f, q.y + r + 2f, fill)
        fill.color = if (i == 0) Y2k.YELLOW else Color.WHITE; c.drawRect(q.x - r, q.y - r, q.x + r, q.y + r, fill)
        c.drawText((i + 1).toString(), q.x, q.y + dpf(4.5f), num)
       }
       routeBitmaps.put(t.key, bmp)
       routeLoading.remove(t.key)
       albumRefresh?.invoke()
      } catch (_: Exception) { fail() }
     }, { _ -> fail() })
    } catch (_: Throwable) { fail() }
   }
  }
 }
 /** Names in visiting order, without repeating the same name back to back. */
 private fun stopNames(stops: List<TripStop>): List<String> {
  val out = mutableListOf<String>()
  stops.forEach { val n = stopName(it); if (out.lastOrNull() != n) out.add(n) }
  return out
 }

 private fun statRow(items: List<Pair<String, String>>): View = LinearLayout(this).apply {
  orientation = LinearLayout.HORIZONTAL
  items.forEachIndexed { i, (big, small) ->
   val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = Y2k.frame(Y2k.GRAY, ink, 2, 0); setPadding(dp(4), dp(10), dp(4), dp(10)) }
   box.addView(Y2k.text(context, big, 18f, ink, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
   box.addView(Y2k.text(context, small, 11f, Y2k.MUTED, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
   addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) })
  }
 }

 /** Full-screen viewer; swipe or tap the left/right edge to move between photos. */
 private fun openViewer(photos: List<PhotoPoint>, startIndex: Int) {
  var index = startIndex
  val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
  val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
  val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
  frame.addView(image, FrameLayout.LayoutParams(-1, -1))
  val caption = Y2k.text(this, "", 14f, Color.WHITE, true).apply { setPadding(dp(18), dp(18), dp(18), dp(24)); setShadowLayer(4f, 0f, 0f, Color.BLACK) }
  frame.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
  frame.addView(Y2k.button(this, "× 닫기", false, 13f) { dialog.dismiss() },
   FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(28), dp(16), 0) })
  val size = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
  val format = SimpleDateFormat("yyyy.MM.dd (E) HH:mm", Locale.KOREA)
  fun show() {
   val p = photos[index]
   caption.text = "${index + 1} / ${photos.size}" + (if (p.date > 0) "   ${format.format(Date(p.date))}" else "")
   image.setImageDrawable(null)
   thumbWorker.execute {
    val bmp = try { contentResolver.loadThumbnail(Uri.parse(p.uri), android.util.Size(size, size), null) } catch (_: Exception) { null }
    runOnUiThread { if (photos[index] == p) { if (bmp != null) image.setImageBitmap(bmp) else image.setImageResource(android.R.drawable.ic_menu_gallery) } }
   }
  }
  var downX = 0f
  image.setOnTouchListener { v, e ->
   when (e.action) {
    MotionEvent.ACTION_DOWN -> downX = e.x
    MotionEvent.ACTION_UP -> {
     val dx = e.x - downX
     val next = when {
      dx < -dp(50) -> 1
      dx > dp(50) -> -1
      e.x > v.width * 0.65f -> 1
      e.x < v.width * 0.35f -> -1
      else -> 0
     }
     if (next != 0 && index + next in photos.indices) { index += next; show() }
     v.performClick()
    }
   }
   true
  }
  dialog.setContentView(frame)
  show(); dialog.show()
 }

 // ---------- map ----------
 override fun onMapReady(g: MapLibreMap) {
  map = g
  g.uiSettings.isCompassEnabled = false
  g.uiSettings.isRotateGesturesEnabled = false
  g.uiSettings.isTiltGesturesEnabled = false
  g.uiSettings.isLogoEnabled = false
  g.uiSettings.isAttributionEnabled = false
  g.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(36.3, 127.8), 5.6))
  g.setStyle(Style.Builder().fromUri(TileNetwork.STYLE_URL)) { style ->
   TileNetwork.koreanize(style)
   pinImages.clear()
   style.addSource(GeoJsonSource("trips"))
   style.addLayer(SymbolLayer("trip-pin", "trips").withProperties(
    PropertyFactory.iconImage(Expression.get("icon")),
    PropertyFactory.iconAllowOverlap(false), PropertyFactory.iconIgnorePlacement(false), PropertyFactory.iconPadding(6f),
    PropertyFactory.symbolSortKey(Expression.get("order"))))
   updateMapPins()
   if (mapWindow.visibility == View.VISIBLE) fitTrips()
  }
  g.addOnMapClickListener { ll ->
   val px = g.projection.toScreenLocation(ll)
   val r = dp(34).toFloat()
   val hit = g.queryRenderedFeatures(android.graphics.RectF(px.x - r, px.y - r, px.x + r, px.y + r), "trip-pin")
    .minByOrNull { it.getNumberProperty("order")?.toDouble() ?: 0.0 }
   val key = hit?.getStringProperty("key")
   val trip = trips.firstOrNull { it.key == key }
   if (trip != null) { openTrip(trip); true } else false
  }
 }

 private fun renderMapYears() {
  mapYears.removeAllViews()
  val chips = yearChips(true)
  while (chips.childCount > 0) { val v = chips.getChildAt(0); chips.removeViewAt(0); mapYears.addView(v) }
 }

 private fun pinLabel(t: Trip): String {
  val name = tripTitle(t)
  return (if (name.length > 10) name.take(9) + "…" else name) + " ${Trips.dayCount(t)}일"
 }
 private fun pinId(t: Trip) = "pin-${t.key}-${pinLabel(t).hashCode()}"

 private fun updateMapPins() {
  val style = map?.style ?: return
  if (!style.isFullyLoaded) return
  val shown = visibleTrips()
  val features = shown.mapIndexed { i, t ->
   Feature.fromGeometry(Point.fromLngLat(t.lng, t.lat)).apply {
    addStringProperty("key", t.key)
    addStringProperty("icon", pinId(t))
    addNumberProperty("order", i) // newest first: wins when pins collide
   }
  }
  style.getSourceAs<GeoJsonSource>("trips")?.setGeoJson(FeatureCollection.fromFeatures(features))
  shown.forEach { loadPin(it) }
  val abroadCount = shown.count { it.abroad }
  abroadButton.visibility = if (abroadCount == 0 && !showAbroad) View.GONE else View.VISIBLE
  abroadButton.text = if (showAbroad) "국내 여행 보기" else "해외 여행 ${abroadCount}곳 보기 ▶"
  abroadButton.setOnClickListener { showAbroad = !showAbroad; updateMapPins(); fitTrips() }
 }

 private fun fitTrips() {
  val g = map ?: return
  val shown = visibleTrips()
  val pool = shown.filter { it.abroad == showAbroad }.ifEmpty { shown }
  if (pool.isEmpty()) return
  val spots = pool.map { LatLng(it.lat, it.lng) }
  if (spots.distinctBy { "%.3f,%.3f".format(Locale.US, it.latitude, it.longitude) }.size < 2) g.animateCamera(CameraUpdateFactory.newLatLngZoom(spots.first(), 9.0))
  else g.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(spots).build(), dp(60), dp(80), dp(60), dp(80)))
 }

 /** A square photo with a hard border and a name tag, drawn once per trip and handed to the map style. */
 private fun loadPin(t: Trip) {
  val id = pinId(t)
  if (id in pinImages) return
  pinImages.add(id)
  val label = pinLabel(t)
  thumbWorker.execute {
   val src = try { contentResolver.loadThumbnail(Uri.parse(t.cover.uri), android.util.Size(dp(100), dp(100)), null) } catch (_: Exception) { null }
   val bmp = drawPin(src, label)
   runOnUiThread { map?.style?.takeIf { it.isFullyLoaded }?.addImage(id, bmp) }
  }
 }

 private fun drawPin(src: Bitmap?, label: String): Bitmap {
  val d = resources.displayMetrics.density
  val box = 52 * d; val border = 3 * d; val sh = 3 * d
  val text = Paint().apply { typeface = Y2k.bold; textSize = 12 * d; color = Color.WHITE; isAntiAlias = false }
  val tagW = text.measureText(label) + 14 * d; val tagH = 20 * d
  val w = max(box, tagW) + sh + 2 * d
  val h = box + 5 * d + tagH + sh + 2 * d
  val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
  out.density = resources.displayMetrics.densityDpi
  val c = Canvas(out)
  val p = Paint()
  val left = (w - sh - box) / 2f
  p.color = Y2k.SHADOW; c.drawRect(left + sh, sh, left + box + sh, box + sh, p)
  p.color = Y2k.INK; c.drawRect(left, 0f, left + box, box, p)
  if (src != null) {
   val side = min(src.width, src.height)
   val sx = (src.width - side) / 2; val sy = (src.height - side) / 2
   c.drawBitmap(src, Rect(sx, sy, sx + side, sy + side), android.graphics.RectF(left + border, border, left + box - border, box - border), Paint())
  } else { p.color = Color.parseColor("#8FB6C9"); c.drawRect(left + border, border, left + box - border, box - border, p) }
  val tagLeft = (w - sh - tagW) / 2f; val tagTop = box + 5 * d
  p.color = Y2k.SHADOW; c.drawRect(tagLeft + sh, tagTop + sh, tagLeft + tagW + sh, tagTop + tagH + sh, p)
  p.color = Y2k.INK; c.drawRect(tagLeft, tagTop, tagLeft + tagW, tagTop + tagH, p)
  c.drawText(label, tagLeft + 7 * d, tagTop + tagH - 6 * d, text)
  return out
 }

 // ---------- thumbnails & places ----------
 private fun loadThumb(view: ImageView, uri: String, size: Int) {
  val key = "$uri@$size"
  view.tag = key
  view.scaleType = ImageView.ScaleType.CENTER_CROP
  thumbs.get(key)?.let { view.setImageBitmap(it); return }
  view.setImageDrawable(null)
  thumbWorker.execute {
   val bmp = try { contentResolver.loadThumbnail(Uri.parse(uri), android.util.Size(size, size), null) } catch (e: Exception) {
    Log.w("TravelFootprints", "thumbnail failed for $uri: ${e.javaClass.simpleName}"); null
   }
   if (bmp != null) thumbs.put(key, bmp)
   runOnUiThread {
    if (view.tag != key) return@runOnUiThread
    if (bmp != null) view.setImageBitmap(bmp) else { view.scaleType = ImageView.ScaleType.CENTER; view.setImageResource(android.R.drawable.ic_menu_gallery) }
   }
  }
 }

 private fun placeFor(lat: Double, lng: Double): String? = placeNames[PlaceNames.key(lat, lng)]

 /**
  * Short place names for trip cards. One representative coordinate per trip goes to the
  * phone's geocoder; photos never leave the phone. Results are cached in places.json.
  */
 private fun requestPlaces(spots: List<Pair<Double, Double>>) {
  if (!Geocoder.isPresent()) return
  val todo = spots.map { PlaceNames.key(it.first, it.second) to it }.distinctBy { it.first }
   .filter { (k, _) -> !placeNames.containsKey(k) && k !in resolvingPlaces && k !in geoFailed }
  if (todo.isEmpty()) return
  todo.forEach { resolvingPlaces.add(it.first) }
  geoWorker.execute {
   val results = todo.map { (k, ll) ->
    val label = try {
     @Suppress("DEPRECATION")
     Geocoder(this, Locale.KOREAN).getFromLocation(ll.first, ll.second, 1)?.firstOrNull()?.let { a ->
      PlaceNames.label(a.countryCode, a.countryName, a.adminArea, a.locality, a.subAdminArea, a.subLocality, a.thoroughfare)
     }
    } catch (_: Exception) { null }
    k to label
   }
   runOnUiThread {
    results.forEach { (k, v) -> resolvingPlaces.remove(k); if (v != null) placeNames[k] = v else geoFailed.add(k) }
    if (results.any { it.second != null }) { try { savePlaceNames() } catch (_: Exception) { }; refresh() }
   }
  }
 }

 // ---------- storage ----------
 private fun loadDecisions() {
  try { val o = JSONObject(openFileInput("trip_decisions.json").bufferedReader().use { it.readText() }); o.keys().forEach { k -> decisions[k] = o.getBoolean(k) } } catch (_: Exception) { }
 }
 private fun saveDecisions() {
  val o = JSONObject(); decisions.forEach { (k, v) -> o.put(k, v) }
  openFileOutput("trip_decisions.json", MODE_PRIVATE).bufferedWriter().use { it.write(o.toString()) }
 }
 private fun loadPlaceNames() {
  try { val o = JSONObject(openFileInput("places.json").bufferedReader().use { it.readText() }); o.keys().forEach { k -> placeNames[k] = o.getString(k) } } catch (_: Exception) { }
 }
 private fun savePlaceNames() {
  val o = JSONObject(); placeNames.forEach { (k, v) -> o.put(k, v) }
  openFileOutput("places.json", MODE_PRIVATE).bufferedWriter().use { it.write(o.toString()) }
 }
 private fun saveCache(data: List<PhotoPoint>) {
  val a = JSONArray(); data.forEach { p -> a.put(JSONObject().put("id", p.id).put("uri", p.uri).put("lat", p.lat).put("lng", p.lng).put("date", p.date)) }
  openFileOutput("points.json", MODE_PRIVATE).bufferedWriter().use { it.write(a.toString()) }
 }
 private fun loadCache() {
  try {
   val a = JSONArray(openFileInput("points.json").bufferedReader().use { it.readText() })
   for (i in 0 until a.length()) { val o = a.getJSONObject(i); points.add(PhotoPoint(o.getLong("id"), o.getString("uri"), o.getDouble("lat"), o.getDouble("lng"), o.getLong("date"))) }
   setStatus("저장된 GPS 사진 ${"%,d".format(points.size)}장에서 여행을 찾고 있어요…")
   val snapshot = points.toList()
   worker.execute { val classified = TravelClassifier.classify(snapshot); runOnUiThread { candidates = classified; setStatus("저장된 GPS 사진 ${"%,d".format(points.size)}장"); rebuild() } }
  } catch (_: Exception) { }
 }
 private fun setStatus(text: String) { statusText = text; statusView?.text = text }

 /** One JSON file with the scan cache, trip decisions and place names. */
 private fun exportBackup(uri: Uri) {
  try {
   val backup = JSONObject().put("app", "travel-footprints").put("version", 1)
   listOf("points.json", "trip_decisions.json", "places.json").forEach { name ->
    val text = try { openFileInput(name).bufferedReader().use { it.readText() } } catch (_: Exception) { null }
    if (text != null) backup.put(name, text)
   }
   contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(backup.toString()) } ?: throw IllegalStateException("파일 열기 실패")
   Toast.makeText(this, "기록 백업 완료 · GPS 사진 ${points.size}장", Toast.LENGTH_LONG).show()
  } catch (e: Exception) { Toast.makeText(this, "백업 실패: ${e.message}", Toast.LENGTH_LONG).show() }
 }
 private fun importBackup(uri: Uri) {
  try {
   val backup = JSONObject(contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: throw IllegalStateException("파일 열기 실패"))
   if (backup.optString("app") != "travel-footprints" || !backup.has("points.json")) throw IllegalArgumentException("여행 발자국 백업 파일이 아니에요")
   JSONArray(backup.getString("points.json"))
   listOf("points.json", "trip_decisions.json", "places.json").forEach { name ->
    if (backup.has(name)) openFileOutput(name, MODE_PRIVATE).bufferedWriter().use { it.write(backup.getString(name)) }
   }
   points.clear(); decisions.clear(); placeNames.clear()
   loadDecisions(); loadPlaceNames(); loadCache()
   Toast.makeText(this, "백업 불러오기 완료", Toast.LENGTH_LONG).show()
  } catch (e: Exception) { Toast.makeText(this, "불러오기 실패: ${e.message}", Toast.LENGTH_LONG).show() }
 }

 // ---------- gallery scan ----------
 private fun hasImageAccess() = ContextCompat.checkSelfPermission(this, if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
 private fun hasLocationAccess() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
 private fun requestScan() {
  val perms = mutableListOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
  if (Build.VERSION.SDK_INT >= 34) perms.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
  if (!hasImageAccess() || !hasLocationAccess()) permissionLauncher.launch(perms.toTypedArray()) else scanGallery()
 }
 private fun scanGallery() {
  if (scanning) return
  scanning = true
  setStatus("갤러리 분석 시작…")
  val previous = points.toList()
  worker.execute {
   val found = mutableListOf<PhotoPoint>(); val failedIds = mutableSetOf<Long>()
   var checked = 0; var noGps = 0; var failed = 0; var total = 0; var fatal = ""
   try {
    val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val cursor = contentResolver.query(queryUri, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN), null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")
     ?: throw IllegalStateException("갤러리 검색 실패")
    cursor.use { c ->
     total = c.count
     val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID); val dateIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
     while (c.moveToNext()) {
      checked++
      val id = c.getLong(idIdx); val date = if (c.isNull(dateIdx)) 0L else c.getLong(dateIdx)
      val uri = ContentUris.withAppendedId(queryUri, id)
      try {
       val stream = contentResolver.openInputStream(MediaStore.setRequireOriginal(uri)) ?: throw IllegalStateException("사진 열기 실패")
       stream.use {
        val ll = ExifInterface(it).latLong
        if (ll != null && ll.size == 2 && ll[0].isFinite() && ll[1].isFinite() && ll[0] in -90.0..90.0 && ll[1] in -180.0..180.0) found.add(PhotoPoint(id, uri.toString(), ll[0], ll[1], date)) else noGps++
       }
      } catch (_: Exception) { failed++; failedIds.add(id) }
      if (checked % 200 == 0) { val msg = "분석 중 ${checked}/${total}장 · 위치 있는 사진 ${found.size}장"; runOnUiThread { setStatus(msg) } }
     }
    }
   } catch (e: Exception) { fatal = e.message ?: e.javaClass.simpleName }
   val merged = ScanMerge.keepUnreadable(previous, found, failedIds)
   if (fatal.isEmpty()) try { saveCache(merged) } catch (e: Exception) { fatal = "저장 실패: ${e.message}" }
   val classified = if (fatal.isEmpty()) TravelClassifier.classify(merged) else emptyList()
   runOnUiThread {
    scanning = false
    if (fatal.isEmpty()) {
     points.clear(); points.addAll(merged); candidates = classified
     setStatus("분석 완료 · 전체 ${total}장 · 위치 있는 사진 ${merged.size}장 · 읽기 실패 ${failed}장")
     rebuild()
    } else setStatus("분석 오류: $fatal")
   }
  }
 }

 // ---------- lifecycle ----------
 override fun onStart() { super.onStart(); mapView.onStart() }
 override fun onResume() { super.onResume(); mapView.onResume() }
 override fun onPause() { mapView.onPause(); super.onPause() }
 override fun onStop() { mapView.onStop(); super.onStop() }
 override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); thumbs.evictAll() }
 override fun onDestroy() { mapView.onDestroy(); worker.shutdown(); geoWorker.shutdown(); thumbWorker.shutdown(); super.onDestroy() }
 override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState); outState.putInt("year", selectedYear ?: 0) }
}
