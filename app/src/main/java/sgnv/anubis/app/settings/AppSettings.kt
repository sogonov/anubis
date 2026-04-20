package sgnv.anubis.app.settings

import android.content.Context
import android.content.SharedPreferences
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientType

/** Centralized SharedPreferences keys for app-level settings. */
object AppSettings {
    const val PREFS_NAME = "settings"
    const val KEY_VPN_CLIENT_PACKAGE = "vpn_client_package"
    const val KEY_BACKGROUND_MONITORING = "background_monitoring"
    const val KEY_FREEZE_ON_BOOT = "freeze_on_boot"
    const val KEY_UNFREEZE_ON_VPN_TOGGLE = "unfreeze_on_vpn_toggle"
    private const val KEY_VPN_CLIENT_AUTOMATION_TOKEN_PREFIX = "vpn_client_automation_token_"

    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Optional behavior: unfreeze the opposite managed group after VPN state changes. */
    fun shouldUnfreezeManagedAppsOnVpnToggle(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_UNFREEZE_ON_VPN_TOGGLE, false)
    }

    fun getVpnClientAutomationToken(context: Context, packageName: String): String? {
        return prefs(context)
            .getString("$KEY_VPN_CLIENT_AUTOMATION_TOKEN_PREFIX$packageName", null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setVpnClientAutomationToken(context: Context, packageName: String, token: String?) {
        val editor = prefs(context).edit()
        val key = "$KEY_VPN_CLIENT_AUTOMATION_TOKEN_PREFIX$packageName"
        if (token.isNullOrBlank()) {
            editor.remove(key)
        } else {
            editor.putString(key, token)
        }
        editor.apply()
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
