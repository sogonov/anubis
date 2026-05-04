package sgnv.anubis.app.data.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.settings.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ImportMode { REPLACE, MERGE }

data class ImportResult(
    val groupAdditions: Map<AppGroup, Int>,
    val settingsApplied: Int,
    val warnings: List<String>,
) {
    val totalAppsAdded: Int get() = groupAdditions.values.sum()
}

class BackupRepository(
    private val context: Context,
    private val appRepository: AppRepository,
) {
    suspend fun export(appVersion: String): String {
        val groups = AppGroup.entries.associateWith { group ->
            appRepository.getAppsByGroup(group).map { it.packageName }
        }

        val prefs = AppSettings.prefs(context)
        val settings = BackupSettings(
            vpnClientPackage = prefs.getString(AppSettings.KEY_VPN_CLIENT_PACKAGE, null)
                ?.takeIf { it.isNotBlank() },
            backgroundMonitoring = prefs.optBooleanOrNull(AppSettings.KEY_BACKGROUND_MONITORING),
            freezeOnBoot = prefs.optBooleanOrNull(AppSettings.KEY_FREEZE_ON_BOOT),
            unfreezeOnVpnToggle = prefs.optBooleanOrNull(AppSettings.KEY_UNFREEZE_ON_VPN_TOGGLE),
            launcherSafeMode = prefs.optBooleanOrNull(AppSettings.KEY_LAUNCHER_SAFE_MODE),
        )

        val backup = AppConfigBackup(
            version = AppConfigBackupSerializer.CURRENT_VERSION,
            exportedAt = isoTimestamp(),
            appVersion = appVersion,
            settings = settings,
            groups = groups,
        )
        return AppConfigBackupSerializer.toJson(backup)
    }

    suspend fun import(json: String, mode: ImportMode): Result<ImportResult> {
        val parseResult = AppConfigBackupSerializer.parse(json)
        val (backup, parseWarnings) = when (parseResult) {
            is ParseResult.Success -> parseResult.backup to parseResult.warnings
            is ParseResult.Failure -> return Result.failure(IllegalArgumentException(parseResult.message))
        }
        val warnings = parseWarnings.toMutableList()

        // REPLACE wipes everything first so apps that moved out of any group in the
        // backup also disappear locally. MERGE keeps existing assignments and only
        // adds packages we don't currently track.
        if (mode == ImportMode.REPLACE) {
            for (pkg in appRepository.getAllManagedPackages()) {
                appRepository.removeApp(pkg)
            }
        }

        val existingBeforeMerge: Set<String> =
            if (mode == ImportMode.MERGE) appRepository.getAllManagedPackages().toSet() else emptySet()

        val additions = mutableMapOf<AppGroup, Int>().apply {
            AppGroup.entries.forEach { put(it, 0) }
        }
        for ((group, packages) in backup.groups) {
            for (pkg in packages) {
                if (mode == ImportMode.MERGE && pkg in existingBeforeMerge) continue
                appRepository.setAppGroup(pkg, group)
                additions[group] = (additions[group] ?: 0) + 1
            }
        }

        val settingsApplied = applySettings(backup.settings)

        return Result.success(ImportResult(additions, settingsApplied, warnings))
    }

    private fun applySettings(settings: BackupSettings): Int {
        val prefs = AppSettings.prefs(context)
        var applied = 0
        prefs.edit {
            settings.vpnClientPackage?.let {
                putString(AppSettings.KEY_VPN_CLIENT_PACKAGE, it)
                applied++
            }
            settings.backgroundMonitoring?.let {
                putBoolean(AppSettings.KEY_BACKGROUND_MONITORING, it)
                applied++
            }
            settings.freezeOnBoot?.let {
                putBoolean(AppSettings.KEY_FREEZE_ON_BOOT, it)
                applied++
            }
            settings.unfreezeOnVpnToggle?.let {
                putBoolean(AppSettings.KEY_UNFREEZE_ON_VPN_TOGGLE, it)
                applied++
            }
            settings.launcherSafeMode?.let {
                putBoolean(AppSettings.KEY_LAUNCHER_SAFE_MODE, it)
                applied++
            }
        }
        return applied
    }

    private fun SharedPreferences.optBooleanOrNull(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null

    private fun isoTimestamp(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    companion object {
        const val SUGGESTED_FILENAME_PREFIX = "anubis-config"
        const val MIME_TYPE = "application/json"
    }
}
