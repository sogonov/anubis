package sgnv.anubis.app.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import sgnv.anubis.app.shizuku.ShizukuStatus
import sgnv.anubis.app.ui.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onOpenRecovery: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val shizukuStatus by viewModel.shizukuStatus.collectAsState()
    val unfreezeManagedAppsOnVpnToggle by viewModel.unfreezeManagedAppsOnVpnToggle.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(24.dp))

        // Background monitoring
        Text("Мониторинг", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        val bgMonitoring by viewModel.backgroundMonitoring.collectAsState()

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Фоновый мониторинг VPN", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Автозаморозка при изменении VPN вне Anubis",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "После включения VPN размораживает «Только VPN», после отключения — «Без VPN».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Устаревший переключатель. Будет удалён в v0.1.5. Вместо него используйте группу «Без VPN + уведомления» для нужных приложений.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                androidx.compose.material3.Switch(
                    checked = unfreezeManagedAppsOnVpnToggle,
                    onCheckedChange = { viewModel.setUnfreezeManagedAppsOnVpnToggle(it) }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Shizuku status
        Text("Shizuku", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Статус", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (shizukuStatus) {
                        ShizukuStatus.READY -> "Подключён"
                        ShizukuStatus.NO_PERMISSION -> "Нет разрешения"
                        ShizukuStatus.UNAVAILABLE -> "Не доступен"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (shizukuStatus == ShizukuStatus.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(24.dp))

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
                    Text("Восстановление", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "Разморозить приложения, очистить группы",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(24.dp))

        // About
        Text("О приложении", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Anubis v${sgnv.anubis.app.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Управляет группами приложений и VPN-подключением. Изолирует приложения по группам для контроля сетевого доступа.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        Text("Проверять обновления", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Автоматическая проверка при запуске",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    updateInfo?.let { info ->
                        if (!info.isUpdateAvailable) {
                            Text(
                                "У вас последняя версия",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                val context = LocalContext.current
                TextButton(onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        "https://github.com/sogonov/anubis".toUri()
                    )
                    context.startActivity(intent)
                }) {
                    Text("GitHub", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
