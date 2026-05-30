package id.webprint.bridge.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.webprint.bridge.data.SettingsRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val settings = SettingsRepository(context).load()
        if (settings.autoStart) {
            ServiceController(context).start()
        }
    }
}
