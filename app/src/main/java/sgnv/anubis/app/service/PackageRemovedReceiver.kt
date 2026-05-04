package sgnv.anubis.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.repository.AppRepository

/**
 * Issue #64: when a managed app is uninstalled via system tools, drop its row from the
 * group list (otherwise the row keeps rendering as an empty placeholder) and disable
 * any pinned shortcut Anubis created — most launchers prune disabled shortcuts on next
 * refresh; the system already enforces that disabled pinned shortcuts can't be tapped.
 *
 * PACKAGE_FULLY_REMOVED fires only on real uninstall, never on app updates, so no
 * EXTRA_REPLACING check is needed.
 */
class PackageRemovedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val packageName = intent.data?.schemeSpecificPart ?: return

        val pending = goAsync()
        val app = context.applicationContext as AnubisApp
        val repo = AppRepository(app.database.managedAppDao(), context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.removeApp(packageName)
                context.getSystemService(ShortcutManager::class.java)
                    ?.disableShortcuts(listOf("stealth_$packageName"))
            } finally {
                pending.finish()
            }
        }
    }
}
