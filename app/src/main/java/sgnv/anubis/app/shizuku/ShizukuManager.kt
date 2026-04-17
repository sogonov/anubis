package sgnv.anubis.app.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import sgnv.anubis.app.IUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

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

    suspend fun forceStopApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCommand("am", "force-stop", packageName)
    }

    // `cmd package` is a direct binder call into PackageManagerService. `pm` is a shell script
    // that wraps `cmd package` but spawns a Dalvik VM on every invocation — ~100-200ms of pure
    // JVM startup overhead per call. At 32 apps that adds up to seconds.
    suspend fun freezeApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCommand("cmd", "package", "disable-user", "--user", "0", packageName)
    }

    suspend fun unfreezeApp(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCommand("cmd", "package", "enable", packageName)
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

    companion object {
        const val REQUEST_CODE = 1001
    }
}

enum class ShizukuStatus {
    UNAVAILABLE,
    NO_PERMISSION,
    READY
}
