package sgnv.anubis.app.shizuku

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageStateWriterTest {
    // Models Android's separate in-memory and persisted package restrictions.
    class PackageManagerService {
        val memory = mutableMapOf(0 to PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            10 to PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        val disk = memory.toMutableMap()
        var rejectWrite = false
        var rejectFlush = false
        var flushes = 0

        @Suppress("UNUSED_PARAMETER")
        fun setApplicationEnabledSetting(pkg: String, state: Int, flags: Int, user: Int, caller: String) {
            if (rejectWrite) error("write denied")
            memory[user] = state
        }

        fun flushPackageRestrictionsAsUser(user: Int) {
            if (rejectFlush) error("flush failed")
            disk[user] = memory.getValue(user)
            flushes++
        }

        fun reboot() { memory.clear(); memory.putAll(disk) }
    }

    private fun write(pm: PackageManagerService, enabled: Boolean, user: Int = 0) =
        runCatching { setAppEnabledStatePersistently(pm, "target", enabled, user, "com.android.shell") }

    @Test fun `freeze survives immediate reboot`() {
        val pm = PackageManagerService()
        write(pm, false).getOrThrow()
        pm.reboot()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, pm.memory[0])
    }

    @Test fun `unfreeze survives immediate reboot`() {
        val pm = PackageManagerService()
        write(pm, false).getOrThrow()
        write(pm, true).getOrThrow()
        pm.reboot()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.memory[0])
    }

    @Test fun `only the current profile is changed and persisted`() {
        val pm = PackageManagerService()
        write(pm, false, 10).getOrThrow()
        pm.reboot()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.memory[0])
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, pm.memory[10])
    }

    @Test fun `failed persistence is reported instead of successful freeze`() {
        val pm = PackageManagerService().apply { rejectFlush = true }
        assertTrue(write(pm, false).isFailure)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.disk[0])
    }

    @Test fun `retry persists even if the in-memory state is already disabled`() {
        val pm = PackageManagerService().apply { rejectFlush = true }
        assertTrue(write(pm, false).isFailure)
        pm.rejectFlush = false
        write(pm, false).getOrThrow()
        pm.reboot()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, pm.memory[0])
    }

    @Test fun `failed state change is reported without flushing unrelated state`() {
        val pm = PackageManagerService().apply { rejectWrite = true }
        assertTrue(write(pm, false).isFailure)
        assertEquals(0, pm.flushes)
    }
}
