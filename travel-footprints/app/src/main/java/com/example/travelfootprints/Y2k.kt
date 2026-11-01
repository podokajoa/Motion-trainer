package com.example.travelfootprints

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The look of the app: an old-computer desktop. Pixel font, sky-and-cloud wallpaper,
 * gray windows with blue title bars, hard black borders, no rounded corners.
 * Only a few colors on purpose, with big dark text so it stays easy to read.
 */
object Y2k {
 val INK = Color.parseColor("#0E1A24")
 val GRAY = Color.parseColor("#D9D4C8")
 val GRAY_DARK = Color.parseColor("#CFC9BB")
 val PAPER = Color.parseColor("#EFECE2")
 val BLUE = Color.parseColor("#1C5FA8")
 val YELLOW = Color.parseColor("#FFD84A")
 val MUTED = Color.parseColor("#45525C")
 val SHADOW = Color.argb(115,14,26,36)

 private var density = 1f
 lateinit var regular: Typeface
 lateinit var bold: Typeface

 fun init(context: Context) {
  density = context.resources.displayMetrics.density
  regular = try { Typeface.createFromAsset(context.assets,"fonts/Galmuri-Regular.ttf") } catch (_: Exception) { Typeface.DEFAULT }
  bold = try { Typeface.createFromAsset(context.assets,"fonts/Galmuri-Bold.ttf") } catch (_: Exception) { Typeface.DEFAULT_BOLD }
 }

 fun dp(v: Int): Int = (v * density + 0.5f).toInt()
 fun dpf(v: Float): Float = v * density

 /** A hard-edged box: border, flat fill, and an offset dark shadow (no rounding anywhere). */
 class PixelFrame(private val fill: Int, private val border: Int, private val borderPx: Float, private val shadowPx: Float) : Drawable() {
  private val paint = Paint()
  override fun draw(canvas: Canvas) {
   val b = bounds
   val l = b.left.toFloat(); val t = b.top.toFloat()
   val r = b.right - shadowPx; val bt = b.bottom - shadowPx
   if (shadowPx > 0f) { paint.color = SHADOW; canvas.drawRect(l + shadowPx, t + shadowPx, r + shadowPx, bt + shadowPx, paint) }
   if (borderPx > 0f) { paint.color = border; canvas.drawRect(l, t, r, bt, paint) }
   paint.color = fill
   canvas.drawRect(l + borderPx, t + borderPx, r - borderPx, bt - borderPx, paint)
  }
  override fun setAlpha(alpha: Int) {}
  override fun setColorFilter(colorFilter: ColorFilter?) {}
  @Deprecated("Deprecated in Java")
  override fun getOpacity() = PixelFormat.TRANSLUCENT
 }

 fun frame(fill: Int, border: Int = INK, borderDp: Int = 2, shadowDp: Int = 0): Drawable =
  PixelFrame(fill, border, dpf(borderDp.toFloat()), dpf(shadowDp.toFloat()))

 fun text(context: Context, value: String, sizeSp: Float, color: Int = INK, isBold: Boolean = false): TextView =
  TextView(context).apply {
   text = value; textSize = sizeSp; setTextColor(color)
   typeface = if (isBold) bold else regular
   includeFontPadding = false
  }

 /** Text that sits straight on the wallpaper: dark with a white outline-ish shadow so it reads on clouds. */
 fun onSky(view: TextView): TextView = view.apply { setShadowLayer(1f, dpf(1.5f), dpf(1.5f), Color.argb(230,255,255,255)) }

 fun button(context: Context, label: String, on: Boolean = false, sizeSp: Float = 13f, onClick: (() -> Unit)? = null): TextView =
  text(context, label, sizeSp, INK, true).apply {
   gravity = Gravity.CENTER
   background = frame(if (on) YELLOW else GRAY, INK, 2, 2)
   setPadding(dp(11), dp(7), dp(13), dp(9))
   if (onClick != null) setOnClickListener { onClick() }
  }

 /** A small square decoration for the title bar (minimize, maximize, close). */
 private fun titleKey(context: Context, label: String, onClick: (() -> Unit)?): TextView =
  text(context, label, 11f, INK, true).apply {
   gravity = Gravity.CENTER
   background = frame(GRAY, INK, 2, 0)
   if (onClick != null) setOnClickListener { onClick() }
  }

