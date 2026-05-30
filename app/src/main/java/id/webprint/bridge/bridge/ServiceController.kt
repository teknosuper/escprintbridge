package id.webprint.bridge.bridge

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ServiceController(private val context: Context) {

    fun start() {
        val intent = Intent(context, BridgeForegroundService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop() {
        val intent = Intent(context, BridgeForegroundService::class.java)
        context.stopService(intent)
    }

    @Suppress("DEPRECATION")
    fun isRunning(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == BridgeForegroundService::class.java.name }
    }
}
