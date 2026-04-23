package sgnv.anubis.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientType
import androidx.core.content.edit

/** Centralized SharedPreferences keys for app-level settings. */
object AppSettings {
    const val PREFS_NAME = "settings"
    private const val SECURE_PREFS_NAME = "settings_secure"
    const val KEY_VPN_CLIENT_PACKAGE = "vpn_client_package"
    const val KEY_BACKGROUND_MONITORING = "background_monitoring"
    const val KEY_FREEZE_ON_BOOT = "freeze_on_boot"
    const val KEY_UNFREEZE_ON_VPN_TOGGLE = "unfreeze_on_vpn_toggle"
    const val KEY_LAUNCHER_SAFE_MODE = "launcher_safe_mode"
    private const val KEY_VPN_CLIENT_AUTOMATION_TOKEN_PREFIX = "vpn_client_automation_token_"
    private const val TAG = "AppSettings"

    private val LAUNCHER_SAFE_MODE_OEM_HINTS = listOf(
        "xiaomi",
        "redmi",
        "poco",
    )

    @Volatile
    private var securePrefsCache: SharedPreferences? = null

    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun securePrefs(context: Context): SharedPreferences? {
        securePrefsCache?.let { return it }
        return synchronized(this) {
            securePrefsCache?.let { return@synchronized it }
            runCatching {
                val appContext = context.applicationContext
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to open encrypted preferences for VPN automation tokens", error)
            }.getOrNull()?.also { securePrefsCache = it }
        }
    }

    private fun automationTokenKey(packageName: String): String =
        "$KEY_VPN_CLIENT_AUTOMATION_TOKEN_PREFIX$packageName"

    private fun migrateLegacyAutomationTokenIfNeeded(context: Context, packageName: String): String? {
        val key = automationTokenKey(packageName)
        val securePrefs = securePrefs(context) ?: return null
        securePrefs.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val legacyPrefs = prefs(context)
        val legacyToken = legacyPrefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
        securePrefs.edit { putString(key, legacyToken) }
        legacyPrefs.edit { remove(key) }
        return legacyToken
    }

    /** Optional behavior: unfreeze the opposite managed group after VPN state changes. */
    fun shouldUnfreezeManagedAppsOnVpnToggle(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_UNFREEZE_ON_VPN_TOGGLE, false)
    }

    /**
     * Slow down mass-unfreeze to reduce launcher duplicate shortcuts.
     * Enabled by default on Xiaomi/Redmi/POCO where the issue is frequent.
     */
    fun shouldUseLauncherSafeMode(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LAUNCHER_SAFE_MODE, launcherSafeModeDefault())
    }

    private fun launcherSafeModeDefault(): Boolean {
        val brand = Build.BRAND.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        return LAUNCHER_SAFE_MODE_OEM_HINTS.any { hint ->
            brand.contains(hint) || manufacturer.contains(hint)
        }
    }

    fun getVpnClientAutomationToken(context: Context, packageName: String): String? {
        return migrateLegacyAutomationTokenIfNeeded(context, packageName)
            ?: securePrefs(context)
            ?.getString(automationTokenKey(packageName), null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setVpnClientAutomationToken(context: Context, packageName: String, token: String?) {
        val securePrefs = securePrefs(context) ?: return
        val editor = securePrefs.edit()
        val key = automationTokenKey(packageName)
        if (token.isNullOrBlank()) {
            editor.remove(key)
        } else {
            editor.putString(key, token)
        }
        editor.apply()
        prefs(context).edit { remove(key) }
    }

    fun loadSelectedVpnClient(
        context: Context,
        packageNameOverride: String? = null,
    ): SelectedVpnClient {
        val prefs = prefs(context)
        val packageName = packageNameOverride
            ?: prefs.getString(KEY_VPN_CLIENT_PACKAGE, null)
            ?: prefs.getString("vpn_client", null)?.let {
                runCatching { VpnClientType.valueOf(it).packageName }.getOrNull()
            }
            ?: VpnClientType.V2RAY_NG.packageName
        return SelectedVpnClient.fromPackage(
            pkg = packageName,
            automationToken = getVpnClientAutomationToken(context, packageName),
        )
    }
}
