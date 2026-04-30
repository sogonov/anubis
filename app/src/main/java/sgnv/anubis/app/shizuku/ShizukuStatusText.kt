package sgnv.anubis.app.shizuku

import androidx.annotation.StringRes
import sgnv.anubis.app.R

@StringRes
fun shizukuUnavailableMessageRes(status: ShizukuStatus): Int? = when (status) {
    ShizukuStatus.NOT_INSTALLED -> R.string.shizuku_toast_not_installed
    ShizukuStatus.NOT_RUNNING -> R.string.shizuku_toast_not_running
    ShizukuStatus.NO_PERMISSION -> R.string.shizuku_toast_no_permission
    ShizukuStatus.READY -> null
}
