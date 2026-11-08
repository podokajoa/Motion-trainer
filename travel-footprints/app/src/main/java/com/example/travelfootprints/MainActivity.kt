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
 private var hiddenTrips = emptyList<Trip>()
 private val edits = TripEdits()
 private var pickingCover = false
 // shared-element opening: the card photo grows into the album cover and shrinks back when closing
 private var sharedFrom: android.graphics.Rect? = null
 private var sharedBitmap: Bitmap? = null
 private var sharedOverlay: ImageView? = null
 private var sharedAnim: android.animation.ValueAnimator? = null
 private var coverImage: ImageView? = null
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
     albumPanel.visibility == View.VISIBLE -> closeTripAnimated()
     mapWindow.visibility == View.VISIBLE -> showTab(false)
     else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
    }
   }
  })
  mapView.onCreate(savedInstanceState); mapView.getMapAsync(this)
  loadDecisions(); loadPlaceNames(); loadBook(); loadEdits(); loadCache()
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
  val edited = TripEditing.apply(Trips.build(candidates, decisions), edits)
  trips = edited.shown; hiddenTrips = edited.hidden
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

 private fun tripTitle(t: Trip) = t.title?.takeIf { it.isNotBlank() } ?: placeFor(t.lat, t.lng) ?: CoarsePlaceLabel.forPoint(t.lat, t.lng)?.removeSuffix(" 부근") ?: "여행"
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
  object Hidden : Row()
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
   if (hiddenTrips.isNotEmpty()) list.add(Row.Hidden)
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
  Row.Hidden -> Y2k.button(this, "숨긴 여행 ${hiddenTrips.size}개 보기", false, 13f) { openHiddenList() }
   .apply { layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8); bottomMargin = dp(8) } }
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
  setPadding(0, dp(3), 0, dp(7))
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
   box.setOnClickListener { openTrip(t, image) }
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

 private fun openTrip(t: Trip, from: ImageView? = null) {
  openTrip = t
  pickingCover = false
  coverImage = null
  val days = Trips.days(t)
  val plan = Trips.plan(days)
  requestPlaces(plan.stops.map { it.lat to it.lng })
  val rows = mutableListOf<AlbumRow>(AlbumRow.Cover)
  var i = 0
  days.forEach { d -> rows.add(AlbumRow.Day(d)); d.photos.forEach { rows.add(AlbumRow.Photo(it, i++)) } }
  val ordered = days.flatMap { it.photos }
  val dayPositions = rows.withIndex().filter { it.value is AlbumRow.Day }.map { it.index }

  val w = Y2k.window(this, "${tripTitle(t)}.${PhotoYears.yearOf(t.start) ?: ""}", onBack = { closeTripAnimated() }, onClose = { closeTripAnimated() })
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
    if (type == 2) FrameLayout(this@MainActivity).apply {
     addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(ink); setPadding(0, dp(2), 0, dp(2)) }, FrameLayout.LayoutParams(-1, -1))
     addView(Y2k.text(this@MainActivity, "☆", 24f, Color.WHITE, false).apply {
      typeface = android.graphics.Typeface.DEFAULT; setShadowLayer(4f, 0f, 0f, ink); setPadding(dp(8), dp(4), dp(8), dp(10))
     }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
     addView(Y2k.text(this@MainActivity, "", 11f, Color.WHITE, true).apply {
      maxLines = 2; gravity = Gravity.CENTER; setShadowLayer(4f, 0f, 1f, Color.BLACK); setPadding(dp(6), 0, dp(6), dp(8))
      ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE
     }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
     layoutParams = RecyclerView.LayoutParams(-1, cell)
    } else FrameLayout(this@MainActivity).apply { layoutParams = RecyclerView.LayoutParams(-1, -2) }) {}
   override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
    when (val r = rows[p]) {
     is AlbumRow.Photo -> {
      val fl = h.itemView as FrameLayout; val iv = fl.getChildAt(0) as ImageView; val st = fl.getChildAt(1) as TextView
      val cap = fl.getChildAt(2) as TextView
      val c0 = tripBook(t).captions[r.p.id]
      if (c0 != null && !c0.isEmpty()) { cap.text = c0.text; cap.visibility = View.VISIBLE } else cap.visibility = View.GONE
      loadThumb(iv, r.p.uri, cell); iv.setOnClickListener { if (pickingCover) setCover(t, r.p) else openViewer(t, ordered, r.index) }
      fun paintStar() { val on = r.p.id in tripBook(t).stars; st.text = if (on) "★" else "☆"; st.setTextColor(if (on) Y2k.YELLOW else Color.WHITE) }
      paintStar(); st.setOnClickListener { toggleStar(t, r.p.id); paintStar() }
     }
     is AlbumRow.Day -> { val box = h.itemView as FrameLayout; box.removeAllViews(); box.addView(dayHeader(t, r.day, plan)) }
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
  sharedAnim?.cancel(); sharedOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }; sharedOverlay = null
  sharedFrom = null; sharedBitmap = null
  val fromBmp = (from?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
  if (from != null && fromBmp != null && from.width > 0 && from.isAttachedToWindow) {
   val parent = albumPanel.parent as ViewGroup
   val a = rectInParent(from, parent)
   sharedFrom = a; sharedBitmap = fromBmp
   albumPanel.alpha = 0f
   // the cover row appears after the list has been laid out, so wait a few frames for it
   fun go(tries: Int) {
    val ci = coverImage
    if (ci != null && ci.isAttachedToWindow && ci.width > 0) {
     ci.alpha = 0f
     flyPhoto(fromBmp, a, rectInParent(ci, parent), true) { ci.alpha = 1f; albumPanel.alpha = 1f }
    } else if (tries < 12 && albumPanel.visibility == View.VISIBLE) albumPanel.postDelayed({ go(tries + 1) }, 16)
    else albumPanel.alpha = 1f
   }
   albumPanel.post { go(0) }
  } else albumPanel.alpha = 1f
 }

 private fun rectInParent(v: View, parent: View): android.graphics.Rect {
  val a = IntArray(2); val b = IntArray(2)
  v.getLocationInWindow(a); parent.getLocationInWindow(b)
  return android.graphics.Rect(a[0] - b[0], a[1] - b[1], a[0] - b[0] + v.width, a[1] - b[1] + v.height)
 }

 /** Moves a copy of the photo from rect [a] to rect [b] while the album panel fades in (or out). */
 private fun flyPhoto(bmp: Bitmap, a: android.graphics.Rect, b: android.graphics.Rect, fadeIn: Boolean, onEnd: () -> Unit) {
  val parent = albumPanel.parent as ViewGroup
  sharedAnim?.cancel(); sharedOverlay?.let { parent.removeView(it) }
  val ov = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(bmp); setBackgroundColor(ink) }
  parent.addView(ov, FrameLayout.LayoutParams(a.width(), a.height()).apply { leftMargin = a.left; topMargin = a.top })
  sharedOverlay = ov
  var cancelled = false
  val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
  anim.duration = 320
  anim.interpolator = android.view.animation.DecelerateInterpolator(1.6f)
  anim.addUpdateListener {
   val f = it.animatedValue as Float
   fun mix(x: Int, y: Int) = (x + (y - x) * f).toInt()
   val lp = ov.layoutParams as FrameLayout.LayoutParams
   lp.leftMargin = mix(a.left, b.left); lp.topMargin = mix(a.top, b.top); lp.width = mix(a.width(), b.width()); lp.height = mix(a.height(), b.height())
   ov.layoutParams = lp
   albumPanel.alpha = if (fadeIn) min(1f, f * 1.8f) else max(0f, 1f - f * 1.3f)
  }
  anim.addListener(object : android.animation.AnimatorListenerAdapter() {
   override fun onAnimationCancel(an: android.animation.Animator) { cancelled = true }
   override fun onAnimationEnd(an: android.animation.Animator) {
    if (cancelled) return
    parent.removeView(ov); if (sharedOverlay === ov) sharedOverlay = null
    sharedAnim = null
    onEnd()
   }
  })
  sharedAnim = anim
  anim.start()
 }

 /** Back / close from the album: the cover photo shrinks back into its card when that is possible. */
 private fun closeTripAnimated() {
  val bmp = sharedBitmap; val a = sharedFrom; val ci = coverImage
  if (bmp == null || a == null || ci == null || !ci.isAttachedToWindow || ci.width == 0 || sharedAnim != null) { closeTrip(); return }
  val parent = albumPanel.parent as ViewGroup
  val b = rectInParent(ci, parent)
  if (b.bottom <= 0 || b.top >= parent.height) { closeTrip(); return }
  ci.alpha = 0f
  flyPhoto(bmp, b, a, false) { closeTrip() }
 }

 private fun closeTrip() {
  sharedAnim?.cancel(); sharedAnim = null
  sharedOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }; sharedOverlay = null
  sharedFrom = null; sharedBitmap = null; coverImage = null
  albumPanel.alpha = 1f
  albumPanel.visibility = View.GONE; albumPanel.removeAllViews(); openTrip = null; albumRefresh = null
 }

 private fun albumCover(t: Trip, days: List<TripDay>, plan: TripPlan, jump: (Int) -> Unit): View = LinearLayout(this).apply {
  orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(2), dp(2), dp(4))
  val top = FrameLayout(context)
  val img = photoBox().apply { background = Y2k.frame(ink, ink, 0, 0); setPadding(0, dp(2), 0, dp(2)) }
  coverImage = img
  loadThumb(img, t.cover.uri, resources.displayMetrics.widthPixels)
  top.addView(img, FrameLayout.LayoutParams(-1, -1))
  top.addView(Y2k.text(context, dateRange(t.start, t.end), 12f, Color.WHITE, true).apply { setBackgroundColor(ink); setPadding(dp(8), dp(4), dp(8), dp(4)) },
   FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(10)) })
  addView(top, LinearLayout.LayoutParams(-1, dp(190)))
  val titleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), dp(4), dp(8)) }
  titleRow.addView(Y2k.text(context, tripTitle(t), 24f, ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
  titleRow.addView(Y2k.text(context, "${Trips.dayCount(t)}일 · 사진 ${"%,d".format(t.photos.size)}장", 12f, Y2k.MUTED, true))
  addView(titleRow)
  // quiet text links: the photo and the trip title stay the first thing you see
  val editRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, 0, dp(4), dp(2)) }
  fun link(label: String, strong: Boolean = false, action: () -> Unit) = Y2k.text(context, label, 11f, if (strong) ink else Y2k.MUTED, false).apply {
   paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
   setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { action() }
  }
  editRow.addView(link("제목 바꾸기") { openTitleEditor(t) })
  editRow.addView(link(if (pickingCover) "대표사진 고르는 중 (취소)" else "대표사진 바꾸기", pickingCover) {
   pickingCover = !pickingCover
   if (pickingCover) Toast.makeText(this@MainActivity, "아래에서 대표로 쓸 사진을 눌러 주세요", Toast.LENGTH_LONG).show()
   albumRefresh?.invoke()
  })
  editRow.addView(link("숨기기") { hideTrip(t) })
  addView(editRow)
  addView(statRow(listOf("${Trips.dayCount(t)}일" to "여행 기간", "${plan.stops.size}곳" to "방문한 곳", "${"%,d".format(t.photos.size)}장" to "사진")),
   LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
  addView(Y2k.button(context, "책 만들기 ▶  (사진 고르기 · 글 · PDF)", true, 14f) { openBookPreview(t, days, plan) },
   LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12); rightMargin = dp(4) })
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

 private fun dayHeader(t: Trip, d: TripDay, plan: TripPlan): View = LinearLayout(this).apply {
  orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(14), dp(6), dp(8))
  val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
  top.addView(Y2k.text(context, "▶ ${d.dayNumber}일차 — ${SimpleDateFormat("M월 d일 (E)", Locale.KOREA).format(Date(d.dayStart))}", 14f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
  top.addView(Y2k.text(context, "${d.photos.size}장", 12f, Y2k.MUTED, true))
  addView(top)
  val where = stopNames((plan.dayRoutes[d.dayNumber] ?: emptyList()).map { plan.stops[it] })
  if (where.isNotEmpty()) addView(Y2k.text(context, where.joinToString(" → "), 13f, Y2k.MUTED, false).apply { setPadding(dp(14), dp(5), 0, 0) })
  val hasNote = tripBook(t).notes[d.dayNumber]?.isEmpty() == false
  val starCount = d.photos.count { it.id in tripBook(t).stars }
  val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), 0, 0) }
  row.addView(Y2k.button(context, if (hasNote) "글 고치기 ✎" else "글 쓰기 ✎", hasNote, 12f) { openNoteEditor(t, d) })
  row.addView(Y2k.text(context, if (starCount > 0) "  ★ ${starCount}장 선택" else "  사진 오른쪽 위 ☆로 고르기", 12f, Y2k.MUTED, true))
  addView(row)
  if (d.dayNumber == 1 && t.cutAt != null) {
   addView(Y2k.button(context, "◀ 앞 여행과 합치기", false, 12f) { mergeWithPrevious(t) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
  } else if (d.dayNumber > 1) {
   addView(Y2k.button(context, "여기서 여행 나누기", false, 12f) { splitTrip(t, d) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
  }
 }

// ---------- trip edits ----------
 /** Moves the stars, captions and day notes that belong to [toDays] from one trip's book to another's. */
 private fun moveBookData(from: TripBook, fromDays: List<TripDay>, to: TripBook, toDays: List<TripDay>) {
  val ids = toDays.flatMap { d -> d.photos.map { it.id } }.toSet()
  from.stars.filter { it in ids }.forEach { from.stars.remove(it); to.stars.add(it) }
  from.captions.keys.filter { it in ids }.forEach { id -> from.captions.remove(id)?.let { to.captions[id] = it } }
  val newNumber = toDays.associate { it.dayStart to it.dayNumber }
  fromDays.forEach { d ->
   val n = newNumber[d.dayStart]; val note = from.notes[d.dayNumber]
   if (n != null && note != null) { to.notes[n] = note; from.notes.remove(d.dayNumber) }
  }
 }

 private fun afterTripEdit(message: String, reopenKey: String? = null) {
  saveEdits(); saveBook()
  closeTrip()
  rebuild()
  Toast.makeText(this, message, Toast.LENGTH_LONG).show()
  if (reopenKey != null) trips.firstOrNull { it.key == reopenKey }?.let { openTrip(it) }
 }

 private fun splitTrip(t: Trip, d: TripDay) {
  val cut = d.dayStart
  val newKey = "${t.key}@$cut"
  val fromDays = Trips.days(t)
  val laterPhotos = t.photos.filter { it.date >= cut }
  val later = Trip(newKey, laterPhotos, t.lat, t.lng, t.abroad)
  moveBookData(tripBook(t), fromDays, tripBook(later), Trips.days(later))
  edits.cuts.add(cut)
  afterTripEdit("${d.dayNumber}일차부터 새 여행으로 나눴어요. 합치려면 뒤 여행 첫날의 '앞 여행과 합치기'를 눌러요")
 }

 private fun mergeWithPrevious(t: Trip) {
  val cut = t.cutAt ?: return
  val previous = trips.filter { it.baseKey == t.baseKey && (it.cutAt ?: Long.MIN_VALUE) < cut }.maxByOrNull { it.cutAt ?: Long.MIN_VALUE }
  if (previous != null) {
   val merged = Trip(previous.key, (previous.photos + t.photos).sortedWith(compareBy<PhotoPoint> { it.date }.thenBy { it.id }), previous.lat, previous.lng, previous.abroad)
   moveBookData(tripBook(t), Trips.days(t), tripBook(previous), Trips.days(merged))
  }
  edits.cuts.remove(cut)
  afterTripEdit("앞 여행과 합쳤어요", previous?.key)
 }

 private fun hideTrip(t: Trip) {
  edits.hidden.add(t.key)
  afterTripEdit("여행을 목록에서 숨겼어요. 사진은 그대로예요. 홈 맨 아래에서 되살릴 수 있어요")
 }

 private fun setCover(t: Trip, p: PhotoPoint) {
  pickingCover = false
  edits.covers[t.key] = p.id
  afterTripEdit("대표사진을 바꿨어요", t.key)
 }

 private fun openTitleEditor(t: Trip) {
  var dialog: android.app.Dialog? = null
  dialog = windowDialog("여행 제목") { body ->
   val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(12)) }
   col.addView(Y2k.text(this, "대표사진 아래와 책 표지에 이 제목이 들어가요. 비워 두면 장소 이름이 나와요.", 12f, Y2k.MUTED, true).apply { setPadding(0, 0, 0, dp(10)) })
   val et = android.widget.EditText(this).apply {
    setText(t.title ?: ""); textSize = 18f; typeface = Y2k.regular; setTextColor(ink); setHintTextColor(Y2k.MUTED); hint = tripTitle(t.copy(title = null))
    background = Y2k.frame(Color.WHITE, ink, 2, 0); setPadding(dp(10), dp(10), dp(10), dp(10)); maxLines = 2
    inputType = android.text.InputType.TYPE_CLASS_TEXT
   }
   col.addView(et, LinearLayout.LayoutParams(-1, -2))
   col.addView(Y2k.button(this, "저장", true, 15f) {
    val v = et.text.toString().trim()
    if (v.isEmpty()) edits.titles.remove(t.key) else edits.titles[t.key] = v
    dialog?.dismiss(); afterTripEdit("제목을 저장했어요", t.key)
   }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
   body.addView(android.widget.ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(-1, -1))
  }
  dialog.show()
 }

 private fun openHiddenList() {
  var dialog: android.app.Dialog? = null
  dialog = windowDialog("숨긴 여행") { body ->
   val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(12)) }
   col.addView(Y2k.text(this, "숨겨도 사진은 지워지지 않아요. 되살리면 목록에 다시 나와요.", 12f, Y2k.MUTED, true).apply { setPadding(0, 0, 0, dp(10)) })
   hiddenTrips.forEach { h ->
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
    row.addView(Y2k.text(this, "${tripTitle(h)}\n${dateRange(h.start, h.end)} · ${"%,d".format(h.photos.size)}장", 13f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(Y2k.button(this, "되살리기", true, 12f) {
     edits.hidden.remove(h.key); saveEdits(); dialog?.dismiss(); rebuild()
     Toast.makeText(this, "여행을 되살렸어요", Toast.LENGTH_SHORT).show()
    })
    col.addView(row)
   }
   body.addView(android.widget.ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(-1, -1))
  }
  dialog.show()
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
   val req = okhttp3.Request.Builder().url(TileNetwork.STYLE_URL).header("User-Agent", "TravelFootprints/0.24 (Android; com.example.travelfootprints)").build()
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
 private fun openViewer(t: Trip, photos: List<PhotoPoint>, startIndex: Int) {
  var index = startIndex
  val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
  val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
  val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
  frame.addView(image, FrameLayout.LayoutParams(-1, -1))
  val caption = Y2k.text(this, "", 14f, Color.WHITE, true).apply { setPadding(dp(18), dp(18), dp(18), dp(24)); setShadowLayer(4f, 0f, 0f, Color.BLACK) }
  frame.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
  frame.addView(Y2k.button(this, "× 닫기", false, 13f) { dialog.dismiss() },
   FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(28), dp(16), 0) })
  val layer = CaptionLayer(this)
  frame.addView(layer, FrameLayout.LayoutParams(-1, -1))
  frame.addView(Y2k.button(this, "글 달기 ✎", true, 13f) {
   openCaptionEditor(t, photos[index]) { layer.cap = tripBook(t).captions[photos[index].id]; layer.invalidate(); albumRefresh?.invoke() }
  }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(28), 0, 0) })
  val size = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
  val format = SimpleDateFormat("yyyy.MM.dd (E) HH:mm", Locale.KOREA)
  fun show() {
   val p = photos[index]
   layer.cap = tripBook(t).captions[p.id]; layer.bw = 0; layer.bh = 0; layer.invalidate()
   caption.text = "${index + 1} / ${photos.size}" + (if (p.date > 0) "   ${format.format(Date(p.date))}" else "")
   image.setImageDrawable(null)
   thumbWorker.execute {
    val bmp = try { contentResolver.loadThumbnail(Uri.parse(p.uri), android.util.Size(size, size), null) } catch (_: Exception) { null }
    runOnUiThread { if (photos[index] == p) { if (bmp != null) { image.setImageBitmap(bmp); layer.bw = bmp.width; layer.bh = bmp.height; layer.invalidate() } else image.setImageResource(android.R.drawable.ic_menu_gallery) } }
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

 // ---------- text on photos ----------
 private fun openCaptionEditor(t: Trip, p: PhotoPoint, onDone: () -> Unit) {
  var cap = tripBook(t).captions[p.id] ?: Caption()
  var dialog: android.app.Dialog? = null
  dialog = windowDialog("사진에 글 달기") { body ->
   val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(12)) }
   val box = FrameLayout(this).apply { background = Y2k.frame(ink, ink, 2, 0); setPadding(dp(2), dp(2), dp(2), dp(2)) }
   val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
   val layer = CaptionLayer(this)
   box.addView(img, FrameLayout.LayoutParams(-1, -1))
   box.addView(layer, FrameLayout.LayoutParams(-1, -1))
   col.addView(box, LinearLayout.LayoutParams(-1, dp(360)))
   thumbWorker.execute {
    val bmp = try { contentResolver.loadThumbnail(Uri.parse(p.uri), android.util.Size(1000, 1000), null) } catch (_: Exception) { null }
    runOnUiThread { if (bmp != null) { img.setImageBitmap(bmp); layer.bw = bmp.width; layer.bh = bmp.height; layer.invalidate() } }
   }
   fun preview() { layer.cap = cap; layer.invalidate() }
   var sizeBar: android.widget.SeekBar? = null
   var rawRot = cap.rot
   layer.onMove = { x, y -> cap = cap.copy(x = x, y = y); preview() }
   layer.onPinch = { k, deg ->
    val ns = (cap.size * k).coerceIn(0.03f, 0.17f)
    // keep the real angle apart from the shown one: snapping to 0 on every small step would swallow slow twists
    rawRot += deg
    while (rawRot > 180f) rawRot -= 360f
    while (rawRot < -180f) rawRot += 360f
    val r = if (kotlin.math.abs(rawRot) < 3f) 0f else rawRot
    cap = cap.copy(size = ns, rot = r)
    sizeBar?.progress = (((ns - 0.03f) / 0.14f) * 100f).toInt().coerceIn(0, 100)
    preview()
   }
   col.addView(Y2k.text(this, "한 손가락으로 끌어 옮기고, 두 손가락으로 벌리면 크기, 비틀면 회전돼요", 12f, Y2k.MUTED, true).apply { setPadding(dp(2), dp(8), 0, 0) })
   val et = android.widget.EditText(this).apply {
    setText(cap.text); textSize = 16f; typeface = Y2k.regular; setTextColor(ink); setHintTextColor(Y2k.MUTED); hint = "사진 위에 올릴 글"
    background = Y2k.frame(Color.WHITE, ink, 2, 0); setPadding(dp(10), dp(8), dp(10), dp(8)); maxLines = 4
    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
    addTextChangedListener(object : android.text.TextWatcher {
     override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
     override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { cap = cap.copy(text = s?.toString() ?: ""); preview() }
     override fun afterTextChanged(s: android.text.Editable?) {}
    })
   }
   col.addView(et, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
   // size: a slider from tiny to big (0.03 to 0.17 of the photo width)
   val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
   sizeRow.addView(Y2k.text(this, "크기", 13f, ink, true), LinearLayout.LayoutParams(dp(64), -2))
   sizeRow.addView(android.widget.SeekBar(this).apply {
    sizeBar = this
    max = 100; progress = (((cap.size - 0.03f) / 0.14f) * 100f).toInt().coerceIn(0, 100)
    setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
     override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) { cap = cap.copy(size = 0.03f + 0.14f * v / 100f); preview() } }
     override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
     override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
    })
   }, LinearLayout.LayoutParams(0, -2, 1f))
   col.addView(sizeRow)
   fun choices(label: String, names: List<String>, get: () -> Int, set: (Int) -> Unit) {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
    row.addView(Y2k.text(this, label, 13f, ink, true), LinearLayout.LayoutParams(dp(64), -2))
    val btns = ArrayList<TextView>()
    names.forEachIndexed { i, n ->
     val b = Y2k.button(this, n, i == get(), 13f) { set(i); btns.forEachIndexed { j, v -> v.background = Y2k.frame(if (j == i) Y2k.YELLOW else Y2k.GRAY, ink, 2, 2) }; preview() }
     btns.add(b); row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
    }
    col.addView(row)
   }
   choices("글자색", listOf("흰색", "검정"), { cap.tone }) { cap = cap.copy(tone = it) }
   choices("정렬", listOf("가운데", "왼쪽"), { cap.align }) { cap = cap.copy(align = it) }
   val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
   actions.addView(Y2k.button(this, "지우기", false, 14f) {
    tripBook(t).captions.remove(p.id); saveBook(); dialog?.dismiss(); onDone()
   }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
   actions.addView(Y2k.button(this, "저장", true, 14f) {
    if (cap.isEmpty()) tripBook(t).captions.remove(p.id) else tripBook(t).captions[p.id] = cap
    saveBook(); dialog?.dismiss(); onDone()
   }, LinearLayout.LayoutParams(0, -2, 1f))
   col.addView(actions)
   preview()
   body.addView(android.widget.ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(-1, -1))
  }
  dialog.show()
 }

 // ---------- book (photo book / essay) ----------
 private val exportWorker = Executors.newSingleThreadExecutor()

 private fun windowDialog(title: String, fill: (FrameLayout) -> Unit): android.app.Dialog {
  val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
  val w = Y2k.window(this, title, onBack = { dialog.dismiss() }, onClose = { dialog.dismiss() })
  fill(w.body)
  val frame = FrameLayout(this)
  frame.addView(Y2k.SkyView(this), FrameLayout.LayoutParams(-1, -1))
  frame.addView(w.root, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), dp(28), dp(8), dp(8)) })
  dialog.setContentView(frame)
  dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
  return dialog
 }

 private fun openNoteEditor(t: Trip, d: TripDay) {
  val old = tripBook(t).notes[d.dayNumber] ?: DayNote()
  val questions = listOf("이날 가장 기억나는 순간은?" to old.a, "예상과 달랐던 건?" to old.b, "나중에 다시 기억하고 싶은 건?" to old.c)
  val fields = ArrayList<android.widget.EditText>()
  var dialog: android.app.Dialog? = null
  dialog = windowDialog("${d.dayNumber}일차 글쓰기") { body ->
   val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(12)) }
   col.addView(Y2k.text(this, "AI가 대신 쓰지 않아요. 떠오르는 대로, 내 말투로 적어 주세요. 이 글이 책 그날 페이지에 들어가요.", 12f, Y2k.MUTED, true).apply { setPadding(0, 0, 0, dp(10)) })
   questions.forEach { (q, v) ->
    col.addView(Y2k.text(this, "▶ $q", 14f, ink, true).apply { setPadding(0, dp(8), 0, dp(6)) })
    val et = android.widget.EditText(this).apply {
     setText(v); textSize = 15f; typeface = Y2k.regular; setTextColor(ink); setHintTextColor(Y2k.MUTED); hint = "여기에 적기"
     background = Y2k.frame(Color.WHITE, ink, 2, 0); setPadding(dp(10), dp(8), dp(10), dp(8)); minLines = 3; gravity = Gravity.TOP or Gravity.START
     inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }
    fields.add(et); col.addView(et, LinearLayout.LayoutParams(-1, -2))
   }
   col.addView(Y2k.button(this, "저장", true, 15f) {
    val note = DayNote(fields[0].text.toString(), fields[1].text.toString(), fields[2].text.toString())
    if (note.isEmpty()) tripBook(t).notes.remove(d.dayNumber) else tripBook(t).notes[d.dayNumber] = note
    saveBook(); dialog?.dismiss(); albumRefresh?.invoke()
   }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
   body.addView(android.widget.ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(-1, -1))
  }
  dialog.show()
 }

 /** Decodes a photo (rotation applied) no larger than [maxPx] on its long side. */
 private fun decodePhoto(p: PhotoPoint, maxPx: Int): Bitmap? = try {
  val src = android.graphics.ImageDecoder.createSource(contentResolver, Uri.parse(p.uri))
  android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
   dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
   val big = max(info.size.width, info.size.height)
   if (big > maxPx) { val k = maxPx.toFloat() / big; dec.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1)) }
  }
 } catch (_: Exception) { null }

 private fun bookPages(t: Trip, days: List<TripDay>, plan: TripPlan): List<BookPage> {
  val book = tripBook(t)
  val dayFmt = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)
  val cover = (if (t.coverId != null) t.cover else null) ?: t.photos.filter { it.id in book.stars }.minByOrNull { it.date } ?: t.cover
  return Book.pages(tripTitle(t), dateRange(t.start, t.end), "${Trips.dayCount(t)}일", cover, days,
   { d -> "DAY ${d.dayNumber} · ${dayFmt.format(Date(d.dayStart))}" },
   { d -> stopNames((plan.dayRoutes[d.dayNumber] ?: emptyList()).map { plan.stops[it] }).filter { !it.startsWith("이름 찾는") && !it.startsWith("이동 중") }.joinToString(" · ") },
   book)
 }

 private fun renderPage(pages: List<BookPage>, i: Int, scale: Float, maxPx: Int, used: MutableList<Bitmap>, captions: Map<Long, Caption>): Bitmap {
  val bmp = Bitmap.createBitmap((BookRender.W * scale).toInt(), (BookRender.H * scale).toInt(), Bitmap.Config.ARGB_8888)
  val c = Canvas(bmp); c.scale(scale, scale)
  BookRender(captions) { p -> decodePhoto(p, maxPx)?.also { used.add(it) } }.draw(c, pages[i], i + 1, pages.size)
  return bmp
 }

 private fun openBookPreview(t: Trip, days: List<TripDay>, plan: TripPlan) {
  val pages = bookPages(t, days, plan)
  val starred = tripBook(t).stars.count { id -> t.photos.any { it.id == id } }
  val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(8)) }
  val dialog = windowDialog("책 미리보기 — ${tripTitle(t)}") { body ->
   val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
   col.addView(Y2k.text(this, "A5 세로 ${pages.size}쪽 · " + (if (starred > 0) "★ ${starred}장 선택" else "★이 없어서 날짜마다 3장씩 자동으로 넣었어요") + "\n위치(GPS) 정보는 책에 들어가지 않아요.", 12f, Y2k.MUTED, true).apply { setPadding(dp(6), dp(4), dp(6), dp(8)) })
   col.addView(android.widget.ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
   col.addView(Y2k.button(this, "PDF로 저장", true, 15f) { exportBookPdf(t, pages) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
   body.addView(col, FrameLayout.LayoutParams(-1, -1))
  }
  dialog.show()
  val widthPx = resources.displayMetrics.widthPixels - dp(56)
  pages.indices.forEach { i ->
   val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; background = Y2k.frame(Color.WHITE, ink, 2, 3); setPadding(dp(2), dp(2), dp(5), dp(5)) }
   list.addView(iv, LinearLayout.LayoutParams(widthPx, (widthPx * BookRender.H / BookRender.W).toInt()).apply { bottomMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL })
   thumbWorker.execute {
    val used = mutableListOf<Bitmap>()
    val bmp = try { renderPage(pages, i, 1.6f, 900, used, tripBook(t).captions) } catch (_: Throwable) { null }
    used.forEach { it.recycle() }
    runOnUiThread { if (bmp != null) iv.setImageBitmap(bmp) }
   }
  }
 }

 private fun exportBookPdf(t: Trip, pages: List<BookPage>) {
  Toast.makeText(this, "PDF 만드는 중… 잠시만요", Toast.LENGTH_SHORT).show()
  exportWorker.execute {
   var msg: String
   try {
    val doc = android.graphics.pdf.PdfDocument()
    pages.indices.forEach { i ->
     val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(BookRender.W.toInt(), BookRender.H.toInt(), i + 1).create())
     val used = mutableListOf<Bitmap>()
     BookRender(tripBook(t).captions) { p -> decodePhoto(p, 1400)?.also { used.add(it) } }.draw(page.canvas, pages[i], i + 1, pages.size)
     doc.finishPage(page)
     used.forEach { it.recycle() }
    }
    val name = tripTitle(t).replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_") + "_여행책"
    val values = android.content.ContentValues().apply {
     put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.pdf"); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
     put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/여행발자국")
    }
    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("저장 위치를 못 만들었어요")
    contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: throw IllegalStateException("파일 열기 실패")
    doc.close()
    msg = "저장했어요: 다운로드/여행발자국/$name.pdf"
   } catch (e: Throwable) { msg = "PDF 저장 실패: ${e.message}" }
   runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
  }
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
 private fun loadEdits() {
  try {
   val e = TripEdits.fromJson(openFileInput("trip_edits.json").bufferedReader().use { it.readText() })
   edits.cuts.addAll(e.cuts); edits.hidden.addAll(e.hidden); edits.titles.putAll(e.titles); edits.covers.putAll(e.covers)
  } catch (_: Exception) { }
 }
 private fun saveEdits() {
  try { openFileOutput("trip_edits.json", MODE_PRIVATE).bufferedWriter().use { it.write(edits.toJson()) } }
  catch (_: Exception) { Toast.makeText(this, "저장 실패", Toast.LENGTH_SHORT).show() }
 }
 private val bookStore = mutableMapOf<String, TripBook>()
 private fun tripBook(t: Trip) = bookStore.getOrPut(t.key) { TripBook() }
 private fun loadBook() {
  try { bookStore.putAll(Book.fromJson(openFileInput("book.json").bufferedReader().use { it.readText() })) } catch (_: Exception) { }
 }
 private fun saveBook() {
  try { openFileOutput("book.json", MODE_PRIVATE).bufferedWriter().use { it.write(Book.toJson(bookStore)) } }
  catch (_: Exception) { Toast.makeText(this, "저장 실패", Toast.LENGTH_SHORT).show() }
 }
 private fun toggleStar(t: Trip, id: Long) {
  val s = tripBook(t).stars
  if (!s.add(id)) s.remove(id)
  saveBook()
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
   listOf("points.json", "trip_decisions.json", "places.json", "book.json", "trip_edits.json").forEach { name ->
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
   listOf("points.json", "trip_decisions.json", "places.json", "book.json", "trip_edits.json").forEach { name ->
    if (backup.has(name)) openFileOutput(name, MODE_PRIVATE).bufferedWriter().use { it.write(backup.getString(name)) }
   }
   points.clear(); decisions.clear(); placeNames.clear(); bookStore.clear(); edits.cuts.clear(); edits.hidden.clear(); edits.titles.clear(); edits.covers.clear()
   loadDecisions(); loadPlaceNames(); loadBook(); loadEdits(); loadCache()
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
