package sgnv.anubis.app.service

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.shizuku.shizukuUnavailableMessageRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Transparent activity launched from home-screen pinned shortcuts AND from the
 * `anubis://<packageName>` URL scheme (issue #9 — integration with launcher
 * widgets like AppFolderWidget, Tasker, etc.).
 *
 * Two entry paths:
 *  - Pinned shortcut: internal [android.content.Intent] with `package` / `group`
 *    extras, created by [sgnv.anubis.app.ui.MainViewModel.createShortcut].
 *  - URL: external `anubis://ru.ozon.app.android` — packageName comes from the
 *    URI host, group is always looked up via the repository. Unmanaged packages
 *    are rejected (finish without action) so arbitrary apps cannot unfreeze each
 *    other through this activity.
 */
class ShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent.data
        val isUrlLaunch = data?.scheme == URL_SCHEME
        val packageName = if (isUrlLaunch) data.host else intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) { finish(); return }

        val app = applicationContext as AnubisApp
        val shizukuManager = app.shizukuManager
        val vpnClientManager = app.vpnClientManager
        val repository = app.appRepository
        val orchestrator = app.orchestrator

        // Shortcut launches must use the same selected VPN client as the main app UI.
        val client = AppSettings.loadSelectedVpnClient(this)

        CoroutineScope(Dispatchers.Main).launch {
            val unavailableRes = shizukuUnavailableMessageRes(shizukuManager.status.value)
            if (unavailableRes != null) {
                Toast.makeText(this@ShortcutActivity, getString(unavailableRes), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            shizukuManager.awaitUserService()

            // Resolve group. Repository is authoritative; extras are a legacy fallback
            // for pinned shortcuts created before this change. URL-launch never falls
            // back to extras — if the package isn't managed, silently finish.
            val managedGroup = repository.getAppGroup(packageName)
            val group = when {
                managedGroup != null -> managedGroup
                isUrlLaunch -> { finish(); return@launch }
                else -> intent.getStringExtra(EXTRA_GROUP)
                    ?.let { runCatching { AppGroup.valueOf(it) }.getOrNull() }
                    ?: AppGroup.LAUNCH_VPN
            }

            when (group) {
                AppGroup.LOCAL, AppGroup.LOCAL_AUTO_UNFREEZE -> {
                    vpnClientManager.refreshVpnState()
                    vpnClientManager.detectActiveVpnClient()
                    val detectedPkg = vpnClientManager.activeVpnPackage.value
                    val detected = vpnClientManager.activeVpnClient.value
                    val stopClient = if (detected != null) {
                        AppSettings.loadSelectedVpnClient(this@ShortcutActivity, detected.packageName)
                    } else detectedPkg?.let {
                        AppSettings.loadSelectedVpnClient(this@ShortcutActivity, it)
                    } ?: client
                    orchestrator.launchLocal(packageName, stopClient, detectedPkg)
                }
                AppGroup.VPN_ONLY, AppGroup.LAUNCH_VPN -> {
                    orchestrator.launchWithVpn(packageName, client)
                }
            }

            finish()
        }
    }

    companion object {
        const val URL_SCHEME = "anubis"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_GROUP = "group"
    }
}
