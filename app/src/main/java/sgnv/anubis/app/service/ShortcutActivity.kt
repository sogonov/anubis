package sgnv.anubis.app.service

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.vpn.VpnClientManager
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        val packageName = if (isUrlLaunch) data?.host else intent.getStringExtra("package")
        if (packageName.isNullOrBlank()) { finish(); return }

        val app = applicationContext as AnubisApp
        val shizukuManager = app.shizukuManager
        val vpnClientManager = VpnClientManager(this, shizukuManager)
        val repository = AppRepository(app.database.managedAppDao(), this)
        val orchestrator = StealthOrchestrator(this, shizukuManager, vpnClientManager, repository)

        // Shortcut launches must use the same selected VPN client as the main app UI.
        val prefs = AppSettings.prefs(this)
        val pkg = prefs.getString(AppSettings.KEY_VPN_CLIENT_PACKAGE, null)
            ?: VpnClientType.V2RAY_NG.packageName
        val client = SelectedVpnClient.fromPackage(pkg)

        // Ensure UserService is bound (instant if already connected)
        shizukuManager.bindUserService()
        vpnClientManager.startMonitoringVpn()

        CoroutineScope(Dispatchers.Main).launch {
            // Brief delay for UserService callback
            delay(200)

            // Resolve group. Repository is authoritative; extras are a legacy fallback
            // for pinned shortcuts created before this change. URL-launch never falls
            // back to extras — if the package isn't managed, silently finish.
            val managedGroup = repository.getAppGroup(packageName)
            val group = when {
                managedGroup != null -> managedGroup
                isUrlLaunch -> { finish(); return@launch }
                else -> intent.getStringExtra("group")
                    ?.let { runCatching { AppGroup.valueOf(it) }.getOrNull() }
                    ?: AppGroup.LAUNCH_VPN
            }

            when (group) {
                AppGroup.LOCAL, AppGroup.LOCAL_AUTO_UNFREEZE -> {
                    vpnClientManager.refreshVpnState()
                    vpnClientManager.detectActiveVpnClient()
                    val detectedPkg = vpnClientManager.activeVpnPackage.value
                    val detected = vpnClientManager.activeVpnClient.value
                    val stopClient = if (detected != null) SelectedVpnClient.fromKnown(detected)
                        else detectedPkg?.let { SelectedVpnClient.fromPackage(it) } ?: client
                    orchestrator.launchLocal(packageName, stopClient, detectedPkg)
                }
                AppGroup.VPN_ONLY, AppGroup.LAUNCH_VPN -> {
                    orchestrator.launchWithVpn(packageName, client)
                }
            }

            vpnClientManager.stopMonitoringVpn()
            finish()
        }
    }

    companion object {
        const val URL_SCHEME = "anubis"
    }
}
