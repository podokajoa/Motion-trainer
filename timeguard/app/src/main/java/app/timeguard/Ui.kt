package app.timeguard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 색은 뜻 하나씩만 쓴다: 초록=여유, 주황=거의 다 씀, 빨강=넘음.
 * 글자는 진한 잉크색과 보조 회색 두 단계만 쓴다(흰 바탕 대비 4.5:1 이상).
 */
object C {
    val INK = Color.parseColor("#111418")
    val SUB = Color.parseColor("#59626E")
    val LINE = Color.parseColor("#E4E7EB")
    val CARD = Color.parseColor("#F5F6F8")
    val GREEN = Color.parseColor("#137A3B")
    val ORANGE = Color.parseColor("#B85C00")
    val RED = Color.parseColor("#C62828")
    val NIGHT = Color.parseColor("#111418")
    val NIGHT_SUB = Color.parseColor("#A9B1BC")
    val NIGHT_RED = Color.parseColor("#FF7A70")
    val WARN_BG = Color.parseColor("#FFF6E0")

    fun of(l: Level) = when (l) { Level.OK -> GREEN; Level.NEAR -> ORANGE; Level.OVER -> RED }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.text(s: CharSequence, sp: Float, color: Int = C.INK, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = keepWords(s)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = false
        setLineSpacing(0f, 1.2f)
        // 한국어가 단어 중간("읽었/어요")에서 끊기지 않게 어절 단위로 줄바꿈
        if (Build.VERSION.SDK_INT >= 33) lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
    }

/**
 * 언어 설정과 상관없이 한국어가 어절 중간("읽었/어요")에서 끊기지 않도록
 * 띄어쓰기 없는 글자 사이에 '줄바꿈 금지' 표시(U+2060)를 넣는다. 화면에는 보이지 않는다.
 */
fun keepWords(s: CharSequence): CharSequence {
    if (s !is String || s.length < 2) return s
    val b = StringBuilder(s.length * 2)
    for (i in s.indices) {
        if (i > 0 && !s[i].isWhitespace() && !s[i - 1].isWhitespace()) b.append('\u2060')
        b.append(s[i])
    }
    return b.toString()
}

fun round(color: Int, radius: Float, stroke: Int? = null, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke != null) setStroke(strokeW, stroke)
}

fun Context.button(label: String, fill: Int, fg: Int, stroke: Int? = null, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(fg)
        background = round(fill, dp(14).toFloat(), stroke, dp(2))
        stateListAnimator = null
        minHeight = dp(56)
        setOnClickListener { onClick() }
    }

/** 두꺼운 진행 막대. 넘으면 꽉 찬 빨강. */
fun Context.bar(fraction: Float, color: Int): View {
    val f = fraction.coerceIn(0f, 1f)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = round(C.LINE, dp(5).toFloat())
        clipToOutline = true
        addView(View(context).apply { background = round(color, dp(5).toFloat()) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, f))
        addView(View(context), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - f))
    }
}

fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, top: Int = 0) =
    LinearLayout.LayoutParams(w, h).apply { topMargin = top }

/**
 * 제한을 넘었을 때 보던 앱 위에 뜨는 경고창(재난문자처럼).
 * 뒤 화면은 살짝 어둡게만 해서 '지금 보던 걸 멈추라'는 느낌이 들게 하고, 버튼을 눌러야 닫힌다.
 */
fun Context.blockView(
    app: String, minutes: Long, limitMin: Int, message: String, roast: String, snoozeCount: Int,
    onStop: () -> Unit, onSnooze: () -> Unit,
): View {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(Color.WHITE, dp(22).toFloat())
        clipToOutline = true
        elevation = dp(16).toFloat()

        // 빨간 머리띠: 경고라는 걸 글을 읽기 전에 색으로 먼저 알린다
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.RED)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(text("⚠ 시간 초과", 16f, Color.WHITE, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("시간 지킴이", 13f, Color.parseColor("#FFE3E0")))
        }, lp())

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(20))
            addView(text("오늘 $app", 16f, C.SUB, bold = true), lp())
            addView(text(Rules.fmt(minutes), 40f, C.RED, bold = true).apply {
                tag = "minutes"; maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(24, 40, 2, TypedValue.COMPLEX_UNIT_SP)
            }, lp(h = dp(52), top = dp(4)))
            addView(text(overText(minutes, limitMin), 15f, C.SUB).apply { tag = "over" }, lp(top = dp(2)))
            addView(View(context).apply { setBackgroundColor(C.LINE) }, lp(h = dp(1), top = dp(16)))
            addView(text(roast, 20f, C.INK, bold = true), lp(top = dp(16)))
            if (message.isNotBlank()) addView(text(message, 16f, C.SUB), lp(top = dp(10)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button(if (snoozeCount > 0) "5분만 더 (${snoozeCount}번째)" else "5분만 더", Color.WHITE, C.SUB, C.LINE) { onSnooze() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
                addView(button("그만할게", C.RED, Color.WHITE) { onStop() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
            }, lp(top = dp(20)))
        }, lp())
    }
    return FrameLayout(this).apply {
        setBackgroundColor(Color.parseColor("#66000000")) // 뒤 화면은 보이되 한 톤 어둡게
        addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            .apply { leftMargin = dp(18); rightMargin = dp(18) })
    }
}

fun overText(minutes: Long, limitMin: Int): String {
    val over = (minutes - limitMin).coerceAtLeast(0)
    val lim = Rules.fmt(limitMin.toLong())
    return if (over > 0) "제한 ${lim}에서 ${Rules.fmt(over)} 넘음" else "제한 $lim 다 씀"
}

/** 쓰는 동안 위에 떠 있는 작은 표시. 손가락 터치는 그대로 아래 앱으로 간다. */
fun Context.bubbleView(): TextView = text("", 15f, Color.WHITE, bold = true).apply {
    background = round(Color.parseColor("#E6111418"), dp(20).toFloat())
    setPadding(dp(16), dp(9), dp(16), dp(9))
}

fun bubbleText(app: String, minutes: Long, limitMin: Int): String =
    if (limitMin > 0) "$app ${Rules.fmt(minutes)} / ${Rules.fmt(limitMin.toLong())}" else "$app ${Rules.fmt(minutes)}째"

/** 정해 둔 간격마다 카톡 알림처럼 위에서 잠깐 뜨는 알림창 */
fun Context.bannerView(app: String, minutes: Long, limitMin: Int, level: Level): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = round(Color.WHITE, dp(18).toFloat())
        clipToOutline = true
        elevation = dp(10).toFloat()
        // 왼쪽 색 띠: 초록 여유 / 주황 거의 다 씀
        addView(View(context).apply { setBackgroundColor(C.of(level)) }, LinearLayout.LayoutParams(dp(6), LinearLayout.LayoutParams.MATCH_PARENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(18), dp(14))
            addView(text("⏱ 시간 지킴이 · 지금", 13f, C.SUB), lp())
            addView(text("$app ${Rules.fmt(minutes)} 지났어요", 19f, C.INK, bold = true), lp(top = dp(6)))
            if (limitMin > 0) {
                val left = (limitMin - minutes).coerceAtLeast(0)
                addView(text("제한까지 ${Rules.fmt(left)} 남음", 15f, C.of(level), bold = true), lp(top = dp(4)))
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
