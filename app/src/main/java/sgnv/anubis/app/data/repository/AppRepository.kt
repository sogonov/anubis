package sgnv.anubis.app.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import sgnv.anubis.app.data.DefaultRestrictedApps
import sgnv.anubis.app.data.db.ManagedAppDao
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.InstalledAppInfo
import sgnv.anubis.app.data.model.ManagedApp
import sgnv.anubis.app.policy.FreezeSafetyPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(
    private val dao: ManagedAppDao,
    private val context: Context
) {
    suspend fun getPackagesByGroup(group: AppGroup): Set<String> =
        dao.getPackageNamesByGroup(group).toSet()

    suspend fun getAllManagedPackages(): List<String> =
        AppGroup.entries.flatMap { getPackagesByGroup(it) }.distinct()

    suspend fun getAppsByGroup(group: AppGroup): List<ManagedApp> =
        dao.getByGroup(group)

    suspend fun getAppGroup(packageName: String): AppGroup? =
        dao.get(packageName)?.group

    suspend fun setAppGroup(packageName: String, group: AppGroup) {
        dao.insert(ManagedApp(packageName, group))
    }

    suspend fun removeApp(packageName: String) {
        dao.delete(packageName)
    }

    /** Cycle through groups: none → LOCAL → LOCAL_AUTO_UNFREEZE → VPN_ONLY → LAUNCH_VPN → none */
    suspend fun cycleGroup(packageName: String) {
        val current = dao.get(packageName)
        when (current?.group) {
            null -> dao.insert(ManagedApp(packageName, AppGroup.LOCAL))
            AppGroup.LOCAL -> dao.insert(ManagedApp(packageName, AppGroup.LOCAL_AUTO_UNFREEZE))
            AppGroup.LOCAL_AUTO_UNFREEZE -> dao.insert(ManagedApp(packageName, AppGroup.VPN_ONLY))
            AppGroup.VPN_ONLY -> dao.insert(ManagedApp(packageName, AppGroup.LAUNCH_VPN))
            AppGroup.LAUNCH_VPN -> dao.delete(packageName)
        }
    }

    suspend fun autoSelectRestricted(): Int {
        val installed = getInstalledPackageNames()
        val toSelect = installed
            .filter { DefaultRestrictedApps.isKnownRestricted(it) }
            .filter { !FreezeSafetyPolicy.isProtected(it) }
            .map { ManagedApp(it, AppGroup.LOCAL) }
        dao.insertAll(toSelect)
        return toSelect.size
    }

    suspend fun getInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
        )

        // Single batch read instead of N point queries — meaningful for users with
        // many installed apps. Independent of the startup-splash logic.
        val managedByPackage = dao.getAll().associateBy { it.packageName }

        apps.map { appInfo ->
            val label = try {
                appInfo.loadLabel(pm).toString()
            } catch (e: Exception) {
                appInfo.packageName
            }
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val managed = managedByPackage[appInfo.packageName]
            InstalledAppInfo(
                packageName = appInfo.packageName,
                label = label,
                isSystem = isSystem,
                group = managed?.group,
                isDisabled = !appInfo.enabled
            )
        }.sortedBy { it.label.lowercase() }
    }

    private suspend fun getInstalledPackageNames(): Set<String> = withContext(Dispatchers.IO) {
        context.packageManager
            .getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
            .map { it.packageName }
            .toSet()
    }
}
