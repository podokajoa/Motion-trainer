package com.example.travelfootprints

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Draws a [Caption] over a photo. The phone screens and the book PDF use this same code, so they match. */
object CaptionDraw {
 /**
  * [photo] is where the whole photo is drawn (it may stick out of [clip] when the photo is cropped);
  * the text is kept inside [clip].
  */
 fun draw(c: Canvas, cap: Caption, photo: RectF, clip: RectF) {
  if (cap.isEmpty()) return
  val black = cap.tone == 1
  val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
   textSize = (cap.size * photo.width()).coerceAtLeast(6f)
   color = if (black) Color.BLACK else Color.WHITE
   typeface = if (cap.size >= 0.085f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
   setShadowLayer(textSize * 0.14f, 0f, textSize * 0.05f, if (black) Color.argb(170, 255, 255, 255) else Color.argb(185, 0, 0, 0))
  }
  val maxW = max(1, (clip.width() * 0.92f).toInt())
  val align = if (cap.align == 1) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER
  val l = StaticLayout.Builder.obtain(cap.text, 0, cap.text.length, paint, maxW).setAlignment(align).setLineSpacing(0f, 1.12f).build()
  var w = 0f
  for (i in 0 until l.lineCount) w = max(w, l.getLineWidth(i))
  val h = l.height.toFloat()
  val m = clip.width() * 0.03f
  val left = (photo.left + cap.x * photo.width() - w / 2f).coerceIn(clip.left + m, max(clip.left + m, clip.right - m - w))
  val top = (photo.top + cap.y * photo.height() - h / 2f).coerceIn(clip.top + m, max(clip.top + m, clip.bottom - m - h))
  // centered text is laid out inside the wider box, so shift it back to where the visible block starts
  val shift = if (cap.align == 1) 0f else (maxW - w) / 2f
  c.save(); c.clipRect(clip); c.translate(left - shift, top); l.draw(c); c.restore()
 }
}

/**
 * Transparent layer on top of a photo shown "fit to screen". It draws the caption where it belongs
 * on the photo, and with [onMove] set the finger drags the text around.
 */
class CaptionLayer(context: Context) : View(context) {
 var cap: Caption? = null
 var bw = 0
 var bh = 0
 var onMove: ((Float, Float) -> Unit)? = null

 private fun fit(): RectF? {
  if (bw <= 0 || bh <= 0 || width <= 0 || height <= 0) return null
  val s = min(width / bw.toFloat(), height / bh.toFloat())
  val w = bw * s; val h = bh * s
  val l = (width - w) / 2f; val t = (height - h) / 2f
  return RectF(l, t, l + w, t + h)
 }

 override fun onDraw(canvas: Canvas) {
  val r = fit() ?: return
  cap?.let { CaptionDraw.draw(canvas, it, r, r) }
 }

 override fun onTouchEvent(e: MotionEvent): Boolean {
  val move = onMove ?: return false
  val r = fit() ?: return false
  if (e.action == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
  if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
   move(((e.x - r.left) / r.width()).coerceIn(0f, 1f), ((e.y - r.top) / r.height()).coerceIn(0f, 1f))
  }
  if (e.action == MotionEvent.ACTION_UP) performClick()
  return true
 }

 override fun performClick(): Boolean { super.performClick(); return true }
}
