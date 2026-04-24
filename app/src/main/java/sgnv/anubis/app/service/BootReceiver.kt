package sgnv.anubis.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        // Reuse the shared settings accessor so boot flow reads the same keys as UI.
        val prefs = AppSettings.prefs(context)
        if (!prefs.getBoolean(AppSettings.KEY_FREEZE_ON_BOOT, false)) return

        val app = context.applicationContext as AnubisApp
        val shizukuManager = app.shizukuManager
        val repo = AppRepository(app.database.managedAppDao(), context)

        CoroutineScope(Dispatchers.IO).launch {
            // Wait for Shizuku to start after boot
            repeat(10) {
                if (shizukuManager.isAvailable() && shizukuManager.hasPermission()) {
                    shizukuManager.bindUserService()
                    kotlinx.coroutines.delay(300)
                    return@repeat
                }
                kotlinx.coroutines.delay(1000)
            }

            if (!shizukuManager.isAvailable()) return@launch

            // Freeze LOCAL + VPN_ONLY on boot (no VPN active)
            for (group in listOf(AppGroup.LOCAL, AppGroup.VPN_ONLY)) {
                val packages = repo.getPackagesByGroup(group)
                for (pkg in packages) {
                    if (shizukuManager.isAppInstalled(pkg)) {
                        val result = shizukuManager.freezeApp(pkg)
                        if (result.isFailure) {
                            AppLogger.e(TAG, "boot.freezeApp failed for package=$pkg", result.exceptionOrNull())
                        }
                    }
                }
            }

            // LOCAL_AUTO_UNFREEZE: VPN is off at boot, so these must be unfrozen to receive
            // notifications. If the previous session left them disabled, restore them now.
            val autoUnfreezePackages = repo.getPackagesByGroup(AppGroup.LOCAL_AUTO_UNFREEZE)
            for (pkg in autoUnfreezePackages) {
                if (shizukuManager.isAppInstalled(pkg) && shizukuManager.isAppFrozen(pkg)) {
                    val result = shizukuManager.unfreezeApp(pkg)
                    if (result.isFailure) {
                        AppLogger.e(TAG, "boot.unfreezeApp failed for package=$pkg", result.exceptionOrNull())
                    }
                }
            }
        }
    }

    private companion object {
        private const val TAG = "BootReceiver"
    }
}
