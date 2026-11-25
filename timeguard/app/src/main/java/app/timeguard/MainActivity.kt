package app.timeguard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch

class MainActivity : Activity() {
    /** 화면 한 줄에 필요한 것만 */
    data class Row(val watch: Watch, val name: String, val icon: Drawable?, val minutes: Long, val snoozeCount: Int)

    private val h = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var demo: String? = null
    private val refresh = object : Runnable {
        override fun run() { render(); h.postDelayed(this, 5000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        demo = intent.getStringExtra("demo")
        if (demo == "block") { showDemoBlock(); return }
        if (demo == "alert") { showDemoAlert(); return }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(48))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(root) })
        if (demo == null && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        if (demo == "block" || demo == "alert") return
        if (demo == null) WatchService.start(this)
        h.post(refresh)
    }

    override fun onPause() { super.onPause(); h.removeCallbacks(refresh) }

    // ---------- 데이터 ----------

    private fun rows(): List<Row> {
        if (demo != null) return demoRows()
        val now = System.currentTimeMillis()
        val acc = Usage.today(this, now)
        val day = Store.day(this, now)
        return Store.watches(this).map {
            Row(it, Usage.label(this, it.pkg), Usage.icon(this, it.pkg), acc.totalNow(it.pkg, now) / 60_000, day.snoozeCount[it.pkg] ?: 0)
        }
    }

    /** 위험한 것부터: 제한 대비 많이 쓴 순서, 제한 없는 앱은 뒤에 쓴 시간 순 */
    private fun sorted(list: List<Row>) = list.sortedWith(
        compareByDescending<Row> { if (it.watch.limitMin > 0) it.minutes.toDouble() / it.watch.limitMin else -1.0 }
            .thenByDescending { it.minutes })

    // ---------- 화면 ----------

    private fun render() {
        root.removeAllViews()
        val list = sorted(rows())
        val usageOk = demo != null || Usage.hasPermission(this)
        val overlayOk = demo != null || Settings.canDrawOverlays(this)

        root.addView(text("시간 지킴이", 15f, C.SUB, bold = true), lp())

        // 주인공: 오늘 합계 한 숫자
        val total = list.sumOf { it.minutes }
        root.addView(text("오늘 지켜보는 앱에 쓴 시간", 15f, C.SUB), lp(top = dp(22)))
        root.addView(text(if (usageOk) Rules.fmt(total) else "–", 44f, C.INK, bold = true), lp(top = dp(6)))
        if (usageOk && list.isNotEmpty()) root.addView(statusPill(list), lp(LinearLayout.LayoutParams.WRAP_CONTENT, top = dp(12)))

        if (!usageOk || !overlayOk) root.addView(permissionCard(usageOk, overlayOk), lp(top = dp(24)))

        if (list.isNotEmpty()) {
            root.addView(text("앱별", 15f, C.SUB, bold = true), lp(top = dp(30)))
            list.forEach { root.addView(card(it), lp(top = dp(10))) }
        } else if (usageOk) {
            root.addView(text("아직 지켜보는 앱이 없어요.\n자주 붙잡히는 앱부터 하나 골라 보세요.", 17f, C.INK), lp(top = dp(30)))
        }

        root.addView(button("+ 지켜볼 앱 추가", Color.WHITE, C.INK, C.INK) { if (demo == null) pickApp() }, lp(top = dp(16)))

        root.addView(optionRow(), lp(top = dp(28)))
        if (demo == null && !ignoringBattery()) root.addView(batteryRow(), lp(top = dp(12)))
        root.addView(text("사용 기록과 설정은 이 폰에만 저장돼요.", 13f, C.SUB), lp(top = dp(24)))
    }

    private fun statusPill(list: List<Row>): View {
        val over = list.count { Rules.level(it.minutes, it.watch.limitMin) == Level.OVER }
        val near = list.count { Rules.level(it.minutes, it.watch.limitMin) == Level.NEAR }
        val (msg, color) = when {
            over > 0 -> "제한 넘은 앱 ${over}개" to C.RED
            near > 0 -> "거의 다 쓴 앱 ${near}개" to C.ORANGE
            else -> "모두 여유 있어요" to C.GREEN
        }
        return text(msg, 15f, Color.WHITE, bold = true).apply {
            background = round(color, dp(16).toFloat())
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
    }

    private fun card(r: Row): View {
        val w = r.watch
        val level = Rules.level(r.minutes, w.limitMin)
        val color = C.of(level)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(C.CARD, dp(18).toFloat())
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setOnClickListener { if (demo == null) edit(w, r.name, isNew = false) }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageDrawable(r.icon ?: round(C.LINE, dp(10).toFloat())) },
                    LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
                addView(text(r.name, 18f, C.INK, bold = true).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(text(Rules.fmt(r.minutes), 28f, if (w.limitMin > 0) color else C.INK, bold = true))
            }, lp())

