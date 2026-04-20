package sgnv.anubis.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import sgnv.anubis.app.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VpnClientManager(
    private val context: Context,
    private val shizukuManager: ShizukuManager
) {

    private val _vpnActive = MutableStateFlow(false)
    val vpnActive: StateFlow<Boolean> = _vpnActive

    private val _activeVpnClient = MutableStateFlow<VpnClientType?>(null)
    val activeVpnClient: StateFlow<VpnClientType?> = _activeVpnClient

    /** Raw package name of active VPN app (even if not in our known list) */
    private val _activeVpnPackage = MutableStateFlow<String?>(null)
    val activeVpnPackage: StateFlow<String?> = _activeVpnPackage

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun getInstalledClients(): List<VpnClientType> {
        return VpnClientType.entries.filter { isInstalled(it) }
    }

    fun isInstalled(type: VpnClientType): Boolean {
        return try {
            context.packageManager.getApplicationInfo(type.packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Start VPN via Shizuku shell command.
     * For TOGGLE: sends toggle only if VPN is currently off.
     * For MANUAL: just opens the app.
     */
    suspend fun startVPN(client: SelectedVpnClient): String? {
        val knownType = client.knownType
        if (knownType != null) {
            val control = VpnClientControls.getControlForClient(client)
            when (control.mode) {
                VpnControlMode.SEPARATE -> {
                    val cmd = control.buildStartCommand(client)
                    if (cmd == null) {
                        val controlError = control.buildStartCommandFailureMessage(client)
                        if (controlError != null) {
                            Log.w(TAG, controlError)
                            return controlError
                        }
                        launchApp(client.packageName)
                        return null
                    }
                    // Shell commands need UserService — binder freeze before us didn't.
                    shizukuManager.awaitUserService(500)
                    val result = shizukuManager.execShellCommand(*cmd)
                    if (result.isFailure) {
                        Log.w(TAG, "Start failed for ${client.displayName}", result.exceptionOrNull())
                        launchApp(client.packageName)
                    }
                }
                VpnControlMode.TOGGLE -> {
                    if (!_vpnActive.value) {
                        val cmd = control.buildStartCommand(client)
                        if (cmd == null) {
                            val controlError = control.buildStartCommandFailureMessage(client)
                            if (controlError != null) {
                                Log.w(TAG, controlError)
                                return controlError
                            }
                            launchApp(client.packageName)
                            return null
                        }
                        shizukuManager.awaitUserService(500)
                        val result = shizukuManager.execShellCommand(*cmd)
                        if (result.isFailure) {
                            Log.w(TAG, "Toggle-start failed for ${client.displayName}", result.exceptionOrNull())
                            launchApp(client.packageName)
                        }
                    }
                }
                VpnControlMode.MANUAL -> launchApp(client.packageName)
            }
        } else {
            // Custom / unknown client — just open it
            launchApp(client.packageName)
        }
        return null
    }

    suspend fun stopVPN(client: SelectedVpnClient) {
        val knownType = client.knownType
        if (knownType != null) {
            val control = VpnClientControls.getControlForClient(client)
            when (control.mode) {
                VpnControlMode.SEPARATE -> {
                    val cmd = control.buildStopCommand(client) ?: return
                    shizukuManager.awaitUserService(500)
                    val result = shizukuManager.execShellCommand(*cmd)
                    if (result.isFailure) Log.w(TAG, "Stop failed for ${client.displayName}", result.exceptionOrNull())
                }
                VpnControlMode.TOGGLE -> {
                    if (_vpnActive.value) {
                        val cmd = control.buildStartCommand(client) ?: return
                        shizukuManager.awaitUserService(500)
                        val result = shizukuManager.execShellCommand(*cmd)
                        if (result.isFailure) Log.w(TAG, "Toggle-stop failed for ${client.displayName}", result.exceptionOrNull())
                    }
                }
                VpnControlMode.MANUAL -> { /* caller handles via force-stop */ }
            }
        }
        // For custom clients: caller handles via force-stop
    }

    fun launchApp(packageName: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    }

    fun startMonitoringVpn() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        _vpnActive.value = isVpnCurrentlyActive(cm)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // No dummy-gate here: orchestrator's stop sequence reads vpnActive to decide
                // fallback steps. If we hide our dummy, waitForVpnOff exits early and
                // launchLocal unfreezes with the external VPN still up (issue #63).
                _vpnActive.value = true
                CoroutineScope(Dispatchers.IO).launch { detectActiveVpnClient() }
            }

            override fun onLost(network: Network) {
                val stillActive = isVpnCurrentlyActive(cm)
                _vpnActive.value = stillActive
                if (!stillActive) {
                    _activeVpnClient.value = null
                    _activeVpnPackage.value = null
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register VPN network callback", e)
        }
    }

    fun stopMonitoringVpn() {
        networkCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
            networkCallback = null
        }
    }

    fun refreshVpnState() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        _vpnActive.value = isVpnCurrentlyActive(cm)
    }

    /**
     * Detect which VPN client is currently providing the active VPN connection.
     * Tries multiple strategies — first success wins.
     */
    suspend fun detectActiveVpnClient(): VpnClientType? {
        if (!_vpnActive.value) {
            _activeVpnClient.value = null
            _activeVpnPackage.value = null
            return null
        }

        val pkg = getVpnOwnerPackage()
        _activeVpnPackage.value = pkg
        val client = if (pkg != null) VpnClientType.fromPackageName(pkg) else null
        _activeVpnClient.value = client
        return client
    }

    /**
     * Multi-strategy VPN owner detection:
     * 1. Shell: dumpsys connectivity — grep "Transports: VPN" and extract OwnerUid
     * 2. Shell: check which known client has a foreground service
     */
    private suspend fun getVpnOwnerPackage(): String? {
        // Strategy 1: dumpsys connectivity — authoritative, works for ANY app
        val dumpsysResult = getVpnOwnerByDumpsys()
        if (dumpsysResult != null) return dumpsysResult

        // Strategy 2: check foreground services of known clients
        val fgResult = getVpnOwnerByForegroundService()
        if (fgResult != null) return fgResult

        return null
    }

    private suspend fun getVpnOwnerByDumpsys(): String? {
        // Exclude our own dummy VPN (StealthVpnService) from OwnerUid candidates —
        // otherwise after disable() dumpsys can still list our dummy first and we'd
        // report ourselves as the active external VPN client.
        val myUid = android.os.Process.myUid()

        // Extract OwnerUid directly from the VPN network section.
        // On A11 the VPN entry has "type: VPN[" in its NetworkAgentInfo.
        // We grep for that (NOT "NOT_VPN"), then find OwnerUid nearby.
        val uidStr = shizukuManager.runCommandWithOutput(
            "sh", "-c",
            "dumpsys connectivity 2>/dev/null | grep -A 30 'type: VPN\\[' | grep -oE 'OwnerUid: [0-9]+' | grep -v 'OwnerUid: $myUid' | head -1 | grep -oE '[0-9]+'"
        )

        var uid = uidStr?.trim()?.toIntOrNull()

        // Fallback: try "Transports: VPN" pattern (newer Android format)
        if (uid == null || uid <= 0) {
            val uidStr2 = shizukuManager.runCommandWithOutput(
                "sh", "-c",
                "dumpsys connectivity 2>/dev/null | grep -A 10 'Transports: VPN' | grep -oE 'OwnerUid: [0-9]+' | grep -v 'OwnerUid: $myUid' | head -1 | grep -oE '[0-9]+'"
            )
            uid = uidStr2?.trim()?.toIntOrNull()
        }

        if (uid != null && uid > 0) {
            val pkgOutput = shizukuManager.runCommandWithOutput(
                "sh", "-c",
                "pm list packages --uid $uid 2>/dev/null | head -1"
            )?.trim()
            val pkg = pkgOutput?.removePrefix("package:")?.split("\\s+".toRegex())?.firstOrNull()?.trim()
            // Second line of defense: if the resolved package is ours anyway
            // (shared UID, unexpected dumpsys formatting), drop it.
            if (!pkg.isNullOrBlank() && pkg != context.packageName) return pkg
        }

        return null
    }

    private suspend fun getVpnOwnerByForegroundService(): String? {
        for (client in VpnClientType.entries) {
            val output = shizukuManager.runCommandWithOutput(
                "sh", "-c",
                "dumpsys activity services ${client.packageName} 2>/dev/null | grep -c 'isForeground=true'"
            )
            if ((output?.trim()?.toIntOrNull() ?: 0) > 0) {
                return client.packageName
            }
        }
        return null
    }

    private fun isVpnCurrentlyActive(cm: ConnectivityManager): Boolean {
        // Don't gate on dummyVpnInFlight here: orchestrator.waitForVpnOff() relies on this
        // to see the real picture. If we pretend no VPN is active while our dummy + the
        // external VPN are both in the list, waitForVpnOff returns true instantly,
        // Step 3 force-stop is skipped, and launchLocal proceeds with the external VPN
        // still running (regression in v0.1.4, issue #63). The dummy-vs-external
        // distinction is only needed in VpnMonitorService — see the guard there.
        return try {
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "VpnClientManager"
    }
}
