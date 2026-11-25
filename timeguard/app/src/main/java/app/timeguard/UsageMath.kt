package app.timeguard

/** UsageEvents.Event 타입 번호. 안드로이드 코드 없이 테스트하려고 숫자로 둔다. */
object Ev {
    const val RESUMED = 1
    const val PAUSED = 2
    const val SCREEN_OFF = 16
    const val KEYGUARD_SHOWN = 17
    const val SHUTDOWN = 26
}

/**
 * 오늘 각 앱을 화면에 띄워 둔 시간을 더한다.
 * 앞으로 나온 앱(RESUMED)이 바뀌거나, 그 화면이 멈추거나(PAUSED), 화면이 꺼지면 한 번의 사용이 끝난다.
 */
class Accumulator(val dayStart: Long) {
    val totals = HashMap<String, Long>()
    var current: String? = null
        private set
    private var currentClass: String? = null
    private var since = 0L

    fun onEvent(type: Int, pkg: String?, cls: String?, t: Long) {
        when (type) {
            Ev.RESUMED -> {
                if (pkg == null) return
                if (pkg == current) { currentClass = cls; return } // 같은 앱 안에서 화면만 바뀜
                close(t)
                current = pkg; currentClass = cls; since = t
            }
            Ev.PAUSED -> if (pkg != null && pkg == current && (cls == null || currentClass == null || cls == currentClass)) close(t)
            Ev.SCREEN_OFF, Ev.KEYGUARD_SHOWN, Ev.SHUTDOWN -> close(t)
        }
    }

    private fun close(t: Long) {
        val p = current ?: return
        val d = t - maxOf(since, dayStart)
        if (d > 0) totals[p] = (totals[p] ?: 0L) + d
        current = null; currentClass = null
    }

    fun totalNow(pkg: String, now: Long): Long {
        var v = totals[pkg] ?: 0L
        if (pkg == current) {
            val d = now - maxOf(since, dayStart)
            if (d > 0) v += d
        }
        return v
    }
}

data class Decision(val minutes: Long, val block: Boolean, val announceStep: Int?)

enum class Level { OK, NEAR, OVER }

object Rules {
    /** limitMin=0이면 제한 없음, everyMin=0이면 중간 알림 없음. snoozeMin은 '5분만 더'로 늘린 시간. */
    fun decide(totalMs: Long, limitMin: Int, everyMin: Int, snoozeMin: Int, announced: Int): Decision {
        val min = totalMs / 60_000
        val block = limitMin > 0 && min >= limitMin + snoozeMin
        val step = if (everyMin > 0) (min / everyMin).toInt() else 0
        val announce = if (!block && step > 0 && step > announced) step else null
        return Decision(min, block, announce)
    }

    /** 지금 시점에서 5분 더 쓰게 해 줄 때 필요한 snooze 값 */
    fun snoozeAfter(minutes: Long, limitMin: Int, extra: Int = 5): Int =
        (minutes - limitMin).toInt().coerceAtLeast(0) + extra

    fun level(minutes: Long, limitMin: Int): Level = when {
        limitMin <= 0 -> Level.OK
        minutes >= limitMin -> Level.OVER
        minutes * 10 >= limitMin * 8L -> Level.NEAR
        else -> Level.OK
    }

    fun fmt(min: Long): String {
        val h = min / 60; val m = min % 60
        return when {
            h == 0L -> "${m}분"
            m == 0L -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }
}
