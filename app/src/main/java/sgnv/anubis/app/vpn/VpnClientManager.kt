package sgnv.anubis.app.vpn

import sgnv.anubis.app.service.StealthVpnService
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import sgnv.anubis.app.shizuku.ShizukuManager
import sgnv.anubis.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class VpnClientManager(
    private val context: Context,
    private val shizukuManager: ShizukuManager
) {

    private val _vpnActive = MutableStateFlow(false)
    val vpnActive: StateFlow<Boolean> = _vpnActive

    private val networkStateLock = Any()
    private val _applicableVpnNetworks = MutableStateFlow<Set<Network>>(emptySet())
    val applicableVpnNetworks: StateFlow<Set<Network>> = _applicableVpnNetworks

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

    suspend fun launchApp(packageName: String, canLaunch: () -> Boolean = { true }): Boolean {
        // After unfreezeApp the binder call returns immediately, but PackageManager's
        // launcher-intent cache lags briefly — getLaunchIntentForPackage returns null
        // for ~100–300 ms while PM rebuilds. Without polling, the first tap on a
        // shortcut/URL silently no-ops; the second tap works because the app is
        // already in the cache. Already-launchable apps return on the first
        // iteration (no delay), so this costs nothing on the happy path.
        val launchIntent = withTimeoutOrNull(LAUNCH_INTENT_POLL_TIMEOUT_MS) {
            var intent = context.packageManager.getLaunchIntentForPackage(packageName)
            while (intent == null) {
                delay(50)
                intent = context.packageManager.getLaunchIntentForPackage(packageName)
            }
            intent
        }
        if (launchIntent != null && canLaunch()) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return true
        }
        return false
    }

    fun startMonitoringVpn() {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        refreshVpnState()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // No dummy-gate here: orchestrator's stop sequence reads vpnActive to decide
                // fallback steps. If we hide our dummy, waitForVpnOff exits early and
                // launchLocal unfreezes with the external VPN still up (issue #63).
                updateVpnNetworks { it + network }
                AppLogger.i(TAG, "VPN network available: id=${network.hashCode()}")
                CoroutineScope(Dispatchers.IO).launch { detectActiveVpnClient() }
            }

            override fun onLost(network: Network) {
                // Callback networks apply to this app/profile. A system-wide scan
                // also sees work VPNs and may still include the just-lost network.
                updateVpnNetworks { it - network }
                val stillActive = _vpnActive.value
                AppLogger.i(TAG, "VPN network lost: id=${network.hashCode()}, stillActive=$stillActive")
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            networkCallback = null
            AppLogger.e(TAG, "registerNetworkCallback failed", e)
        }
    }

    fun stopMonitoringVpn() {
        networkCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                AppLogger.e(TAG, "unregisterNetworkCallback failed in stopMonitoringVpn", e)
            }
            networkCallback = null
        }
    }

    fun refreshVpnState() {
        // Once registered, callbacks own the network set. A synchronous snapshot can
        // lag both onLost and a handover's onAvailable; it must neither resurrect the
        // former nor erase the replacement before its default route becomes visible.
        if (networkCallback != null) return
        val active = currentVpnNetwork()
        updateVpnNetworks { if (active == null) emptySet() else setOf(active) }
    }

    private fun currentVpnNetwork(): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        return network?.takeIf {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    /** A launch needs agreement between our callbacks and Android's current route. */
    fun isVpnAvailableForLaunch(requiresVpn: Boolean): Boolean {
        if (StealthVpnService.dummyVpnInFlight) return false
        val current = currentVpnNetwork()
        return if (requiresVpn) {
            current != null && current in _applicableVpnNetworks.value && !isOwnVpnNetwork(current)
        } else {
            current == null && _applicableVpnNetworks.value.isEmpty()
        }
    }

    /**
     * Identifies our own force-disconnect dummy VPN by its local-end address
     * (`StealthVpnService.DUMMY_VPN_ADDRESS`, from the RFC 2544 benchmarking range
     * so it does not collide with a real VPN provider). Avoids the API 30+
     * NetworkCapabilities.getOwnerUid() accessor so Android 10 remains supported;
     * on that version NoSuchMethodError can occur before a catch can handle it
     * when a class referring to that accessor is verified (#106, #118).
     */
    internal fun isOwnVpnNetwork(network: Network): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getLinkProperties(network)?.linkAddresses?.any {
            it.address.hostAddress == StealthVpnService.DUMMY_VPN_ADDRESS
        } == true
    } catch (e: Exception) {
        AppLogger.e(TAG, "isOwnVpnNetwork failed", e)
        false
    }

    fun isExternalVpnActive(): Boolean =
        !StealthVpnService.dummyVpnInFlight && _applicableVpnNetworks.value.any { !isOwnVpnNetwork(it) }

    private fun updateVpnNetworks(update: (Set<Network>) -> Set<Network>) {
        synchronized(networkStateLock) {
            val networks = update(_applicableVpnNetworks.value)
            _vpnActive.value = networks.isNotEmpty()
            _applicableVpnNetworks.value = networks
            if (networks.isEmpty()) {
                _activeVpnClient.value = null
                _activeVpnPackage.value = null
            }
        }
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
        val dump = shizukuManager.runCommandWithOutput("dumpsys", "connectivity") ?: return null
        val uid = VpnOwnerResolver.ownerUid(dump, android.os.Process.myUid()) ?: return null
        return context.packageManager.getPackagesForUid(uid)
            ?.firstOrNull { it != context.packageName }
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

    companion object {
        private const val TAG = "VpnClientManager"

        /**
         * Ceiling for the getLaunchIntentForPackage poll after unfreezeApp. Typical
         * PM settle time is 100–300 ms; 1500 ms covers slow OEMs and also bounds the
         * wait for genuinely-uninstalled packages (rare — caller usually knows the
         * package is managed and installed).
         */
        private const val LAUNCH_INTENT_POLL_TIMEOUT_MS = 1_500L
    }
}
