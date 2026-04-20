package sgnv.anubis.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import sgnv.anubis.app.IUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

class ShizukuManager(private val packageManager: PackageManager) {

    @Volatile
    private var userService: IUserService? = null

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<ShizukuStatus> = _status

    private fun initialStatus(): ShizukuStatus =
        if (isInstalled()) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED

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
        // Binder gone but the package is still installed (otherwise we wouldn't be running here).
        _status.value = ShizukuStatus.NOT_RUNNING
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
        // Check binder first: Sui (root module) exposes the Shizuku binder without installing
        // the moe.shizuku.privileged.api package, so "not installed" is only meaningful when
        // the binder is also absent.
        _status.value = when {
            isAvailable() -> if (hasPermission()) ShizukuStatus.READY else ShizukuStatus.NO_PERMISSION
            isInstalled() -> ShizukuStatus.NOT_RUNNING
            else -> ShizukuStatus.NOT_INSTALLED
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun isInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
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

    /**
     * Returns immediately if the UserService is already connected.
     * Otherwise polls up to [timeoutMs] for onServiceConnected — useful in tile/widget
     * onClick where a fixed delay(200) wastes time on the common warm path.
     */
    suspend fun awaitUserService(timeoutMs: Long = 500): Boolean {
        if (userService != null) return true
        bindUserService()
        return withTimeoutOrNull(timeoutMs) {
            while (userService == null) delay(50)
            true
        } != null
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
    suspend fun freezeApp(packageName: String): Result<Unit> =
        withTimeoutOrNull(BINDER_OP_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                forceStopInternal(packageName)
                setAppEnabledState(packageName, enabled = false)
            }
        } ?: Result.failure(RuntimeException("freezeApp timed out: $packageName"))

    /**
     * Unfreezes a package by setting `COMPONENT_ENABLED_STATE_ENABLED` via the same
     * binder path as `freezeApp`.
     */
    suspend fun unfreezeApp(packageName: String): Result<Unit> =
        withTimeoutOrNull(BINDER_OP_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                setAppEnabledState(packageName, enabled = true)
            }
        } ?: Result.failure(RuntimeException("unfreezeApp timed out: $packageName"))

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
        }

    private fun forceStopInternal(packageName: String): Result<Unit> = runCatching {
        val am = asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE)
        HiddenApiBypass.invoke(
            am::class.java, am, "forceStopPackage", packageName, currentUserId
        )
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
         * Binder IPC calls (freeze/unfreeze) normally complete in < 200 ms.
         * 5 s is long enough to survive a temporarily busy system and short enough
         * to prevent the groupOpMutex from being held indefinitely on a hung call.
         */
        private const val BINDER_OP_TIMEOUT_MS = 5_000L

        /**
         * Main user ID for this process. For app UIDs Android encodes userId as `uid / 100_000`
         * (see `UserHandle.PER_USER_RANGE`). Cached — process UID doesn't change.
         */
        private val currentUserId: Int = Process.myUid() / 100_000
    }
}

enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    NO_PERMISSION,
    READY
}

const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
