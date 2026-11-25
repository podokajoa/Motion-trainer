package app.timeguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 지켜볼 앱 하나의 설정. limitMin=0 제한 없음, everyMin=0 중간 알림 없음. */
data class Watch(val pkg: String, val limitMin: Int, val everyMin: Int, val message: String)

/** 오늘 하루 동안만 의미 있는 기록(알린 단계, 미룬 시간). 날짜가 바뀌면 새로 시작한다. */
class DayState(
    val date: String,
    val announced: MutableMap<String, Int> = HashMap(),
    val snooze: MutableMap<String, Int> = HashMap(),
    val snoozeCount: MutableMap<String, Int> = HashMap(),
)

/** 모든 기록은 이 폰의 SharedPreferences에만 둔다. 밖으로 보내지 않는다. */
object Store {
    const val DEFAULT_MESSAGE = "잠깐 내려놓을 시간이에요"

    private fun p(c: Context) = c.getSharedPreferences("timeguard", Context.MODE_PRIVATE)

    fun dateKey(now: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    fun watches(c: Context): List<Watch> = try {
        val a = JSONArray(p(c).getString("watches", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Watch(o.getString("pkg"), o.optInt("limit", 30), o.optInt("every", 10), o.optString("msg", DEFAULT_MESSAGE))
        }
    } catch (e: Exception) { emptyList() }

    fun saveWatches(c: Context, list: List<Watch>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("pkg", it.pkg).put("limit", it.limitMin).put("every", it.everyMin).put("msg", it.message)) }
        p(c).edit().putString("watches", a.toString()).apply()
    }

    fun upsert(c: Context, w: Watch) {
        val list = watches(c).filter { it.pkg != w.pkg } + w
        saveWatches(c, list)
    }

    fun remove(c: Context, pkg: String) = saveWatches(c, watches(c).filter { it.pkg != pkg })

    fun bubbleOn(c: Context) = p(c).getBoolean("bubble", true)
    fun setBubble(c: Context, on: Boolean) = p(c).edit().putBoolean("bubble", on).apply()

    fun day(c: Context, now: Long): DayState {
        val today = dateKey(now)
        return try {
            val o = JSONObject(p(c).getString("day", "{}") ?: "{}")
            if (o.optString("date") != today) DayState(today)
            else DayState(today, map(o.optJSONObject("announced")), map(o.optJSONObject("snooze")), map(o.optJSONObject("snoozeCount")))
        } catch (e: Exception) { DayState(today) }
    }

    fun saveDay(c: Context, d: DayState) {
        val o = JSONObject().put("date", d.date)
            .put("announced", JSONObject(d.announced as Map<*, *>))
            .put("snooze", JSONObject(d.snooze as Map<*, *>))
            .put("snoozeCount", JSONObject(d.snoozeCount as Map<*, *>))
        p(c).edit().putString("day", o.toString()).apply()
    }

    private fun map(o: JSONObject?): MutableMap<String, Int> {
        val m = HashMap<String, Int>()
        if (o != null) o.keys().forEach { k -> m[k] = o.optInt(k) }
        return m
    }
}
