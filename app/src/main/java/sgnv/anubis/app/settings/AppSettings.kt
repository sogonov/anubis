package sgnv.anubis.app.settings

import android.content.Context
import android.content.SharedPreferences

/** Centralized SharedPreferences keys for app-level settings. */
object AppSettings {
    const val PREFS_NAME = "settings"
    const val KEY_VPN_CLIENT_PACKAGE = "vpn_client_package"
    const val KEY_BACKGROUND_MONITORING = "background_monitoring"
    const val KEY_FREEZE_ON_BOOT = "freeze_on_boot"
    const val KEY_UNFREEZE_ON_VPN_TOGGLE = "unfreeze_on_vpn_toggle"

    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Optional behavior: unfreeze the opposite managed group after VPN state changes. */
    fun shouldUnfreezeManagedAppsOnVpnToggle(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_UNFREEZE_ON_VPN_TOGGLE, false)
    }
}
