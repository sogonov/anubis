package sgnv.anubis.app.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import sgnv.anubis.app.settings.AppSettings

class BootReceiverTest {
    @Test fun `monitoring starts at boot independently of freeze on boot`() = checkBoot(true, false)
    @Test fun `boot freezing runs even with continuous monitoring disabled`() = checkBoot(false, true)
    @Test fun `both settings share one foreground service start`() = checkBoot(true, true)
    @Test fun `disabled settings do not start a foreground service`() = checkBoot(false, false)

    private fun checkBoot(monitoring: Boolean, freeze: Boolean) {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val boot = mock(Intent::class.java)
        `when`(boot.action).thenReturn(Intent.ACTION_BOOT_COMPLETED)
        `when`(context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.getBoolean(AppSettings.KEY_BACKGROUND_MONITORING, false)).thenReturn(monitoring)
        `when`(prefs.getBoolean(AppSettings.KEY_FREEZE_ON_BOOT, false)).thenReturn(freeze)
        mockConstruction(Intent::class.java).use { intents ->
            BootReceiver().onReceive(context, boot)
            if (freeze || monitoring) {
                val command = intents.constructed().single()
                verify(context).startForegroundService(command)
                verify(command).setAction(if (freeze) "sgnv.anubis.app.BOOT_MONITOR" else VpnMonitorService.ACTION_START)
            } else {
                verify(context, never()).startForegroundService(any(Intent::class.java))
            }
        }
    }
}
