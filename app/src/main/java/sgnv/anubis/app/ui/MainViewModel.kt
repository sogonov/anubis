package sgnv.anubis.app.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.InstalledAppInfo
import sgnv.anubis.app.data.model.ManagedApp
import sgnv.anubis.app.data.model.NetworkInfo
import sgnv.anubis.app.service.StealthState
import sgnv.anubis.app.service.StealthVpnService
import sgnv.anubis.app.service.VpnMonitorService
import sgnv.anubis.app.settings.AppSettings
import sgnv.anubis.app.shizuku.ShizukuStatus
import sgnv.anubis.app.update.UpdateChecker
import sgnv.anubis.app.update.UpdateInfo
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientType
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.core.graphics.createBitmap
import androidx.core.content.edit
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.graphics.drawable.AdaptiveIconDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.json.JSONObject
import java.net.URL
import sgnv.anubis.app.ui.util.renderToBitmap

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AnubisApp
    val shizukuManager = app.shizukuManager
    private val vpnClientManager = app.vpnClientManager
    private val repository = app.appRepository
    private val orchestrator = app.orchestrator

    val stealthState: StateFlow<StealthState> = orchestrator.state
    val lastError: StateFlow<String?> = orchestrator.lastError
    val vpnActive: StateFlow<Boolean> = vpnClientManager.vpnActive
    val activeVpnClient: StateFlow<VpnClientType?> = vpnClientManager.activeVpnClient
    val activeVpnPackage: StateFlow<String?> = vpnClientManager.activeVpnPackage
    val shizukuStatus: StateFlow<ShizukuStatus> = shizukuManager.status
    val frozenVersion: StateFlow<Long> = orchestrator.frozenVersion

    /** Easter-egg: "Заморожено N за X с" / "Разморожено ..." — direct pass-through. */
    val benchmark: SharedFlow<String> = orchestrator.benchmark

    fun getVpnPermissionIntent(): Intent? = StealthVpnService.prepareVpn(getApplication())

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps

    // Home screen groups
    private val _localApps = MutableStateFlow<List<ManagedApp>>(emptyList())
    val localApps: StateFlow<List<ManagedApp>> = _localApps

    private val _localAutoUnfreezeApps = MutableStateFlow<List<ManagedApp>>(emptyList())
    val localAutoUnfreezeApps: StateFlow<List<ManagedApp>> = _localAutoUnfreezeApps

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

    // Exposes issue #31 behavior to Compose settings UI.
    private val _unfreezeManagedAppsOnVpnToggle = MutableStateFlow(false)
    val unfreezeManagedAppsOnVpnToggle: StateFlow<Boolean> = _unfreezeManagedAppsOnVpnToggle
    
    private val _launcherSafeMode = MutableStateFlow(false)
    val launcherSafeMode: StateFlow<Boolean> = _launcherSafeMode

    private val _dangerousAppWarning = MutableStateFlow<String?>(null)
    val dangerousAppWarning: StateFlow<String?> = _dangerousAppWarning

    /** Package pending confirmation for manual unfreeze under active VPN (issue #81). */
    private val _manualUnfreezeWarning = MutableStateFlow<String?>(null)
    val manualUnfreezeWarning: StateFlow<String?> = _manualUnfreezeWarning

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo

    private val _updateCheckEnabled = MutableStateFlow(true)
    val updateCheckEnabled: StateFlow<Boolean> = _updateCheckEnabled

    private val _updateCheckInProgress = MutableStateFlow(false)
    val updateCheckInProgress: StateFlow<Boolean> = _updateCheckInProgress

    private val _resetCompleted = MutableSharedFlow<Int>()
    val resetCompleted: SharedFlow<Int> = _resetCompleted

    init {
        // VpnClientManager is started in AnubisApp.onCreate — don't re-register here.
        refreshVpnClients()
        loadSelectedClient()
        loadInstalledApps()
        loadGroupedApps()
        scheduleAutoFreeze()
        observeVpnState()
        loadBackgroundMonitoring()
        loadUnfreezeManagedAppsOnVpnToggle()
        loadLauncherSafeMode()
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
            } else if (stealthState.value == StealthState.ENABLED) {
                val detectedPkg = vpnClientManager.activeVpnPackage.value
                val detectedClient = vpnClientManager.activeVpnClient.value
                val clientToStop = if (detectedClient != null) {
                    AppSettings.loadSelectedVpnClient(getApplication(), detectedClient.packageName)
                } else vpnClientManager.activeVpnPackage.value?.let {
                    AppSettings.loadSelectedVpnClient(getApplication(), it)
                }
                    ?: _selectedVpnClient.value
                orchestrator.disable(clientToStop, detectedPkg)
            }
        }
    }

    fun launchWithVpn(packageName: String) {
        viewModelScope.launch {
            orchestrator.launchWithVpn(packageName, _selectedVpnClient.value)
        }
    }

    fun launchLocal(packageName: String) {
        viewModelScope.launch {
            val detectedPkg = vpnClientManager.activeVpnPackage.value
            val detectedClient = vpnClientManager.activeVpnClient.value
            val clientToStop = if (detectedClient != null) {
                AppSettings.loadSelectedVpnClient(getApplication(), detectedClient.packageName)
            } else detectedPkg?.let {
                AppSettings.loadSelectedVpnClient(getApplication(), it)
            }
                ?: _selectedVpnClient.value
            orchestrator.launchLocal(packageName, clientToStop, detectedPkg)
        }
    }

    fun toggleAppFrozen(packageName: String) {
        viewModelScope.launch {
            orchestrator.toggleAppFrozen(packageName)
            loadGroupedApps()
        }
    }

    /**
     * Issue #81: ручная разморозка LOCAL-приложения при активном VPN — выстрел в ногу
     * (приложение получит реальный сетевой адрес через VPN). Перехватываем такой случай
     * подтверждающим диалогом; всё остальное (заморозка, или разморозка вне LOCAL/VPN OFF)
     * проходит без вопросов.
     */
    fun requestToggleAppFrozen(packageName: String) {
        if (!isAppFrozen(packageName) || !vpnActive.value) {
            toggleAppFrozen(packageName)
            return
        }
        val isLocalGroup = _localApps.value.any { it.packageName == packageName }
            || _localAutoUnfreezeApps.value.any { it.packageName == packageName }
        if (isLocalGroup) {
            _manualUnfreezeWarning.value = packageName
        } else {
            toggleAppFrozen(packageName)
        }
    }

    fun confirmManualUnfreeze() {
        val pkg = _manualUnfreezeWarning.value ?: return
        _manualUnfreezeWarning.value = null
        toggleAppFrozen(pkg)
    }

    fun dismissManualUnfreezeWarning() {
        _manualUnfreezeWarning.value = null
    }

    fun isAppFrozen(packageName: String): Boolean = shizukuManager.isAppFrozen(packageName)

    fun removeFromGroup(packageName: String) {
        viewModelScope.launch {
            repository.removeApp(packageName)
            loadInstalledApps()
            loadGroupedApps()
        }
    }

    private fun buildShortcutIcon(
        pm: PackageManager,
        sm: ShortcutManager,
        packageName: String
    ): Icon {
        val drawable = pm.getApplicationIcon(packageName)
        val visibleSize = maxOf(sm.iconMaxWidth, sm.iconMaxHeight).coerceAtLeast(1)

        return if (drawable is AdaptiveIconDrawable) {
            val extraInset = AdaptiveIconDrawable.getExtraInsetFraction()
            val fullSize = (visibleSize * (1f + 2f * extraInset))
                .roundToInt()
                .coerceAtLeast(1)

            val bmp = createBitmap(fullSize, fullSize)
            val canvas = Canvas(bmp)
            drawable.background?.let { bg ->
                bg.setBounds(0, 0, fullSize, fullSize)
                bg.draw(canvas)
            }
            drawable.foreground?.let { fg ->
                fg.setBounds(0, 0, fullSize, fullSize)
                fg.draw(canvas)
            }
            Icon.createWithAdaptiveBitmap(bmp)
        } else {
            val bmp = drawable.renderToBitmap(visibleSize, visibleSize)
            Icon.createWithBitmap(bmp)
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
                buildShortcutIcon(pm, sm, packageName)
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

    /** Directly assign an apps to a specific group (used by the Home-screen inline add picker). */
    suspend fun setAppsGroup(
        packageNames: Collection<String>,
        group: AppGroup
    ) {
        for (packageName in packageNames) {
            repository.setAppGroup(packageName, group)
        }
        if (packageNames.isNotEmpty()) {
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
        viewModelScope.launch { refreshInstalledAppsSync() }
    }

    /** Suspend version for pull-to-refresh: caller awaits completion, then dismisses the spinner. */
    suspend fun refreshInstalledAppsSync() {
        _installedApps.value = repository.getInstalledApps()
    }

    fun loadGroupedApps() {
        viewModelScope.launch {
            _localApps.value = repository.getAppsByGroup(AppGroup.LOCAL)
            _localAutoUnfreezeApps.value = repository.getAppsByGroup(AppGroup.LOCAL_AUTO_UNFREEZE)
            _vpnOnlyApps.value = repository.getAppsByGroup(AppGroup.VPN_ONLY)
            _launchVpnApps.value = repository.getAppsByGroup(AppGroup.LAUNCH_VPN)
        }
    }

    fun selectVpnClient(client: SelectedVpnClient) {
        val persistedClient = AppSettings.loadSelectedVpnClient(getApplication(), client.packageName)
        _selectedVpnClient.value = client.copy(
            automationToken = client.automationToken ?: persistedClient.automationToken,
        )
        AppSettings.prefs(getApplication())
            .edit {
                putString(AppSettings.KEY_VPN_CLIENT_PACKAGE, client.packageName)
            }
    }

    fun updateSelectedVpnClientAutomationToken(token: String) {
        val normalizedToken = token.trim()
        val current = _selectedVpnClient.value
        AppSettings.setVpnClientAutomationToken(
            getApplication(),
            current.packageName,
            normalizedToken.takeIf { it.isNotEmpty() },
        )
        _selectedVpnClient.value = current.copy(
            automationToken = normalizedToken.takeIf { it.isNotEmpty() },
        )
    }

    fun isVpnClientEnabled(packageName: String): Boolean {
        return try {
            getApplication<Application>().packageManager.getApplicationInfo(packageName, 0).enabled
        } catch (e: Exception) { false }
    }

    fun setBackgroundMonitoring(enabled: Boolean) {
        _backgroundMonitoring.value = enabled
        val app = getApplication<Application>()
        AppSettings.prefs(app)
            .edit {
                putBoolean(AppSettings.KEY_BACKGROUND_MONITORING, enabled)
            }
        if (enabled) {
            VpnMonitorService.start(app)
        } else {
            VpnMonitorService.stop(app)
        }
    }

    private fun loadBackgroundMonitoring() {
        val enabled = AppSettings.prefs(getApplication())
            .getBoolean(AppSettings.KEY_BACKGROUND_MONITORING, false)
        _backgroundMonitoring.value = enabled
        if (enabled) {
            VpnMonitorService.start(getApplication())
        }
    }

    fun setUnfreezeManagedAppsOnVpnToggle(enabled: Boolean) {
        _unfreezeManagedAppsOnVpnToggle.value = enabled
        // Persist immediately so orchestrators and services pick the flag up without restart.
        AppSettings.prefs(getApplication())
            .edit {
                putBoolean(AppSettings.KEY_UNFREEZE_ON_VPN_TOGGLE, enabled)
            }
    }

    private fun loadUnfreezeManagedAppsOnVpnToggle() {
        // Load once on startup; services read the same flag directly from shared prefs.
        _unfreezeManagedAppsOnVpnToggle.value = AppSettings.prefs(getApplication())
            .getBoolean(AppSettings.KEY_UNFREEZE_ON_VPN_TOGGLE, false)
    }

    fun setLauncherSafeMode(enabled: Boolean) {
        _launcherSafeMode.value = enabled
        AppSettings.prefs(getApplication())
            .edit {
                putBoolean(AppSettings.KEY_LAUNCHER_SAFE_MODE, enabled)
            }
    }

    private fun loadLauncherSafeMode() {
        _launcherSafeMode.value = AppSettings.shouldUseLauncherSafeMode(getApplication())
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
                shizukuManager.awaitUserService(500)
                vpnClientManager.detectActiveVpnClient()
            } else {
                // First launch or Shizuku not yet ready — brief wait
                repeat(5) {
                    delay(300)
                    if (shizukuManager.status.value == ShizukuStatus.READY) {
                        shizukuManager.awaitUserService(500)
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
        _selectedVpnClient.value = AppSettings.loadSelectedVpnClient(getApplication())
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
        // VpnClientManager is a process-wide singleton in AnubisApp — don't stop it here.
        super.onCleared()
    }
}

