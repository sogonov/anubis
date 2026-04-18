package sgnv.anubis.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.sync.Mutex
import org.lsposed.hiddenapibypass.HiddenApiBypass
import sgnv.anubis.app.data.db.AppDatabase
import sgnv.anubis.app.shizuku.ShizukuManager

class AnubisApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    lateinit var shizukuManager: ShizukuManager
        private set

    /**
     * App-wide mutex for mass freeze/unfreeze operations. Prevents duplicate work when
     * Orchestrator (invoked by UI toggle) and VpnMonitorService (invoked by VPN network
     * callback) try to freeze/unfreeze the same groups in parallel. The second path
     * acquires the lock after the first completes, sees everything already in the
     * target state, and returns without doing any work.
     */
    val groupOpMutex: Mutex = Mutex()

    override fun onCreate() {
        super.onCreate()

        // Required on Android 9+ to reflect into @hide IPackageManager / IActivityManager
        // methods used by the binder-based freeze path in ShizukuManager.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }

        createNotificationChannel()

        // Init Shizuku once — all components share this instance
        shizukuManager = ShizukuManager(packageManager)
        shizukuManager.startListening()
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

    companion object {
        const val CHANNEL_ID = "anubis_monitor"
    }
}
