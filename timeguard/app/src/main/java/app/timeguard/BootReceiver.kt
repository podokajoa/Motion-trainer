package app.timeguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 폰을 다시 켜거나 앱을 업데이트해도 지켜보기를 이어간다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { WatchService.start(c) }
}
