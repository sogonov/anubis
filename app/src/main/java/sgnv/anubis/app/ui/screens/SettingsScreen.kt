package sgnv.anubis.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import sgnv.anubis.app.R
import sgnv.anubis.app.data.backup.ImportMode
import sgnv.anubis.app.shizuku.SHIZUKU_PACKAGE
import sgnv.anubis.app.shizuku.ShizukuStatus
import sgnv.anubis.app.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onOpenRecovery: () -> Unit = {},
    onOpenJournal: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val shizukuStatus by viewModel.shizukuStatus.collectAsState()
    val unfreezeManagedAppsOnVpnToggle by viewModel.unfreezeManagedAppsOnVpnToggle.collectAsState()
    val launcherSafeMode by viewModel.launcherSafeMode.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Настройки", style = typography.headlineSmall, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(24.dp))

        // Background monitoring
        Text("Мониторинг", style = typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        val bgMonitoring by viewModel.backgroundMonitoring.collectAsState()

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Фоновый мониторинг VPN", style = typography.bodyMedium)
                    Text(
                        "Автозаморозка при изменении VPN вне Anubis",
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = bgMonitoring,
                    onCheckedChange = { viewModel.setBackgroundMonitoring(it) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Issue #31 setting — deprecated: the per-group LOCAL_AUTO_UNFREEZE replaces this
        // global toggle. Kept for users who enabled it in v0.1.4-beta.1. Removed in v0.1.5.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Размораживать группы при включении/отключении VPN",
                        style = typography.bodyMedium
                    )
                    Text(
                        "После включения VPN размораживает «Только VPN», после отключения — «Без VPN».",
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Устаревший переключатель. Будет удалён в v0.1.5. Вместо него используйте группу «Без VPN + уведомления» для нужных приложений.",
                        style = typography.bodySmall,
                        color = colorScheme.error
                    )
                }
                androidx.compose.material3.Switch(
                    checked = unfreezeManagedAppsOnVpnToggle,
                    onCheckedChange = { viewModel.setUnfreezeManagedAppsOnVpnToggle(it) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_launcher_safe_mode_title),
                        style = typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.settings_launcher_safe_mode_description),
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = launcherSafeMode,
                    onCheckedChange = { viewModel.setLauncherSafeMode(it) }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Shizuku status — clickable when not ready: opens Shizuku activity, the download page,
        // or requests permission depending on the state (issue #84).
        Text("Shizuku", style = typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val shizukuContext = LocalContext.current
        val shizukuCardModifier = when (shizukuStatus) {
            ShizukuStatus.READY -> Modifier.fillMaxWidth()
            ShizukuStatus.NO_PERMISSION -> Modifier.fillMaxWidth().clickable { viewModel.requestShizukuPermission() }
            ShizukuStatus.NOT_RUNNING -> Modifier.fillMaxWidth().clickable {
                val launch = shizukuContext.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                if (launch != null) shizukuContext.startActivity(launch)
            }
            ShizukuStatus.NOT_INSTALLED -> Modifier.fillMaxWidth().clickable {
                shizukuContext.startActivity(
                    Intent(Intent.ACTION_VIEW, SettingsScreenConstants.SHIZUKU_DOWNLOAD_URL.toUri())
                )
            }
        }
        Card(modifier = shizukuCardModifier) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_shizuku_status_label), style = typography.bodyMedium)
                    if (shizukuStatus != ShizukuStatus.READY) {
                        val hintRes = when (shizukuStatus) {
                            ShizukuStatus.NOT_INSTALLED -> R.string.settings_shizuku_hint_not_installed
                            ShizukuStatus.NOT_RUNNING -> R.string.settings_shizuku_hint_not_running
                            ShizukuStatus.NO_PERMISSION -> R.string.settings_shizuku_hint_no_permission
                            ShizukuStatus.READY -> null
                        }
                        hintRes?.let {
                            Text(
                                stringResource(it),
                                style = typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    stringResource(
                        when (shizukuStatus) {
                            ShizukuStatus.READY -> R.string.settings_shizuku_value_ready
                            ShizukuStatus.NO_PERMISSION -> R.string.settings_shizuku_value_no_permission
                            ShizukuStatus.NOT_RUNNING -> R.string.settings_shizuku_value_not_running
                            ShizukuStatus.NOT_INSTALLED -> R.string.settings_shizuku_value_not_installed
                        }
                    ),
                    style = typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (shizukuStatus == ShizukuStatus.READY) colorScheme.primary else colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Journal entry
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenJournal() }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_journal_title),
                        style = typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        stringResource(R.string.settings_journal_description),
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                Text("›", style = typography.headlineSmall, color = colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Backup / restore — issue #131. Tunguska automation tokens are intentionally
        // excluded from export so the JSON file is safe to share or store unencrypted.
        var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> uri?.let { viewModel.exportConfig(it) } }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) pendingImportUri = uri }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    exportLauncher.launch("anubis-config-$date.json")
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Экспорт настроек", style = typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "Сохранить группы и настройки в JSON-файл",
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                Text("›", style = typography.headlineSmall, color = colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { importLauncher.launch(arrayOf("application/json")) }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Импорт настроек", style = typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "Восстановить группы и настройки из файла",
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                Text("›", style = typography.headlineSmall, color = colorScheme.onSurfaceVariant)
            }
        }

        pendingImportUri?.let { uri ->
            AlertDialog(
                onDismissRequest = { pendingImportUri = null },
                title = { Text("Импорт настроек") },
                text = {
                    Text(
                        "«Заменить» очистит текущие группы и применит импортируемую конфигурацию. " +
                            "«Объединить» добавит из файла только те приложения, которых ещё нет в группах. " +
                            "Настройки в обоих режимах применяются из файла."
                    )
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = {
                            viewModel.importConfig(uri, ImportMode.MERGE)
                            pendingImportUri = null
                        }) { Text("Объединить") }
                        TextButton(onClick = {
                            viewModel.importConfig(uri, ImportMode.REPLACE)
                            pendingImportUri = null
                        }) { Text("Заменить") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingImportUri = null }) { Text("Отмена") }
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        // Recovery entry
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenRecovery() }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.recovery_title),
                        style = typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        stringResource(R.string.settings_recovery_description),
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                Text("›", style = typography.headlineSmall, color = colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(24.dp))

        // About
        Text("О приложении", style = typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Anubis v${sgnv.anubis.app.BuildConfig.VERSION_NAME}",
                    style = typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Управляет группами приложений и VPN-подключением. Изолирует приложения по группам для контроля сетевого доступа.",
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                val updateCheckEnabled by viewModel.updateCheckEnabled.collectAsState()
                val updateCheckInProgress by viewModel.updateCheckInProgress.collectAsState()
                val updateInfo by viewModel.updateInfo.collectAsState()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Проверять обновления", style = typography.bodyMedium)
                        Text(
                            "Автоматическая проверка при запуске",
                            style = typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = updateCheckEnabled,
                        onCheckedChange = { viewModel.setUpdateCheckEnabled(it) }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { viewModel.checkForUpdatesNow() },
                        enabled = !updateCheckInProgress
                    ) {
                        Text(
                            if (updateCheckInProgress) "Проверка..." else "Проверить сейчас",
                            style = typography.labelMedium
                        )
                    }
                    updateInfo?.let { info ->
                        if (!info.isUpdateAvailable) {
                            Text(
                                "У вас последняя версия",
                                style = typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                val context = LocalContext.current
                Column {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, SettingsScreenConstants.GITHUB_URL.toUri())
                        )
                    }) {
                        Text("GitHub", style = typography.labelMedium)
                    }
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, SettingsScreenConstants.TELEGRAM_URL.toUri())
                        )
                    }) {
                        Text("Telegram", style = typography.labelMedium)
                    }
                }
            }
        }
    }
}

private object SettingsScreenConstants {
    const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    const val GITHUB_URL = "https://github.com/sogonov/anubis"
    const val TELEGRAM_URL = "https://t.me/anubis_app"
}
