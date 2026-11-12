package sgnv.anubis.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import sgnv.anubis.app.settings.AppSettings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = AppSettings.prefs(context)
        val freeze = prefs.getBoolean(AppSettings.KEY_FREEZE_ON_BOOT, false)
        if (freeze || prefs.getBoolean(AppSettings.KEY_BACKGROUND_MONITORING, false)) {
            // Keep the process alive while waiting for Shizuku; the service owns all policy work.
            VpnMonitorService.start(context, freezeOnBoot = freeze)
        }
    }
}
