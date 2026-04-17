package sgnv.anubis.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sgnv.anubis.app.data.model.ManagedApp
import sgnv.anubis.app.service.StealthState
import sgnv.anubis.app.shizuku.ShizukuStatus
import sgnv.anubis.app.ui.MainViewModel

private val grayscaleFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onRequestVpnPermission: (Intent) -> Unit = {},
    onOpenRecovery: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val stealthState by viewModel.stealthState.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val shizukuStatus by viewModel.shizukuStatus.collectAsState()
    val vpnActive by viewModel.vpnActive.collectAsState()
    val activeVpnClient by viewModel.activeVpnClient.collectAsState()
    val activeVpnPackage by viewModel.activeVpnPackage.collectAsState()
    val networkInfo by viewModel.networkInfo.collectAsState()
    val networkLoading by viewModel.networkLoading.collectAsState()

    val localApps by viewModel.localApps.collectAsState()
    val localAutoUnfreezeApps by viewModel.localAutoUnfreezeApps.collectAsState()
    val vpnOnlyApps by viewModel.vpnOnlyApps.collectAsState()
    val launchVpnApps by viewModel.launchVpnApps.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val hasDisabledUserApps = installedApps.any { !it.isSystem && it.isDisabled }

    // Observing this triggers recomposition when any app is frozen/unfrozen
    val frozenVersion by viewModel.frozenVersion.collectAsState()
    val dangerousAppWarning by viewModel.dangerousAppWarning.collectAsState()

    val isEnabled = stealthState == StealthState.ENABLED
    val isTransitioning = stealthState == StealthState.ENABLING || stealthState == StealthState.DISABLING

    val statusColor by animateColorAsState(
        when (stealthState) {
            StealthState.ENABLED -> Color(0xFF2E7D32)
            StealthState.ENABLING, StealthState.DISABLING -> Color(0xFFF57F17)
            StealthState.DISABLED -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "statusColor"
    )

    // Context menu state
    var menuApp by remember { mutableStateOf<String?>(null) }
    // Inline "add to group" sheet: non-null means that group's sheet is open.
    var addingToGroup by remember { mutableStateOf<sgnv.anubis.app.data.model.AppGroup?>(null) }

    // Ephemeral benchmark message. Conflate-style: each new emit overwrites the previous
    // and resets the 3s hide timer. Avoids flicker if multiple emits arrive in a burst
    // (e.g. a hypothetical duplicate orchestration path).
    var benchmarkMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.benchmark.collect { msg -> benchmarkMsg = msg }
    }
    LaunchedEffect(benchmarkMsg) {
        if (benchmarkMsg != null) {
            kotlinx.coroutines.delay(3000)
            benchmarkMsg = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Status + Toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = statusColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (stealthState) {
                            StealthState.ENABLED -> "ЗАЩИТА АКТИВНА"
                            StealthState.ENABLING -> "ВКЛЮЧЕНИЕ..."
                            StealthState.DISABLING -> "ОТКЛЮЧЕНИЕ..."
                            StealthState.DISABLED -> "ЗАЩИТА ОТКЛЮЧЕНА"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled || isTransitioning) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        when {
                            vpnActive && activeVpnClient != null -> "VPN: ${activeVpnClient!!.fullDisplayName}"
                            vpnActive && activeVpnPackage != null -> "VPN: $activeVpnPackage"
                            vpnActive -> "VPN активен"
                            else -> "VPN выключен"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isEnabled || isTransitioning) Color.White.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isTransitioning) {
                    CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
                } else {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = {
                            val vpnIntent = viewModel.getVpnPermissionIntent()
                            if (vpnIntent != null) { onRequestVpnPermission(vpnIntent); return@Switch }
                            viewModel.toggleStealth()
                        },
                        enabled = !isTransitioning && shizukuStatus == ShizukuStatus.READY
                    )
                }
            }
        }

        // Benchmark easter-egg (auto-hides after 3s)
        benchmarkMsg?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    msg,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Error / Shizuku warning
        lastError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        if (shizukuStatus != ShizukuStatus.READY) {
            val context = LocalContext.current
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (shizukuStatus == ShizukuStatus.UNAVAILABLE) "Shizuku не установлен или не запущен" else "Нет разрешения Shizuku",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (shizukuStatus == ShizukuStatus.UNAVAILABLE) {
                            Button(onClick = {
                                context.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://shizuku.rikka.app/download/")
                                ))
                            }) { Text("Скачать") }
                        }
                        if (shizukuStatus == ShizukuStatus.NO_PERMISSION) {
                            Button(onClick = { viewModel.requestShizukuPermission() }) { Text("Разрешить") }
                        }
                    }
                }
            }
        }

        // App groups
        if (localAutoUnfreezeApps.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            AppGroupSection(
                title = "Без VPN + уведомления",
                subtitle = "Заморожены при VPN, активны и шлют уведомления без VPN",
                apps = localAutoUnfreezeApps,
                tintColor = MaterialTheme.colorScheme.secondary,
                viewModel = viewModel,
                frozenVersion = frozenVersion,
                onClick = { pkg -> viewModel.launchLocal(pkg) },
                onLongClick = { pkg -> menuApp = pkg },
                onAdd = { addingToGroup = sgnv.anubis.app.data.model.AppGroup.LOCAL_AUTO_UNFREEZE }
            )
        }

        if (localApps.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            AppGroupSection(
                title = "Без VPN",
                subtitle = "Изолированы от VPN. Нажмите для запуска без VPN",
                apps = localApps,
                tintColor = MaterialTheme.colorScheme.error,
                viewModel = viewModel,
                frozenVersion = frozenVersion,
                onClick = { pkg -> viewModel.launchLocal(pkg) },
                onLongClick = { pkg -> menuApp = pkg },
                onAdd = { addingToGroup = sgnv.anubis.app.data.model.AppGroup.LOCAL }
            )
        }

        if (launchVpnApps.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            AppGroupSection(
                title = "Запуск с VPN",
                subtitle = "Нажмите для запуска через VPN",
                apps = launchVpnApps,
                tintColor = MaterialTheme.colorScheme.primary,
                viewModel = viewModel,
                frozenVersion = frozenVersion,
                onClick = { pkg -> viewModel.launchWithVpn(pkg) },
                onLongClick = { pkg -> menuApp = pkg },
                onAdd = { addingToGroup = sgnv.anubis.app.data.model.AppGroup.LAUNCH_VPN }
            )
        }

        if (vpnOnlyApps.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            AppGroupSection(
                title = "Только VPN",
                subtitle = "Заморожены без VPN. Нажмите для запуска через VPN",
                apps = vpnOnlyApps,
                tintColor = MaterialTheme.colorScheme.tertiary,
                viewModel = viewModel,
                frozenVersion = frozenVersion,
                onClick = { pkg -> viewModel.launchWithVpn(pkg) },
                onLongClick = { pkg -> menuApp = pkg },
                onAdd = { addingToGroup = sgnv.anubis.app.data.model.AppGroup.VPN_ONLY }
            )
        }

        if (localApps.isEmpty() && localAutoUnfreezeApps.isEmpty() && vpnOnlyApps.isEmpty() && launchVpnApps.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Нет приложений в группах. Добавьте хотя бы в одну:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EmptyGroupAddButton(
                    label = "Без VPN + уведомления",
                    tint = MaterialTheme.colorScheme.secondary,
                    onClick = { addingToGroup = sgnv.anubis.app.data.model.AppGroup.LOCAL_AUTO_UNFREEZE }
                )
                EmptyGroupAddButton(
                    label = "Без VPN",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { addingToGroup = sgnv.anubis.app.data.model.AppGroup.LOCAL }
                )
                EmptyGroupAddButton(
                    label = "Запуск с VPN",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = { addingToGroup = sgnv.anubis.app.data.model.AppGroup.LAUNCH_VPN }
                )
                EmptyGroupAddButton(
                    label = "Только VPN",
                    tint = MaterialTheme.colorScheme.tertiary,
                    onClick = { addingToGroup = sgnv.anubis.app.data.model.AppGroup.VPN_ONLY }
                )
            }
        }

        // Network
        Spacer(Modifier.height(16.dp))
        NetworkCard(viewModel, networkInfo, networkLoading)

        // Recovery hint — only shown when there are disabled user apps on device
        if (hasDisabledUserApps) {
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenRecovery),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Что-то пошло не так?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Разморозить приложения и очистить группы",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Text(
                        "›",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // Dangerous app warning
    dangerousAppWarning?.let { url ->
        val context = LocalContext.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissDangerousAppWarning() },
            title = { Text("Обнаружено опасное приложение", fontWeight = FontWeight.Bold) },
            text = {
                Text("На устройстве установлен клиент Telega (ru.dahl.messenger). " +
                    "Это приложение перехватывает шифрование Telegram, подменяя серверы и ключи RSA. " +
                    "Все ваши сообщения могут быть прочитаны третьими лицами.\n\n" +
                    "Настоятельно рекомендуем удалить Telega и завершить сессию в настройках Telegram.")
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    ))
                    viewModel.dismissDangerousAppWarning()
                }) { Text("Подробнее") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDangerousAppWarning() }) { Text("Закрыть") }
            }
        )
    }

    // Inline add-to-group sheet (one per tap on the "+" in a group header)
    addingToGroup?.let { group ->
        AddAppSheet(
            viewModel = viewModel,
            targetGroup = group,
            onDismiss = { addingToGroup = null }
        )
    }

    // Bottom sheet context menu
    menuApp?.let { pkg ->
        val isFrozen = viewModel.isAppFrozen(pkg)
        val context = LocalContext.current
        val pm = context.packageManager
        val label = remember(pkg) {
            try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }
            catch (e: Exception) { pkg }
        }
        val iconBitmap = remember(pkg) {
            try {
                val drawable = pm.getApplicationIcon(pkg)
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

        ModalBottomSheet(
            onDismissRequest = { menuApp = null },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                // Header with icon and name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = label,
                            modifier = Modifier.size(48.dp),
                            colorFilter = if (isFrozen) grayscaleFilter else null
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                    Column {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (isFrozen) "Заморожено" else "Активно",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isFrozen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()

                // Actions
                BottomSheetAction(
                    text = if (isFrozen) "Разморозить" else "Заморозить",
                    onClick = { viewModel.toggleAppFrozen(pkg); menuApp = null }
                )

                BottomSheetAction(
                    text = "Создать ярлык на рабочий стол",
                    onClick = {
                        viewModel.createShortcut(pkg)
                        menuApp = null
                    }
                )

                BottomSheetAction(
                    text = "Убрать из группы",
                    onClick = {
                        viewModel.removeFromGroup(pkg)
                        menuApp = null
                    }
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AppGroupSection(
    title: String,
    subtitle: String,
    apps: List<ManagedApp>,
    tintColor: Color,
    viewModel: MainViewModel,
    frozenVersion: Long,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = tintColor
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onAdd) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Добавить приложение",
                tint = tintColor
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    val pm = LocalContext.current.packageManager
    val sortedApps = remember(apps) {
        apps.sortedBy { app ->
            try {
                pm.getApplicationInfo(app.packageName, 0)
                    .loadLabel(pm).toString().lowercase()
            } catch (e: Exception) {
                app.packageName.lowercase()
            }
        }
    }

    val rows = (sortedApps.size + 3) / 4
    val gridHeight = (rows * 88).dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxWidth().height(gridHeight),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false
    ) {
        items(sortedApps, key = { "${it.packageName}_$frozenVersion" }) { app ->
            val isFrozen = viewModel.isAppFrozen(app.packageName)
            AppIconItem(
                packageName = app.packageName,
                isFrozen = isFrozen,
                onClick = { onClick(app.packageName) },
                onLongClick = { onLongClick(app.packageName) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppIconItem(
    packageName: String,
    isFrozen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager

    val label = remember(packageName) {
        try { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }
        catch (e: Exception) { packageName.substringAfterLast('.') }
    }

    val iconBitmap = remember(packageName) {
        try {
            val drawable = pm.getApplicationIcon(packageName)
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

    Column(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = label,
                modifier = Modifier.size(48.dp),
                colorFilter = if (isFrozen) grayscaleFilter else null
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
            color = if (isFrozen) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun NetworkCard(
    viewModel: MainViewModel,
    networkInfo: sgnv.anubis.app.data.model.NetworkInfo?,
    networkLoading: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Сеть", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Button(
                    onClick = { viewModel.refreshNetworkInfo() },
                    enabled = !networkLoading,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    if (networkLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Проверить", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (networkLoading) {
                Text("Проверяем соединение...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (networkInfo != null) {
                if (networkInfo.pingMs > 0) InfoRow("Ping", "${networkInfo.pingMs} мс")
                if (networkInfo.country.isNotBlank()) {
                    val loc = if (networkInfo.city.isNotBlank()) "${networkInfo.country}, ${networkInfo.city}" else networkInfo.country
                    InfoRow("Локация", loc)
                }
                var showDetails by remember { mutableStateOf(false) }
                if (showDetails) {
                    InfoRow("IP", networkInfo.ip)
                    if (networkInfo.org.isNotBlank()) InfoRow("Провайдер", networkInfo.org)
                }
                TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showDetails) "Скрыть детали" else "Показать IP и провайдер", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text("Нажмите «Проверить»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BottomSheetAction(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyGroupAddButton(
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = tint)
            Spacer(Modifier.width(12.dp))
            Text(
                "Добавить в «$label»",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = tint
            )
        }
    }
}
