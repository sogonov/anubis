package sgnv.anubis.app.shizuku

import android.content.pm.PackageManager

/** Called on Dispatchers.IO through the Shizuku-wrapped package manager. */
internal fun setAppEnabledStatePersistently(
    pm: Any, packageName: String, enabled: Boolean, userId: Int, callingPackage: String,
) {
    val newState = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    pm::class.java.getMethod(
        "setApplicationEnabledSetting",
        String::class.java, Int::class.java, Int::class.java, Int::class.java, String::class.java,
    ).invoke(pm, packageName, newState, 0, userId, callingPackage)

    // Otherwise Android delays the disk write, so a quick reboot can undo a freeze.
    // This API also supports Android 10. Flush even on an unchanged state: a previous
    // attempt may have changed RAM state but failed to persist it (SYNCHRONOUS alone
    // skips the flush when setApplicationEnabledSetting is a no-op).
    flushPackageState(pm, userId)
}

internal fun flushPackageState(pm: Any, userId: Int) {
    pm::class.java.getMethod("flushPackageRestrictionsAsUser", Int::class.java)
        .invoke(pm, userId)
}