 class WindowParts(val root: LinearLayout, val body: FrameLayout)

 /**
  * A gray window with a blue title bar. [onBack] / [onClose] make the left arrow / × work;
  * the other keys are decoration. Content goes into [WindowParts.body].
  */
 fun window(context: Context, title: String, onBack: (() -> Unit)? = null, onClose: (() -> Unit)? = null): WindowParts {
  val root = LinearLayout(context).apply {
   orientation = LinearLayout.VERTICAL
   background = frame(GRAY, INK, 2, 4)
   setPadding(dp(2), dp(2), dp(2) + dp(4), dp(2) + dp(4))
  }
  val bar = LinearLayout(context).apply {
   orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
   setBackgroundColor(BLUE); setPadding(dp(8), dp(6), dp(6), dp(6))
  }
  if (onBack != null) bar.addView(text(context, "◀", 13f, Color.WHITE, true).apply {
   setPadding(0, 0, dp(8), 0); setOnClickListener { onBack() }
  })
  bar.addView(text(context, title, 14f, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
   LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
  listOf("_", "□", "×").forEachIndexed { i, label ->
   bar.addView(titleKey(context, label, if (i == 2) onClose else null),
    LinearLayout.LayoutParams(dp(20), dp(20)).apply { leftMargin = dp(4) })
  }
  root.addView(bar, LinearLayout.LayoutParams(-1, -2))
  root.addView(View(context).apply { setBackgroundColor(INK) }, LinearLayout.LayoutParams(-1, dp(2)))
  val body = FrameLayout(context)
  root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
  return WindowParts(root, body)
 }

 fun pressableLabel(context: Context, value: String, sizeSp: Float): TextView =
  text(context, value, sizeSp, INK, true).apply {
   gravity = Gravity.CENTER
   maxLines = 2; ellipsize = TextUtils.TruncateAt.END
   setPadding(dp(6), dp(3), dp(6), dp(3))
   val pressedBg = ColorDrawable(BLUE)
   background = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
   }
   isDuplicateParentStateEnabled = true
   setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(Color.WHITE, INK)))
   setShadowLayer(1f, dpf(1.5f), dpf(1.5f), Color.argb(230,255,255,255))
  }

 /** Wallpaper: sky gradient, chunky clouds and faint scanlines, drawn small and scaled up without smoothing. */
 class SkyView(context: Context) : View(context) {
  private var bmp: Bitmap? = null
  private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
  private val dst = Rect()
  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
   super.onSizeChanged(w, h, oldw, oldh)
   if (w <= 0 || h <= 0) return
   val lw = 130
   val lh = (lw * h / w.toFloat()).toInt().coerceAtLeast(40)
   val b = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
   val c = Canvas(b)
   val p = Paint()
   p.shader = LinearGradient(0f, 0f, 0f, lh.toFloat(), intArrayOf(Color.parseColor("#5FBDE4"), Color.parseColor("#9ADBF2"), Color.parseColor("#CDEDF8")), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
   c.drawRect(0f, 0f, lw.toFloat(), lh.toFloat(), p)
   p.shader = null
   p.color = Color.argb(235, 255, 255, 255)
   fun cloud(cx: Float, cy: Float, r: Float) {
    listOf(floatArrayOf(0f, 0f, r), floatArrayOf(r * .8f, r * .2f, r * .75f), floatArrayOf(-r * .85f, r * .25f, r * .65f), floatArrayOf(r * .3f, -r * .5f, r * .6f))
     .forEach { c.drawCircle(cx + it[0], cy + it[1], it[2], p) }
   }
   var y = 28f; var i = 0
   while (y < lh) { cloud(if (i % 2 == 0) 28f else 98f, y, 10f + (i * 3 % 6)); y += lh / 6.2f; i++ }
   p.color = Color.argb(56, 255, 255, 255)
   var row = 0
   while (row < lh) { c.drawRect(0f, row.toFloat(), lw.toFloat(), row + 1f, p); row += 2 }
   bmp = b
  }
  override fun onDraw(canvas: Canvas) {
   val b = bmp ?: return
   dst.set(0, 0, width, height)
   canvas.drawBitmap(b, null, dst, paint)
  }
 }
}
