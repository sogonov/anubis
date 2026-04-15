package sgnv.anubis.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.InstalledAppInfo
import sgnv.anubis.app.data.model.ManagedApp
import sgnv.anubis.app.data.model.NetworkInfo
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.service.StealthOrchestrator
import sgnv.anubis.app.service.StealthState
import sgnv.anubis.app.service.StealthVpnService
import sgnv.anubis.app.service.VpnMonitorService
import sgnv.anubis.app.shizuku.ShizukuStatus
import sgnv.anubis.app.update.UpdateChecker
import sgnv.anubis.app.update.UpdateInfo
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientManager
import sgnv.anubis.app.vpn.VpnClientType
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AnubisApp
    val shizukuManager = app.shizukuManager
    private val vpnClientManager = VpnClientManager(application, shizukuManager)
    private val repository = AppRepository(app.database.managedAppDao(), application)
    private val orchestrator = StealthOrchestrator(application, shizukuManager, vpnClientManager, repository)

    val stealthState: StateFlow<StealthState> = orchestrator.state
    val lastError: StateFlow<String?> = orchestrator.lastError
    val vpnActive: StateFlow<Boolean> = vpnClientManager.vpnActive
    val activeVpnClient: StateFlow<VpnClientType?> = vpnClientManager.activeVpnClient
    val activeVpnPackage: StateFlow<String?> = vpnClientManager.activeVpnPackage
    val shizukuStatus: StateFlow<ShizukuStatus> = shizukuManager.status
    val frozenVersion: StateFlow<Long> = orchestrator.frozenVersion

    fun getVpnPermissionIntent(): Intent? = StealthVpnService.prepareVpn(getApplication())

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps

    // Home screen groups
    private val _localApps = MutableStateFlow<List<ManagedApp>>(emptyList())
    val localApps: StateFlow<List<ManagedApp>> = _localApps

    private val _vpnOnlyApps = MutableStateFlow<List<ManagedApp>>(emptyList())
    val vpnOnlyApps: StateFlow<List<ManagedApp>> = _vpnOnlyApps

    private val _launchVpnApps = MutableStateFlow<List<ManagedApp>>(emptyList())
    val launchVpnApps: StateFlow<List<ManagedApp>> = _launchVpnApps

    private val _selectedVpnClient = MutableStateFlow(SelectedVpnClient.fromKnown(VpnClientType.V2RAY_NG))
    val selectedVpnClient: StateFlow<SelectedVpnClient> = _selectedVpnClient

    private val _installedVpnClients = MutableStateFlow<List<VpnClientType>>(emptyList())
    val installedVpnClients: StateFlow<List<VpnClientType>> = _installedVpnClients

    private val _networkInfo = MutableStateFlow<NetworkInfo?>(null)
    val networkInfo: StateFlow<NetworkInfo?> = _networkInfo

    private val _networkLoading = MutableStateFlow(false)
    val networkLoading: StateFlow<Boolean> = _networkLoading

    private val _backgroundMonitoring = MutableStateFlow(false)
    val backgroundMonitoring: StateFlow<Boolean> = _backgroundMonitoring

    private val _dangerousAppWarning = MutableStateFlow<String?>(null)
    val dangerousAppWarning: StateFlow<String?> = _dangerousAppWarning

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo

    private val _updateCheckEnabled = MutableStateFlow(true)
    val updateCheckEnabled: StateFlow<Boolean> = _updateCheckEnabled

    private val _updateCheckInProgress = MutableStateFlow(false)
    val updateCheckInProgress: StateFlow<Boolean> = _updateCheckInProgress

    private val _resetCompleted = MutableSharedFlow<Int>()
    val resetCompleted: SharedFlow<Int> = _resetCompleted

    init {
        vpnClientManager.startMonitoringVpn()
        refreshVpnClients()
        loadSelectedClient()
        loadInstalledApps()
        loadGroupedApps()
        scheduleAutoFreeze()
        observeVpnState()
        loadBackgroundMonitoring()
        checkDangerousApps()
        loadUpdateCheckPref()
        autoCheckForUpdates()
    }

    /** Watch VPN state changes — auto-freeze, sync state, refresh network */
    private fun observeVpnState() {
        viewModelScope.launch {
            var prevActive = vpnClientManager.vpnActive.value
            vpnClientManager.vpnActive.collect { active ->
                if (active != prevActive) {
                    prevActive = active

                    if (active) {
                        // VPN turned ON (possibly outside Anubis) — freeze LOCAL apps
                        orchestrator.freezeOnly()
                        VpnMonitorService.start(getApplication())
                    } else {
                        // VPN turned OFF — freeze VPN_ONLY apps
                        orchestrator.freezeVpnOnly()
                    }

                    orchestrator.syncState()

                    if (active && orchestrator.lastError.value?.contains("вручную") == true) {
                        orchestrator.clearError()
                    }

                    delay(500)
                    refreshNetworkInfo()
                }
            }
        }
    }

    fun toggleStealth() {
        viewModelScope.launch {
            if (stealthState.value == StealthState.DISABLED) {
                orchestrator.enable(_selectedVpnClient.value)
                if (orchestrator.state.value == StealthState.ENABLED) {
                    VpnMonitorService.start(getApplication())
                }
            } else if (stealthState.value == StealthState.ENABLED) {
                val detectedPkg = vpnClientManager.activeVpnPackage.value
                val detectedClient = vpnClientManager.activeVpnClient.value
                val clientToStop = if (detectedClient != null) SelectedVpnClient.fromKnown(detectedClient)
                    else vpnClientManager.activeVpnPackage.value?.let { SelectedVpnClient.fromPackage(it) }
                    ?: _selectedVpnClient.value
                orchestrator.disable(clientToStop, detectedPkg)
                if (orchestrator.state.value == StealthState.DISABLED) {
                    VpnMonitorService.stop(getApplication())
                }
            }
        }
    }

    fun freezeLocalApps() {
        viewModelScope.launch {
            orchestrator.freezeOnly()
        }
    }

    fun launchWithVpn(packageName: String) {
        viewModelScope.launch {
            orchestrator.launchWithVpn(packageName, _selectedVpnClient.value)
            if (orchestrator.state.value == StealthState.ENABLED) {
                VpnMonitorService.start(getApplication())
            }
        }
    }

    fun launchLocal(packageName: String) {
        viewModelScope.launch {
            val detectedPkg = vpnClientManager.activeVpnPackage.value
            val detectedClient = vpnClientManager.activeVpnClient.value
            val clientToStop = if (detectedClient != null) SelectedVpnClient.fromKnown(detectedClient)
                else detectedPkg?.let { SelectedVpnClient.fromPackage(it) }
                ?: _selectedVpnClient.value
            orchestrator.launchLocal(packageName, clientToStop, detectedPkg)
            if (orchestrator.state.value == StealthState.DISABLED) {
                VpnMonitorService.stop(getApplication())
            }
        }
    }

    fun toggleAppFrozen(packageName: String) {
        viewModelScope.launch {
            orchestrator.toggleAppFrozen(packageName)
            loadGroupedApps()
        }
    }

    fun isAppFrozen(packageName: String): Boolean = shizukuManager.isAppFrozen(packageName)

    fun removeFromGroup(packageName: String) {
        viewModelScope.launch {
            repository.removeApp(packageName)
            loadInstalledApps()
            loadGroupedApps()
        }
    }

    fun createShortcut(packageName: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val pm = app.packageManager
            val sm = app.getSystemService(ShortcutManager::class.java) ?: return@launch

            if (!sm.isRequestPinShortcutSupported) return@launch

            val group = repository.getAppGroup(packageName)
            val label = try {
                pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
            } catch (e: Exception) { packageName }

            val icon = try {
                val drawable = pm.getApplicationIcon(packageName)
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                Icon.createWithBitmap(bmp)
            } catch (e: Exception) {
                Icon.createWithResource(app, android.R.drawable.sym_def_app_icon)
            }

            val intent = Intent(app, sgnv.anubis.app.service.ShortcutActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("package", packageName)
                putExtra("group", group?.name ?: AppGroup.LAUNCH_VPN.name)
            }

            val shortcutInfo = ShortcutInfo.Builder(app, "stealth_$packageName")
                .setShortLabel(label)
                .setLongLabel("$label (Stealth)")
                .setIcon(icon)
                .setIntent(intent)
                .build()

            sm.requestPinShortcut(shortcutInfo, null)
        }
    }

    fun cycleAppGroup(packageName: String) {
        viewModelScope.launch {
            repository.cycleGroup(packageName)
            loadInstalledApps()
            loadGroupedApps()
        }
    }

    fun autoSelectRestricted() {
        viewModelScope.launch {
            repository.autoSelectRestricted()
            loadInstalledApps()
            loadGroupedApps()
        }
    }

    fun unfreezeAllAndClear() {
        viewModelScope.launch {
            val allManaged = repository.getAllManagedPackages()
            var unfrozenCount = 0
            for (pkg in allManaged) {
                if (shizukuManager.isAppFrozen(pkg)) {
                    shizukuManager.unfreezeApp(pkg)
                    unfrozenCount++
                }
                repository.removeApp(pkg)
            }
            loadInstalledApps()
            loadGroupedApps()
            orchestrator.syncState()
            _resetCompleted.emit(unfrozenCount)
        }
    }

    /** Emergency: scan PM for all user-disabled apps and unfreeze them.
     *  Covers the case when Anubis was reinstalled and DB is empty but apps are still frozen. */
    fun unfreezeAllUserDisabled() {
        viewModelScope.launch {
            val pm = getApplication<Application>().packageManager
            val disabled = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
                    .filter { !it.enabled && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .map { it.packageName }
            }
            var unfrozenCount = 0
            for (pkg in disabled) {
                shizukuManager.unfreezeApp(pkg)
                unfrozenCount++
            }
            // Also clear DB so orchestrator state stays consistent
            for (pkg in repository.getAllManagedPackages()) {
                repository.removeApp(pkg)
            }
            loadInstalledApps()
            loadGroupedApps()
            orchestrator.syncState()
            _resetCompleted.emit(unfrozenCount)
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = repository.getInstalledApps()
        }
    }

    fun loadGroupedApps() {
        viewModelScope.launch {
            _localApps.value = repository.getAppsByGroup(AppGroup.LOCAL)
            _vpnOnlyApps.value = repository.getAppsByGroup(AppGroup.VPN_ONLY)
            _launchVpnApps.value = repository.getAppsByGroup(AppGroup.LAUNCH_VPN)
        }
    }

    fun selectVpnClient(client: SelectedVpnClient) {
        _selectedVpnClient.value = client
        getApplication<Application>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("vpn_client_package", client.packageName)
            .apply()
    }

    fun isVpnClientEnabled(packageName: String): Boolean {
        return try {
            getApplication<Application>().packageManager.getApplicationInfo(packageName, 0).enabled
        } catch (e: Exception) { false }
    }

    fun setBackgroundMonitoring(enabled: Boolean) {
        _backgroundMonitoring.value = enabled
        val app = getApplication<Application>()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("background_monitoring", enabled).apply()
        if (enabled) {
            VpnMonitorService.start(app)
        } else {
            VpnMonitorService.stop(app)
        }
    }

    private fun loadBackgroundMonitoring() {
        val prefs = getApplication<Application>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("background_monitoring", false)
        _backgroundMonitoring.value = enabled
        if (enabled) {
            VpnMonitorService.start(getApplication())
        }
    }

    fun dismissDangerousAppWarning() {
        _dangerousAppWarning.value = null
    }

    private fun checkDangerousApps() {
        val dominated = mapOf(
            "ru.dahl.messenger" to "https://dontusetelega.lol/analysis"
        )
        val pm = getApplication<Application>().packageManager
        for ((pkg, url) in dominated) {
            try {
                pm.getApplicationInfo(pkg, 0)
                _dangerousAppWarning.value = url
                return
            } catch (_: Exception) {}
        }
    }

    fun requestShizukuPermission() {
        if (shizukuManager.isAvailable()) {
            shizukuManager.requestPermission()
        }
    }

    fun refreshNetworkInfo() {
        viewModelScope.launch {
            _networkLoading.value = true
            _networkInfo.value = fetchNetworkInfo()
            _networkLoading.value = false
        }
    }

    fun onResume() {
        shizukuManager.refreshStatus()
        if (shizukuManager.status.value == ShizukuStatus.READY) {
            shizukuManager.bindUserService()
        }
        vpnClientManager.refreshVpnState()
        orchestrator.syncState()
        refreshVpnClients()
        viewModelScope.launch { vpnClientManager.detectActiveVpnClient() }
        loadInstalledApps()
        loadGroupedApps()
        scheduleAutoFreeze()
    }

    private fun scheduleAutoFreeze() {
        viewModelScope.launch {
            // Shizuku is initialized in Application.onCreate — just bind UserService
            if (shizukuManager.status.value == ShizukuStatus.READY) {
                shizukuManager.bindUserService()
                delay(200)
                vpnClientManager.detectActiveVpnClient()
            } else {
                // First launch or Shizuku not yet ready — brief wait
                repeat(5) {
                    delay(300)
                    if (shizukuManager.status.value == ShizukuStatus.READY) {
                        shizukuManager.bindUserService()
                        delay(200)
                        vpnClientManager.detectActiveVpnClient()
                        return@repeat
                    }
                }
            }

            if (vpnClientManager.vpnActive.value
                && stealthState.value == StealthState.DISABLED
                && shizukuManager.status.value == ShizukuStatus.READY
            ) {
                orchestrator.freezeOnly()
                if (orchestrator.state.value == StealthState.ENABLED) {
                    VpnMonitorService.start(getApplication())
                }
            }
        }
    }

    private suspend fun fetchNetworkInfo(): NetworkInfo? = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            val json = URL("https://ipinfo.io/json").readText()
            val pingMs = System.currentTimeMillis() - start
            val obj = JSONObject(json)
            NetworkInfo(
                ip = obj.optString("ip", "?"),
                country = obj.optString("country", ""),
                city = obj.optString("city", ""),
                org = obj.optString("org", ""),
                pingMs = pingMs
            )
        } catch (e: Exception) {
            try {
                val start = System.currentTimeMillis()
                val ip = URL("https://api.ipify.org").readText().trim()
                val pingMs = System.currentTimeMillis() - start
                NetworkInfo(ip = ip, pingMs = pingMs)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun refreshVpnClients() {
        _installedVpnClients.value = vpnClientManager.getInstalledClients()
    }

    private fun loadSelectedClient() {
        val prefs = getApplication<Application>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
        val pkg = prefs.getString("vpn_client_package", null)
            ?: prefs.getString("vpn_client", null)?.let {
                // Migration from old format (enum name → package name)
                try { VpnClientType.valueOf(it).packageName } catch (e: Exception) { null }
            }
        _selectedVpnClient.value = if (pkg != null) {
            SelectedVpnClient.fromPackage(pkg)
        } else {
            SelectedVpnClient.fromKnown(VpnClientType.V2RAY_NG)
        }
    }

    private fun loadUpdateCheckPref() {
        _updateCheckEnabled.value = UpdateChecker.isEnabled(getApplication())
    }

    fun setUpdateCheckEnabled(enabled: Boolean) {
        _updateCheckEnabled.value = enabled
        UpdateChecker.setEnabled(getApplication(), enabled)
    }

    private fun autoCheckForUpdates() {
        if (!UpdateChecker.isEnabled(getApplication())) return
        viewModelScope.launch {
            delay(5000)
            val info = UpdateChecker.check(getApplication(), force = false) ?: return@launch
            if (UpdateChecker.shouldNotify(getApplication(), info)) {
                _updateInfo.value = info
            }
        }
    }

    fun checkForUpdatesNow() {
        viewModelScope.launch {
            _updateCheckInProgress.value = true
            val info = UpdateChecker.check(getApplication(), force = true)
            _updateCheckInProgress.value = false
            _updateInfo.value = info
        }
    }

    fun dismissUpdateDialog() { _updateInfo.value = null }

    fun skipCurrentUpdate() {
        _updateInfo.value?.let {
            UpdateChecker.skipVersion(getApplication(), it.latestVersion)
        }
        _updateInfo.value = null
    }

    override fun onCleared() {
        vpnClientManager.stopMonitoringVpn()
        super.onCleared()
    }
}
