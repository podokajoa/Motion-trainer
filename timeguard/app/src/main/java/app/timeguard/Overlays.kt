package app.timeguard

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** '다른 앱 위에 표시' 권한으로 띄우는 세 가지 화면: 위쪽 작은 표시, 잠깐 뜨는 카드, 제한 화면. */
class Overlays(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val h = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var banner: View? = null
    private var block: View? = null
    var blockPkg: String? = null
        private set
    private var blockKey = ""

    fun canDraw() = Settings.canDrawOverlays(ctx)

    // 안드로이드 12부터는 터치가 지나가는 창의 불투명도가 0.8 이하여야 아래 앱이 터치를 받는다.
    private fun passThrough(gravity: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity; this.y = y; alpha = 0.8f }

    fun showBubble(text: String, dotColor: Int) {
        if (!canDraw()) return
        val v = bubble ?: ctx.bubbleView().also {
            try { wm.addView(it, passThrough(Gravity.TOP or Gravity.CENTER_HORIZONTAL, ctx.dp(36))); bubble = it } catch (e: Exception) { return }
        }
        v.text = text
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(dotColor), null, null, null)
        v.compoundDrawablePadding = ctx.dp(8)
    }

    private fun dot(color: Int) = round(color, ctx.dp(5).toFloat()).apply { setSize(ctx.dp(10), ctx.dp(10)) }

    fun hideBubble() { bubble?.let { remove(it) }; bubble = null }

    /** 카드는 또렷하게(불투명) 띄우고, 카드를 누르면 바로 닫힌다. 카드 밖 터치는 아래 앱으로 간다. */
    fun showBanner(v: View) {
        if (!canDraw()) return
        banner?.let { remove(it) }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = ctx.dp(32)
            width = ctx.resources.displayMetrics.widthPixels - ctx.dp(24)
        }
        v.setOnClickListener { if (banner === v) { remove(v); banner = null } }
        try { wm.addView(v, p); banner = v } catch (e: Exception) { return }
        h.postDelayed({ if (banner === v) { remove(v); banner = null } }, 6000)
    }

    /** key가 같으면 다시 그리지 않는다(문장이 계속 바뀌지 않게). */
    fun showBlock(pkg: String, key: String, build: () -> View) {
        if (!canDraw()) return
        if (block != null && blockPkg == pkg && blockKey == key) return
        hideBlock()
        val v = build()
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try { wm.addView(v, p); block = v; blockPkg = pkg; blockKey = key } catch (e: Exception) { }
        hideBubble()
    }

    private var blackout: View? = null
    fun isBlackout() = blackout != null

    /** 화면이 꺼진 것처럼 잠깐 새까맣게 덮는다. 켜져 있는 동안 터치는 막힌다. */
    fun flashBlackout(ms: Long) {
        if (!canDraw() || blackout != null) return
        val v = View(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        )
        try { wm.addView(v, p); blackout = v } catch (e: Exception) { return }
        h.postDelayed({ hideBlackout() }, ms)
    }

    fun hideBlackout() { blackout?.let { remove(it) }; blackout = null }

    fun currentBlock(): View? = block

    fun hideBlock() { block?.let { remove(it) }; block = null; blockPkg = null; blockKey = "" }

    fun hideAll() { hideBlackout(); hideBubble(); hideBlock(); banner?.let { remove(it) }; banner = null }

    private fun remove(v: View) { try { wm.removeView(v) } catch (e: Exception) { } }
}
