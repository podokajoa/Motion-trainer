package app.timeguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.TextView

/** 2초마다 지금 화면에 떠 있는 앱을 보고, 오늘 쓴 시간을 확인해서 알리거나 막는다. */
class WatchService : Service() {
    companion object {
        private const val CH_RUN = "running"
        private const val CH_ALERT = "alerts"
        private const val ID_RUN = 1
        private const val ID_ALERT = 2
        private const val TICK = 2000L

        fun start(c: Context) {
            val i = Intent(c, WatchService::class.java)
            if (Store.watches(c).isEmpty() || !Usage.hasPermission(c)) { c.stopService(i); return }
            try { c.startForegroundService(i) } catch (e: Exception) { }
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var overlays: Overlays
    private var acc: Accumulator? = null
    private var lastQuery = 0L
    private var lastFull = 0L
    private var lastNotif = 0L
    private var day: DayState? = null
    private var running = false

    private val loop = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { }
            h.postDelayed(this, TICK)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlays = Overlays(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "지켜보는 중", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "시간 알림", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = runningNotification("오늘 쓴 시간을 확인하는 중")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID_RUN, n)
        if (!running) { running = true; h.post(loop) }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacks(loop)
        overlays.hideAll()
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    private fun runningNotification(text: String): Notification =
        Notification.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("시간 지킴이")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    private fun alertNotification(title: String, text: String) {
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_ALERT, n)
    }

    private fun buzz() {
        try {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                .vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val watches = Store.watches(this)
        if (watches.isEmpty() || !Usage.hasPermission(this)) { stopSelf(); return }

        // 날짜가 바뀌었거나 1분이 지나면 처음부터 다시 센다(놓친 기록 보정). 그 사이엔 새 기록만 더한다.
        val today = Store.dateKey(now)
        var a = acc
        if (a == null || day?.date != today || now - lastFull > 60_000) {
            a = Usage.today(this, now); acc = a; lastFull = now; lastQuery = now
        } else {
            Usage.feed(this, a, lastQuery, now); lastQuery = now
        }
        val d = if (day?.date == today) day!! else Store.day(this, now).also { day = it }

        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val fg = if (interactive) a.current else null
        val w = watches.firstOrNull { it.pkg == fg }

        if (now - lastNotif > 30_000) {
            lastNotif = now
            val summary = watches.map { it to a.totalNow(it.pkg, now) / 60_000 }
                .sortedByDescending { it.second }.take(3)
                .joinToString(" · ") { "${Usage.label(this, it.first.pkg)} ${Rules.fmt(it.second)}" }
            getSystemService(NotificationManager::class.java).notify(ID_RUN, runningNotification("오늘 $summary"))
        }

        if (w == null) { overlays.hideBubble(); overlays.hideBlock(); return }

        val total = a.totalNow(w.pkg, now)
        val snooze = d.snooze[w.pkg] ?: 0
        val count = d.snoozeCount[w.pkg] ?: 0
        val dec = Rules.decide(total, w.limitMin, w.everyMin, snooze, d.announced[w.pkg] ?: 0)
        val name = Usage.label(this, w.pkg)
        val level = Rules.level(dec.minutes, w.limitMin)

        if (dec.block) {
            if (!overlays.canDraw()) {
                if ((d.announced[w.pkg] ?: 0) != -1) {
                    d.announced[w.pkg] = -1; Store.saveDay(this, d)
                    alertNotification("$name 제한 시간이 지났어요", Roast.line(name, dec.minutes, count, (now / 60_000).toInt()))
                }
                return
            }
            val seed = (now / 60_000).toInt()
            // 화면을 처음 띄울 때 문장을 정하고, 그 뒤엔 숫자만 새로 그린다.
            overlays.showBlock(w.pkg, "$count") {
                blockView(name, dec.minutes, w.limitMin, w.message,
                    Roast.line(name, dec.minutes, count, seed), count,
                    onStop = { goHome(); overlays.hideBlock() },
                    onSnooze = {
                        d.snooze[w.pkg] = Rules.snoozeAfter(a.totalNow(w.pkg, System.currentTimeMillis()) / 60_000, w.limitMin)
                        d.snoozeCount[w.pkg] = count + 1
                        Store.saveDay(this, d)
                        overlays.hideBlock()
                    })
            }
            overlays.currentBlock()?.let { v ->
                v.findViewWithTag<TextView>("minutes")?.text = Rules.fmt(dec.minutes)
                v.findViewWithTag<TextView>("over")?.text = overText(dec.minutes, w.limitMin)
            }
            return
        }
        overlays.hideBlock()

        val step = dec.announceStep
        if (step != null) {
            d.announced[w.pkg] = step; Store.saveDay(this, d)
            buzz()
            if (overlays.canDraw()) overlays.showBanner(bannerView(name, dec.minutes, w.limitMin, level))
            else alertNotification("$name ${Rules.fmt(dec.minutes)} 지났어요",
                if (w.limitMin > 0) "제한까지 ${Rules.fmt((w.limitMin - dec.minutes).coerceAtLeast(0))} 남음" else "오늘 ${Rules.fmt(dec.minutes)}째")
        }

        if (Store.bubbleOn(this)) overlays.showBubble(bubbleText(name, dec.minutes, w.limitMin), C.of(level))
        else overlays.hideBubble()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