            if (w.limitMin > 0) {
                addView(bar(r.minutes.toFloat() / w.limitMin, color), lp(h = dp(10), top = dp(14)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val left = w.limitMin - r.minutes
                    val status = when (level) {
                        Level.OVER -> text("${Rules.fmt(-left)} 넘음" + if (r.snoozeCount > 0) " · ${r.snoozeCount}번 미룸" else "", 15f, C.RED, bold = true)
                        Level.NEAR -> text("${Rules.fmt(left)} 남음", 15f, C.ORANGE, bold = true)
                        Level.OK -> text("${Rules.fmt(left)} 남음", 15f, C.SUB)
                    }
                    addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text("제한 ${Rules.fmt(w.limitMin.toLong())}", 15f, C.SUB))
                }, lp(top = dp(10)))
            } else {
                addView(text("제한 없음 · ${if (w.everyMin > 0) "${w.everyMin}분마다 알림" else "알림 없음"}", 15f, C.SUB), lp(top = dp(10)))
            }
        }
    }

    private fun permissionCard(usageOk: Boolean, overlayOk: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(C.WARN_BG, dp(18).toFloat())
        setPadding(dp(18), dp(18), dp(18), dp(18))
        addView(text("켜야 할 권한", 18f, C.INK, bold = true), lp())
        if (!usageOk) {
            addView(text("앱마다 오늘 몇 분 썼는지 보려면 필요해요. 목록에서 '시간 지킴이'를 찾아 켜 주세요.", 15f, C.INK), lp(top = dp(10)))
            addView(button("사용 정보 접근 켜기", C.INK, Color.WHITE) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }, lp(top = dp(10)))
        }
        if (!overlayOk) {
            addView(text("쓰는 중인 앱 위에 시간과 경고 화면을 띄우려면 필요해요.", 15f, C.INK), lp(top = dp(16)))
            addView(button("다른 앱 위에 표시 켜기", C.INK, Color.WHITE) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }, lp(top = dp(10)))
        }
    }

    private fun optionRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("쓰는 동안 위에 시간 띄우기", 16f, C.INK, bold = true))
            addView(text("예: 유튜브 12분 / 30분", 14f, C.SUB), lp(top = dp(4)))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Switch(context).apply {
            isChecked = demo != null || Store.bubbleOn(context)
            setOnCheckedChangeListener { _, on -> if (demo == null) Store.setBubble(context, on) }
        })
    }

    private fun ignoringBattery() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun batteryRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text("알림이 가끔 안 온다면", 16f, C.INK, bold = true))
        addView(text("폰이 배터리 아끼려고 이 앱을 멈출 수 있어요.", 14f, C.SUB), lp(top = dp(4)))
        addView(button("배터리 제한 풀기", Color.WHITE, C.INK, C.LINE) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, lp(top = dp(10)))
    }

    // ---------- 앱 고르기 / 설정 ----------

    private fun pickApp() {
        val now = System.currentTimeMillis()
        val acc = Usage.today(this, now)
        val watched = Store.watches(this).map { it.pkg }.toSet()
        val apps = Usage.launcherApps(this).filter { it !in watched }
            .map { Triple(it, Usage.label(this, it), acc.totalNow(it, now) / 60_000) }
            .sortedWith(compareByDescending<Triple<String, String, Long>> { it.third }.thenBy { it.second })
        if (apps.isEmpty()) return
        val items = apps.map { if (it.third > 0) "${it.second}  ·  오늘 ${Rules.fmt(it.third)}" else it.second }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("어떤 앱을 지켜볼까요?")
            .setItems(items) { _, i ->
                val a = apps[i]
                edit(Watch(a.first, 30, 10, Store.DEFAULT_MESSAGE), a.second, isNew = true)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun numberField(value: Int): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        textSize = 20f
        setTextColor(C.INK)
    }

    private fun presets(field: EditText, values: List<Int>, labelOf: (Int) -> String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        values.forEach { v ->
            addView(text(labelOf(v), 15f, C.INK, bold = true).apply {
                gravity = Gravity.CENTER
                background = round(C.CARD, dp(12).toFloat(), C.LINE, dp(1))
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { field.setText(v.toString()) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
    }

    private fun edit(w: Watch, name: String, isNew: Boolean) {
        val limit = numberField(w.limitMin)
        val every = numberField(w.everyMin)
        val msg = EditText(this).apply {
            setText(w.message); hint = "예: 책 읽을 시간이에요"; textSize = 18f; setTextColor(C.INK)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(0))
            addView(text("하루 제한 (분)", 15f, C.SUB, bold = true), lp(top = dp(8)))
            addView(limit, lp())
            addView(presets(limit, listOf(15, 30, 60, 0)) { if (it == 0) "없음" else Rules.fmt(it.toLong()) }, lp(top = dp(4)))
            addView(text("몇 분마다 알려줄까요", 15f, C.SUB, bold = true), lp(top = dp(20)))
            addView(every, lp())
            addView(presets(every, listOf(5, 10, 20, 0)) { if (it == 0) "안 함" else "${it}분" }, lp(top = dp(4)))
            addView(text("제한 넘으면 보여줄 내 한마디", 15f, C.SUB, bold = true), lp(top = dp(20)))
            addView(msg, lp())
            addView(text("경고 화면엔 오늘 숫자로 만든 팩폭 한 줄이 같이 나와요.", 13f, C.SUB), lp(top = dp(4)))
        }
        val b = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(name)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                Store.upsert(this, w.copy(
                    limitMin = (limit.text.toString().toIntOrNull() ?: w.limitMin).coerceIn(0, 1440),
                    everyMin = (every.text.toString().toIntOrNull() ?: w.everyMin).coerceIn(0, 1440),
                    message = msg.text.toString().trim()))
                WatchService.start(this); render()
            }
            .setNegativeButton("취소", null)
        if (!isNew) b.setNeutralButton("빼기") { _, _ -> Store.remove(this, w.pkg); WatchService.start(this); render() }
        b.show()
    }

    // ---------- 화면 확인용 예시(실제 기록 아님) ----------

    private fun demoRows(): List<Row> {
        fun r(name: String, min: Long, limit: Int, every: Int, snooze: Int = 0) =
            Row(Watch(name, limit, every, Store.DEFAULT_MESSAGE), name, null, min, snooze)
        return listOf(r("유튜브", 72, 60, 10, 2), r("인스타그램", 25, 30, 10), r("웹툰", 8, 30, 10), r("카카오톡", 41, 0, 20))
    }

    private fun showDemoBlock() {
        val v = blockView("유튜브", 72, 60, "책 읽을 시간이에요",
            Roast.line("유튜브", 72, 2, intent.getIntExtra("seed", 1)), 2, onStop = {}, onSnooze = {})
        setContentView(FrameLayout(this).apply { addView(v) })
        window.statusBarColor = C.NIGHT
        window.navigationBarColor = C.NIGHT
        window.decorView.systemUiVisibility = 0
    }

    /** 다른 앱을 쓰는 중에 위에 뜨는 작은 표시와 알림 카드 모양 확인용 */
    private fun showDemoAlert() {
        val fake = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#2A2F36"))
            setPadding(dp(16), dp(120), dp(16), dp(16))
            repeat(6) { addView(View(context).apply { background = round(Color.parseColor("#3A4048"), dp(12).toFloat()) }, lp(h = dp(110), top = dp(14))) }
        }
        val frame = FrameLayout(this)
        frame.addView(fake)
        frame.addView(bubbleView().apply {
            text = bubbleText("유튜브", 21, 30)
            setCompoundDrawablesRelativeWithIntrinsicBounds(round(C.GREEN, dp(5).toFloat()).apply { setSize(dp(10), dp(10)) }, null, null, null)
            compoundDrawablePadding = dp(8)
            alpha = 0.8f
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(36) })
        frame.addView(bannerView("유튜브", 20, 30, Level.OK).apply { alpha = 0.8f },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(110) })
        setContentView(frame)
        window.statusBarColor = Color.parseColor("#2A2F36")
        window.decorView.systemUiVisibility = 0
    }
}
