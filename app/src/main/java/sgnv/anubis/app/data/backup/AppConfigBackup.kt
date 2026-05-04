package sgnv.anubis.app.data.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import sgnv.anubis.app.data.model.AppGroup

/**
 * Snapshot of user-configurable state — group memberships and the small set of
 * settings persisted in plain SharedPreferences. Tunguska automation tokens live
 * in EncryptedSharedPreferences and are deliberately excluded so the export file
 * stays safe to share or store outside the device. Issue #131.
 */
data class AppConfigBackup(
    val version: Int,
    val exportedAt: String,
    val appVersion: String,
    val settings: BackupSettings,
    val groups: Map<AppGroup, List<String>>,
)

data class BackupSettings(
    val vpnClientPackage: String? = null,
    val backgroundMonitoring: Boolean? = null,
    val freezeOnBoot: Boolean? = null,
    val unfreezeOnVpnToggle: Boolean? = null,
    val launcherSafeMode: Boolean? = null,
)

sealed class ParseResult {
    data class Success(val backup: AppConfigBackup, val warnings: List<String>) : ParseResult()
    data class Failure(val message: String) : ParseResult()
}

object AppConfigBackupSerializer {
    const val CURRENT_VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_APP_VERSION = "appVersion"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_GROUPS = "groups"

    private const val SETTING_VPN_CLIENT_PACKAGE = "vpn_client_package"
    private const val SETTING_BACKGROUND_MONITORING = "background_monitoring"
    private const val SETTING_FREEZE_ON_BOOT = "freeze_on_boot"
    private const val SETTING_UNFREEZE_ON_VPN_TOGGLE = "unfreeze_on_vpn_toggle"
    private const val SETTING_LAUNCHER_SAFE_MODE = "launcher_safe_mode"

    fun toJson(backup: AppConfigBackup): String {
        val root = JSONObject()
        root.put(KEY_VERSION, backup.version)
        root.put(KEY_EXPORTED_AT, backup.exportedAt)
        root.put(KEY_APP_VERSION, backup.appVersion)

        val settings = JSONObject()
        backup.settings.vpnClientPackage?.let { settings.put(SETTING_VPN_CLIENT_PACKAGE, it) }
        backup.settings.backgroundMonitoring?.let { settings.put(SETTING_BACKGROUND_MONITORING, it) }
        backup.settings.freezeOnBoot?.let { settings.put(SETTING_FREEZE_ON_BOOT, it) }
        backup.settings.unfreezeOnVpnToggle?.let { settings.put(SETTING_UNFREEZE_ON_VPN_TOGGLE, it) }
        backup.settings.launcherSafeMode?.let { settings.put(SETTING_LAUNCHER_SAFE_MODE, it) }
        root.put(KEY_SETTINGS, settings)

        val groups = JSONObject()
        for ((group, packages) in backup.groups) {
            val arr = JSONArray()
            packages.forEach { arr.put(it) }
            groups.put(group.name, arr)
        }
        root.put(KEY_GROUPS, groups)

        return root.toString(2)
    }

    fun parse(json: String): ParseResult {
        val warnings = mutableListOf<String>()
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return ParseResult.Failure("Не удалось распарсить JSON: ${e.message}")
        }

        val version = root.optInt(KEY_VERSION, -1)
        if (version <= 0) return ParseResult.Failure("Неверный формат: отсутствует поле version")
        if (version > CURRENT_VERSION) {
            return ParseResult.Failure(
                "Файл создан более новой версией (v$version). Поддерживается до v$CURRENT_VERSION."
            )
        }

        val settingsObj = root.optJSONObject(KEY_SETTINGS) ?: JSONObject()
        val settings = BackupSettings(
            vpnClientPackage = settingsObj.optStringOrNull(SETTING_VPN_CLIENT_PACKAGE),
            backgroundMonitoring = settingsObj.optBooleanOrNull(SETTING_BACKGROUND_MONITORING),
            freezeOnBoot = settingsObj.optBooleanOrNull(SETTING_FREEZE_ON_BOOT),
            unfreezeOnVpnToggle = settingsObj.optBooleanOrNull(SETTING_UNFREEZE_ON_VPN_TOGGLE),
            launcherSafeMode = settingsObj.optBooleanOrNull(SETTING_LAUNCHER_SAFE_MODE),
        )

        val groups = mutableMapOf<AppGroup, List<String>>()
        val groupsObj = root.optJSONObject(KEY_GROUPS) ?: JSONObject()
        val keys = groupsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val group = runCatching { AppGroup.valueOf(key) }.getOrNull()
            if (group == null) {
                warnings += "Неизвестная группа «$key» — пропущена"
                continue
            }
            val arr = groupsObj.optJSONArray(key) ?: continue
            val packages = (0 until arr.length())
                .map { arr.optString(it) }
                .filter { it.isNotBlank() }
            groups[group] = packages
        }

        val backup = AppConfigBackup(
            version = version,
            exportedAt = root.optString(KEY_EXPORTED_AT),
            appVersion = root.optString(KEY_APP_VERSION),
            settings = settings,
            groups = groups,
        )
        return ParseResult.Success(backup, warnings)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null
}
