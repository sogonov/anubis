package sgnv.anubis.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import sgnv.anubis.app.IUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

class ShizukuManager(private val packageManager: PackageManager) {

    @Volatile
    private var userService: IUserService? = null

    private val _status = MutableStateFlow(ShizukuStatus.UNAVAILABLE)
    val status: StateFlow<ShizukuStatus> = _status

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = IUserService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
        if (hasPermission()) {
            bindUserService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        userService = null
        _status.value = ShizukuStatus.UNAVAILABLE
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                _status.value = ShizukuStatus.READY
                bindUserService()
            } else {
                _status.value = ShizukuStatus.NO_PERMISSION
            }
        }

    fun startListening() {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshStatus()
    }

    fun stopListening() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    fun refreshStatus() {
        _status.value = when {
            !isAvailable() -> ShizukuStatus.UNAVAILABLE
            !hasPermission() -> ShizukuStatus.NO_PERMISSION
            else -> ShizukuStatus.READY
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE)
    }

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "sgnv.anubis.app",
            UserService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("shell")
        .version(1)

    fun bindUserService() {
        if (userService != null) return
        if (!isAvailable() || !hasPermission()) return
        try {
            Shizuku.bindUserService(serviceArgs, serviceConnection)
        } catch (_: Exception) {
        }
    }

    fun unbindUserService() {
        try {
            Shizuku.unbindUserService(serviceArgs, serviceConnection, true)
        } catch (_: Exception) {
        }
        userService = null
    }

    suspend fun execShellCommand(vararg args: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCommand(*args)
    }

    /**
     * Force-stops a package via `IActivityManager.forceStopPackage` through a Shizuku-wrapped
     * binder. Bypasses the `am force-stop` shell wrapper, which on some OEM builds
     * (HyperOS / HiOS / MIUI) runs inside a restricted shell environment where it silently
     * no-ops. Binder IPC is not touched by OEM shell sandboxes.
     */
    suspend fun forceStopApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        forceStopInternal(packageName)
    }

    /**
     * Freezes a package by calling `IPackageManager.setApplicationEnabledSetting(DISABLED_USER)`
     * via Shizuku binder, preceded by `IActivityManager.forceStopPackage` to stop any running
     * components (matches Hail's approach — without the stop, background services can linger
     * briefly before the disable state takes effect).
     *
     * This path was added in v0.1.5 to fix #7/#33/#44/#58 — HyperOS/HiOS/MIUI modify the
     * `com.android.shell` environment such that `pm disable-user` / `cmd package disable-user`
     * either times out or returns success without effect. Binder IPC is preserved by OEMs
     * (required for OS function), so direct Binder calls work where shell does not.
     */
    suspend fun freezeApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        forceStopInternal(packageName)
        setAppEnabledState(packageName, enabled = false)
    }

    /**
     * Unfreezes a package by setting `COMPONENT_ENABLED_STATE_ENABLED` via the same
     * binder path as `freezeApp`.
     */
    suspend fun unfreezeApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        setAppEnabledState(packageName, enabled = true)
    }

    fun isAppFrozen(packageName: String): Boolean {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            !info.enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Run a shell command and return its stdout output.
     */
    suspend fun runCommandWithOutput(vararg args: String): String? = withContext(Dispatchers.IO) {
        try {
            val service = userService ?: return@withContext null
            service.execCommandWithOutput(args.toList().toTypedArray())
        } catch (e: Exception) {
            null
        }
    }

    private fun runCommand(vararg args: String): Result<Unit> {
        return try {
            val service = userService
                ?: return Result.failure(IllegalStateException("Shizuku UserService not connected"))
            val exitCode = service.execCommand(args.toList().toTypedArray())
            if (exitCode == 0) {
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException("Command failed with exit code $exitCode"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Binder-based privileged ops ---

    /**
     * Wraps a system-service binder with Shizuku's binder wrapper (which rewrites the
     * caller UID to shell/root) and returns the AIDL interface via its `$Stub.asInterface`.
     * Works for any `android.os.IInterface` whose `Stub.asInterface(IBinder)` static exists.
     */
    private fun asInterface(className: String, serviceName: String): Any {
        val binder = SystemServiceHelper.getSystemService(serviceName)
        val wrapped = ShizukuBinderWrapper(binder)
        val stubClass = Class.forName("$className\$Stub")
        return HiddenApiBypass.invoke(stubClass, null, "asInterface", wrapped)
    }

    private fun setAppEnabledState(packageName: String, enabled: Boolean): Result<Unit> =
        runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            // setApplicationEnabledSetting(String pkg, int newState, int flags, int userId, String callingPackage)
            pm::class.java.getMethod(
                "setApplicationEnabledSetting",
                String::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                String::class.java
            ).invoke(pm, packageName, newState, 0, currentUserId, CALLER_PACKAGE)
            Unit
        }

    private fun forceStopInternal(packageName: String): Result<Unit> = runCatching {
        val am = asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE)
        HiddenApiBypass.invoke(
            am::class.java, am, "forceStopPackage", packageName, currentUserId
        )
        Unit
    }

    companion object {
        const val REQUEST_CODE = 1001

        /**
         * Caller package passed to `setApplicationEnabledSetting`. `com.android.shell` matches
         * the UID Shizuku exposes (when running in ADB mode), which PackageManagerService
         * validates against on some API levels.
         */
        private const val CALLER_PACKAGE = "com.android.shell"

        /**
         * Main user ID for this process. For app UIDs Android encodes userId as `uid / 100_000`
         * (see `UserHandle.PER_USER_RANGE`). Cached — process UID doesn't change.
         */
        private val currentUserId: Int = Process.myUid() / 100_000
    }
}

enum class ShizukuStatus {
    UNAVAILABLE,
    NO_PERMISSION,
    READY
}
