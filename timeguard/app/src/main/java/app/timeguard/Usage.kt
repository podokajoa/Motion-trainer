package app.timeguard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import java.util.Calendar

object Usage {
    fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun hasPermission(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun feed(c: Context, acc: Accumulator, from: Long, to: Long) {
        if (to <= from) return
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return
        val events = try { usm.queryEvents(from, to) } catch (e: Exception) { return } ?: return
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            acc.onEvent(e.eventType, e.packageName, e.className, e.timeStamp)
        }
    }

    /** 자정 6시간 전부터 읽어서, 자정 전에 켜 둔 앱도 자정부터 센다. */
    fun today(c: Context, now: Long): Accumulator {
        val start = startOfDay(now)
        val acc = Accumulator(start)
        if (hasPermission(c)) feed(c, acc, start - 6 * 3600_000L, now)
        return acc
    }

    fun label(c: Context, pkg: String): String = try {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    fun icon(c: Context, pkg: String): Drawable? = try { c.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }

    /** 홈 화면에 아이콘이 있는 앱들 */
    fun launcherApps(c: Context): List<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return c.packageManager.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName }
            .filter { it != c.packageName }
            .distinct()
    }
}
