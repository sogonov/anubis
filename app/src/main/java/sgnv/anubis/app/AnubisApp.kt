package sgnv.anubis.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import org.lsposed.hiddenapibypass.HiddenApiBypass
import sgnv.anubis.app.data.db.AppDatabase
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.service.StealthOrchestrator
import sgnv.anubis.app.shizuku.ShizukuManager
import sgnv.anubis.app.util.AppLogger
import sgnv.anubis.app.vpn.VpnClientManager

class AnubisApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    lateinit var shizukuManager: ShizukuManager
        private set

    // Process-wide singletons so MainViewModel and VpnMonitorService share the same
    // state (_vpnActive, StealthState). Previously each had its own VpnClientManager/
    // orchestrator, so the service could freeze groups on VPN drop but couldn't sync
    // the orchestrator's _state — UI stayed "защита активна" while VPN was actually down.
    val vpnClientManager: VpnClientManager by lazy {
        VpnClientManager(this, shizukuManager)
    }
    val appRepository: AppRepository by lazy {
        AppRepository(database.managedAppDao(), this)
    }
    val orchestrator: StealthOrchestrator by lazy {
        StealthOrchestrator(this, shizukuManager, vpnClientManager, appRepository)
    }

    /**
     * App-wide mutex for mass freeze/unfreeze operations. Prevents duplicate work when
     * Orchestrator (invoked by UI toggle) and VpnMonitorService (invoked by VPN network
     * callback) try to freeze/unfreeze the same groups in parallel. The second path
     * acquires the lock after the first completes, sees everything already in the
     * target state, and returns without doing any work.
     */
    val groupOpMutex: Mutex = Mutex()

    /**
     * Process-wide scope for orchestrator work that must outlive any single suspension
     * point. Used so enable()/disable() can launch the freeze/VPN sequence in a detached
     * Job and wait via job.join() with a hard timeout — if the underlying binder hangs,
     * the user-facing state recovers even though the orphan continues running until
     * the binder eventually returns.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        installCrashLoggingHandler()
        AppLogger.i(TAG, APP_START_LOG_MESSAGE)

        // Required on Android 9+ to reflect into @hide IPackageManager / IActivityManager
        // methods used by the binder-based freeze path in ShizukuManager.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }

        createNotificationChannel()

        // Init Shizuku once — all components share this instance
        shizukuManager = ShizukuManager(packageManager)
        shizukuManager.startListening()

        // Start VPN monitoring at process level — VpnClientManager's NetworkCallback
        // and _vpnActive StateFlow are shared by UI and VpnMonitorService.
        vpnClientManager.startMonitoringVpn()
    }

    override fun onTerminate() {
        shizukuManager.stopListening()
        shizukuManager.unbindUserService()
        super.onTerminate()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN Stealth Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Мониторинг состояния VPN и замороженных приложений"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun installCrashLoggingHandler() {
        if (previousUncaughtExceptionHandler != null) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previousUncaughtExceptionHandler = previous
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.e(
                TAG,
                "FATAL: Uncaught exception on thread=${thread.name}",
                throwable
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CHANNEL_ID = "anubis_monitor"
        private const val TAG = "AnubisApp"
        private const val APP_START_LOG_MESSAGE = "Start"
    }
}
