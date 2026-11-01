package com.example.travelfootprints

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Draws one book page (A5 portrait, 420 x 595 pt) onto any canvas: the phone preview and the
 * PDF use the same code. No GPS or other location data is ever drawn, only photos and text.
 */
class BookRender(private val captions: Map<Long, Caption>, private val loader: (PhotoPoint) -> Bitmap?) {
 companion object { const val W = 420f; const val H = 595f; private const val M = 30f }

 private val paper = Color.parseColor("#FBF8F0")
 private val ink = Color.parseColor("#1B1B1B")
 private val grey = Color.parseColor("#6B6B6B")

 private fun tp(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
  textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
 }

 private fun photo(c: Canvas, p: PhotoPoint?, r: RectF) {
  val fill = Paint().apply { color = Color.parseColor("#E6E1D4") }
  c.drawRect(r, fill)
  val bmp = p?.let { loader(it) }
  if (bmp != null) drawCover(c, bmp, r)
  p?.let { captions[it.id] }?.let { overlay(c, it, r) }
 }

 private fun drawCover(c: Canvas, bmp: Bitmap, r: RectF) {
  val rw = r.width() / r.height(); val bw = bmp.width.toFloat() / bmp.height
  val src = if (bw > rw) { val w = (bmp.height * rw).toInt(); val x = (bmp.width - w) / 2; Rect(x, 0, x + w, bmp.height) }
   else { val h = (bmp.width / rw).toInt(); val y = (bmp.height - h) / 2; Rect(0, y, bmp.width, y + h) }
  c.drawBitmap(bmp, src, r, Paint(Paint.FILTER_BITMAP_FLAG))
 }

 /** The user's line of text on top of a photo; same look as on the phone. */
 private fun overlay(c: Canvas, cap: Caption, r: RectF) {
  if (cap.isEmpty()) return
  val factor = floatArrayOf(0.065f, 0.095f, 0.14f)[cap.size.coerceIn(0, 2)]
  val black = cap.tone == 1
  val paint = tp(r.width() * factor, if (black) Color.BLACK else Color.WHITE, true)
  paint.setShadowLayer(paint.textSize * 0.2f, 0f, paint.textSize * 0.06f, if (black) Color.argb(170, 255, 255, 255) else Color.argb(180, 0, 0, 0))
  val w = (r.width() * 0.84f).toInt()
  val l = StaticLayout.Builder.obtain(cap.text, 0, cap.text.length, paint, w).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.1f).build()
  val x = r.left + (r.width() - w) / 2f
  val y = when (cap.pos) {
   0 -> r.top + r.height() * 0.08f
   1 -> r.top + (r.height() - l.height) / 2f
   else -> r.bottom - l.height - r.height() * 0.08f
  }
  c.save(); c.clipRect(r); c.translate(x, y); l.draw(c); c.restore()
 }

 /** Draws wrapped text inside [r]; whatever does not fit is cut off. Returns nothing, the preview shows it. */
 private fun text(c: Canvas, s: String, paint: TextPaint, r: RectF, spacing: Float = 1.45f) {
  if (s.isBlank()) return
  val l = StaticLayout.Builder.obtain(s, 0, s.length, paint, r.width().toInt()).setLineSpacing(0f, spacing).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
  c.save(); c.clipRect(r); c.translate(r.left, r.top); l.draw(c); c.restore()
 }

 fun draw(c: Canvas, p: BookPage, pageNo: Int, total: Int) {
  c.drawColor(paper)
  when (p.kind) {
   PageKind.COVER -> {
    photo(c, p.hero, RectF(M, 40f, W - M, 400f))
    text(c, p.heading, tp(32f, ink, true), RectF(M, 425f, W - M, 480f))
    text(c, p.sub, tp(12f, grey), RectF(M, 490f, W - M, 530f))
    text(c, "여행 발자국", tp(9f, grey), RectF(M, H - 40f, W - M, H - 20f))
   }
   PageKind.DAY -> {
    text(c, p.heading, tp(10f, grey, true), RectF(M, 34f, W - M, 50f))
    if (p.sub.isNotBlank()) text(c, p.sub, tp(18f, ink, true), RectF(M, 52f, W - M, 100f), 1.2f)
    val hasSmall = p.small.isNotEmpty()
    if (p.hero != null) {
     photo(c, p.hero, RectF(M, 108f, W - M, 338f))
     val textBottom = if (hasSmall) 472f else 548f
     text(c, p.text, tp(11f, ink), RectF(M, 352f, W - M, textBottom))
     if (hasSmall) {
      val gap = 10f; val cw = (W - 2 * M - gap) / 2
      p.small.forEachIndexed { i, ph -> photo(c, ph, RectF(M + i * (cw + gap), 484f, M + i * (cw + gap) + cw, 556f)) }
     }
    } else text(c, p.text, tp(13f, ink), RectF(M, 112f, W - M, 548f), 1.55f)
   }
   PageKind.MORE -> {
    text(c, p.heading, tp(10f, grey, true), RectF(M, 34f, W - M, 50f))
    val gap = 10f; val cw = (W - 2 * M - gap) / 2; val ch = 215f
    p.small.forEachIndexed { i, ph ->
     val x = M + (i % 2) * (cw + gap); val y = 66f + (i / 2) * (ch + gap)
     photo(c, ph, RectF(x, y, x + cw, y + ch))
    }
   }
  }
  val num = tp(8f, grey).apply { textAlign = Paint.Align.CENTER }
  c.drawText("$pageNo / $total", W / 2, H - 14f, num)
 }
}
