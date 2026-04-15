package sgnv.anubis.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sgnv.anubis.app.shizuku.ShizukuStatus
import sgnv.anubis.app.ui.MainViewModel
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientControls
import sgnv.anubis.app.vpn.VpnClientType
import sgnv.anubis.app.vpn.VpnControlMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val selectedClient by viewModel.selectedVpnClient.collectAsState()
    val installedClients by viewModel.installedVpnClients.collectAsState()
    val shizukuStatus by viewModel.shizukuStatus.collectAsState()
    var showAppPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(24.dp))

        Text("VPN-клиент", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Выберите VPN-клиент для управления",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        // Known clients
        VpnClientType.entries.forEach { client ->
            val isInstalled = installedClients.contains(client)
            val isSelected = selectedClient.packageName == client.packageName
            val isFrozen = isInstalled && !viewModel.isVpnClientEnabled(client.packageName)
            val control = VpnClientControls.getControl(client)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(enabled = isInstalled) {
                        viewModel.selectVpnClient(SelectedVpnClient.fromKnown(client))
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            client.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (isInstalled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                        val modeText = when {
                            !isInstalled -> "Не установлен"
                            isFrozen -> "Заморожен!"
                            control.mode == VpnControlMode.SEPARATE -> "Полное управление"
                            control.mode == VpnControlMode.TOGGLE -> "Авто вкл. / принудит. выкл."
                            else -> "Ручной режим"
                        }
                        Text(
                            modeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                isFrozen -> MaterialTheme.colorScheme.error
                                !isInstalled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                control.mode == VpnControlMode.SEPARATE -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    RadioButton(selected = isSelected, onClick = {
                        if (isInstalled) viewModel.selectVpnClient(SelectedVpnClient.fromKnown(client))
                    }, enabled = isInstalled)
                }
            }
        }

        // Custom client option
        val isCustomSelected = selectedClient.knownType == null
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { showAppPicker = true },
            colors = CardDefaults.cardColors(
                containerColor = if (isCustomSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isCustomSelected) "Другой: ${selectedClient.displayName}" else "Другой клиент...",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text("Выбрать любое приложение (ручной режим)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RadioButton(selected = isCustomSelected, onClick = { showAppPicker = true })
            }
        }

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
                        android.net.Uri.parse("https://github.com/sogonov/anubis")
                    )
                    context.startActivity(intent)
                }) {
                    Text("GitHub", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    // App picker bottom sheet
    if (showAppPicker) {
        val allApps by viewModel.installedApps.collectAsState()
        val context = LocalContext.current
        val pm = context.packageManager

        // Filter to apps that have INTERNET permission (likely VPN clients)
        val vpnCandidates = remember(allApps) {
            allApps.filter { !it.isSystem && !it.isDisabled }
                .sortedBy { it.label.lowercase() }
        }

        ModalBottomSheet(
            onDismissRequest = { showAppPicker = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Выберите VPN-клиент", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(400.dp)) {
                    items(vpnCandidates, key = { it.packageName }) { app ->
                        val iconBitmap = remember(app.packageName) {
                            try {
                                val drawable = pm.getApplicationIcon(app.packageName)
                                val bmp = Bitmap.createBitmap(
                                    drawable.intrinsicWidth.coerceAtLeast(1),
                                    drawable.intrinsicHeight.coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888
                                )
                                val canvas = Canvas(bmp)
                                drawable.setBounds(0, 0, canvas.width, canvas.height)
                                drawable.draw(canvas)
                                bmp.asImageBitmap()
                            } catch (e: Exception) { null }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectVpnClient(SelectedVpnClient.fromPackage(app.packageName))
                                    showAppPicker = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (iconBitmap != null) {
                                Image(bitmap = iconBitmap, contentDescription = app.label, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                            }
                            Column {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
