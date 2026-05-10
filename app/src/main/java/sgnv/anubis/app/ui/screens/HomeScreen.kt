package sgnv.anubis.app.ui.screens

import android.content.Intent
import androidx.core.net.toUri
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
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sgnv.anubis.app.R
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.ManagedApp
import sgnv.anubis.app.service.StealthState
import sgnv.anubis.app.settings.HomeSortMode
import sgnv.anubis.app.shizuku.SHIZUKU_PACKAGE
import sgnv.anubis.app.shizuku.ShizukuStatus
import sgnv.anubis.app.shizuku.shizukuUnavailableMessageRes
import sgnv.anubis.app.ui.MainViewModel
import sgnv.anubis.app.ui.util.renderToImageBitmap
import kotlinx.coroutines.delay

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
    val manualUnfreezeWarning by viewModel.manualUnfreezeWarning.collectAsState()

    val isEnabled = stealthState == StealthState.ENABLED
    val isTransitioning = stealthState == StealthState.ENABLING
        || stealthState == StealthState.DISABLING
        || stealthState == StealthState.UNFREEZING
    val cancellable by viewModel.cancellable.collectAsState()
    val paused by viewModel.paused.collectAsState()
    val homeSortMode by viewModel.homeSortMode.collectAsState()

    // Search filters apps inside each group (#86). Not a scroll-and-flash — that
    // produced a jumpy UX because every keystroke re-scrolled and even the keyboard
    // wobbled. Plain filtering keeps the user in place: matching apps stay, the
    // rest fall away, and groups that go empty hide their headers entirely.
    // Collapsed by default (lupa icon only); expands to a TextField on tap, mirroring
    // the AppListScreen / VpnClientsScreen pattern. rememberSaveable so it survives
    // rotation / process recreation.
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    var showNetworkDialog by remember { mutableStateOf(false) }

    val statusColor by animateColorAsState(
        when (stealthState) {
            StealthState.ENABLED -> Color(0xFF2E7D32)
            StealthState.ENABLING, StealthState.DISABLING, StealthState.UNFREEZING -> Color(0xFFF57F17)
            StealthState.DISABLED -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "statusColor"
    )

    // Context menu state
    var menuApp by remember { mutableStateOf<String?>(null) }
    val dismissMenuSheet: () -> Unit = { menuApp = null }

    // Inline "add to group" sheet: non-null means that group's sheet is open.
    var addingToGroup by remember { mutableStateOf<AppGroup?>(null) }
    val dismissAddSheet: () -> Unit = { addingToGroup = null }

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
        // Pause banner. Sits above the status card so the user can't miss that
        // automatic group actions are off — a paused-but-still-green status card
        // would be misleading. Tapping "Возобновить" flips it back instantly.
        if (paused) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
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
                            stringResource(R.string.paused_banner_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            stringResource(R.string.paused_banner_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { viewModel.setPaused(false) }) {
                        Text(stringResource(R.string.paused_resume))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

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
                            StealthState.ENABLED -> stringResource(R.string.home_status_enabled)
                            StealthState.ENABLING -> stringResource(R.string.home_status_enabling)
                            StealthState.DISABLING -> stringResource(R.string.home_status_disabling)
                            StealthState.UNFREEZING -> stringResource(R.string.home_status_unfreezing)
                            StealthState.DISABLED -> stringResource(R.string.home_status_disabled)
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
                    // Spinner + cancel cross. The cross only appears once orchestrator
                    // marks the operation cancellable (currentJob != null in flight) —
                    // before that there's nothing to cancel and the cross would just
                    // produce a broken click. Tapping the box cancels the in-flight
                    // enable/disable; the orchestrator rolls back side effects.
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(32.dp),
                            color = Color.White,
                            strokeWidth = 3.dp
                        )
                        if (cancellable) {
                            IconButton(
                                onClick = { viewModel.cancelTransition() },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.home_cancel_transition),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                } else {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = {
                            val vpnIntent = viewModel.getVpnPermissionIntent()
                            if (vpnIntent != null) { onRequestVpnPermission(vpnIntent); return@Switch }
                            viewModel.toggleStealth()
                        },
                        enabled = true
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

        // Error card — only when Shizuku is READY. Otherwise the dedicated Shizuku card below
        // already explains the situation, and showing both produces a confusing duplicate
        // (issue #85).
        if (shizukuStatus == ShizukuStatus.READY) {
            lastError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        if (shizukuStatus != ShizukuStatus.READY) {
            val context = LocalContext.current
            val shizukuStatusTextRes = shizukuUnavailableMessageRes(shizukuStatus)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    shizukuStatusTextRes?.let { textRes ->
                        Text(
                            stringResource(textRes),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (shizukuStatus) {
                            ShizukuStatus.NOT_INSTALLED -> Button(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri()))
                            }) { Text("Скачать") }
                            ShizukuStatus.NOT_RUNNING -> Button(onClick = {
                                val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                                if (launch != null) context.startActivity(launch)
                            }) { Text("Открыть Shizuku") }
                            ShizukuStatus.NO_PERMISSION -> Button(onClick = { viewModel.requestShizukuPermission() }) { Text("Разрешить") }
                            ShizukuStatus.READY -> Unit // unreachable
                        }
                    }
                }
            }
        }

        // Search + sort row (#86, #56). Search is collapsed by default — only the
        // lupa icon shows — and expands on tap to a full-width TextField. Same
        // pattern as AppListScreen / VpnClientsScreen so the gesture is consistent
        // across the app. Sort is a dropdown next to the search icon when collapsed;
        // hidden while search is active to keep the row uncluttered.
        Spacer(Modifier.height(16.dp))
        if (searchActive) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.common_search)) },
                leadingIcon = {
                    IconButton(onClick = {
                        searchActive = false
                        searchQuery = ""
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.app_list_cd_close_search))
                    }
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_clear))
                        }
                    }
                }
            )
            LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Network status pill — used to be a full Card at the bottom of the
                // screen, but with many groups the user rarely scrolled that far.
                // Inline pill keeps it visible and turns the otherwise empty left
                // half of this row into something useful. Tap → AlertDialog with
                // full info + refresh.
                val ni = networkInfo
                val pillText = when {
                    networkLoading -> "Проверяем..."
                    ni != null -> {
                        val parts = buildList {
                            if (ni.pingMs > 0) add("${ni.pingMs} мс")
                            if (ni.country.isNotBlank()) add(ni.country)
                        }
                        parts.joinToString(" · ").ifEmpty { "Сеть" }
                    }
                    else -> "Проверить сеть"
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showNetworkDialog = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (networkLoading) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        pillText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { searchActive = true }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.common_search))
                }
                var sortMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_sort_cd))
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.app_list_sort_name)) },
                            onClick = {
                                viewModel.setHomeSortMode(HomeSortMode.NAME)
                                sortMenuExpanded = false
                            },
                            trailingIcon = {
                                if (homeSortMode == HomeSortMode.NAME) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.app_list_sort_package)) },
                            onClick = {
                                viewModel.setHomeSortMode(HomeSortMode.PACKAGE)
                                sortMenuExpanded = false
                            },
                            trailingIcon = {
                                if (homeSortMode == HomeSortMode.PACKAGE) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            }
                        )
                    }
                }
            }
        }

        // App groups
            Spacer(Modifier.height(16.dp))
            AppGroupSection(
                title = "Без VPN + уведомления",
                subtitle = "Заморожены при VPN, активны и шлют уведомления без VPN",
                apps = localAutoUnfreezeApps,
                tintColor = MaterialTheme.colorScheme.secondary,
                viewModel = viewModel,
                frozenVersion = frozenVersion,
                sortMode = homeSortMode,
                searchQuery = searchQuery,
                onClick = { pkg -> viewModel.launchLocal(pkg) },
                onLongClick = { pkg -> menuApp = pkg },
                onAdd = { addingToGroup = AppGroup.LOCAL_AUTO_UNFREEZE }
            )

            Spacer(Modifier.height(16.dp))
            AppGroupSection(
                title = "Без VPN",
                subtitle = "Изолированы от VPN. Нажмите для запуска без VPN",
                apps = localApps,
                tintColor = MaterialTheme.colorScheme.error,
                viewModel = viewModel,
                frozenVersion = frozenVersion,
                sortMode = homeSortMode,
                searchQuery = searchQuery,
                onClick = { pkg -> viewModel.launchLocal(pkg) },
                onLongClick = { pkg -> menuApp = pkg },
                onAdd = { addingToGroup = AppGroup.LOCAL }
            )

            Spacer(Modifier.height(16.dp))
            AppGroupSection(
                title = "Запуск с VPN",
                subtitle = "Нажмите для запуска через VPN",
                apps = launchVpnApps,
                tintColor = MaterialTheme.colorScheme.primary,
                viewModel = viewModel,
                frozenVersion = frozenVersion,
                sortMode = homeSortMode,
                searchQuery = searchQuery,
                onClick = { pkg -> viewModel.launchWithVpn(pkg) },
                onLongClick = { pkg -> menuApp = pkg },
                onAdd = { addingToGroup = AppGroup.LAUNCH_VPN }
            )

            Spacer(Modifier.height(16.dp))
            AppGroupSection(
                title = "Только VPN",
                subtitle = "Заморожены без VPN. Нажмите для запуска через VPN",
                apps = vpnOnlyApps,
                tintColor = MaterialTheme.colorScheme.tertiary,
                viewModel = viewModel,
                frozenVersion = frozenVersion,
                sortMode = homeSortMode,
                searchQuery = searchQuery,
                onClick = { pkg -> viewModel.launchWithVpn(pkg) },
                onLongClick = { pkg -> menuApp = pkg },
                onAdd = { addingToGroup = AppGroup.VPN_ONLY }
            )

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

    // Network info dialog — opened by tapping the inline pill in the search row.
    if (showNetworkDialog) {
        val ni = networkInfo
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNetworkDialog = false },
            title = { Text("Сеть") },
            text = {
                Column {
                    when {
                        networkLoading -> Text(
                            "Проверяем соединение...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ni != null -> {
                            if (ni.pingMs > 0) InfoRow("Ping", "${ni.pingMs} мс")
                            if (ni.country.isNotBlank()) {
                                val loc = if (ni.city.isNotBlank()) "${ni.country}, ${ni.city}" else ni.country
                                InfoRow("Локация", loc)
                            }
                            if (ni.ip.isNotBlank()) InfoRow("IP", ni.ip)
                            if (ni.org.isNotBlank()) InfoRow("Провайдер", ni.org)
                        }
                        else -> Text(
                            "Нажмите «Обновить», чтобы проверить соединение.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.refreshNetworkInfo() },
                    enabled = !networkLoading
                ) { Text("Обновить") }
            },
            dismissButton = {
                TextButton(onClick = { showNetworkDialog = false }) { Text("Закрыть") }
            }
        )
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
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    viewModel.dismissDangerousAppWarning()
                }) { Text("Подробнее") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDangerousAppWarning() }) { Text("Закрыть") }
            }
        )
    }

    // Manual unfreeze warning (issue #81): user is unfreezing a LOCAL app while VPN is on.
    manualUnfreezeWarning?.let { pkg ->
        val context = LocalContext.current
        val pm = context.packageManager
        val label = remember(pkg) {
            try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }
            catch (e: Exception) { pkg }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissManualUnfreezeWarning() },
            title = { Text("Разморозить под VPN?") },
            text = {
                Text(
                    "«$label» в группе «Без VPN». Если разморозить сейчас, " +
                    "приложение запустится через активный VPN — это противоречит цели изоляции.\n\n" +
                    "Лучше сначала выключить VPN, затем работать с приложением."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmManualUnfreeze() }) {
                    Text("Всё равно разморозить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissManualUnfreezeWarning() }) { Text("Отмена") }
            }
        )
    }

    // Inline add-to-group sheet (one per tap on the "+" in a group header)
    addingToGroup?.let { group ->
        AddAppSheet(
            viewModel = viewModel,
            targetGroup = group,
            onDismiss = dismissAddSheet
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
            runCatching {
                pm.getApplicationIcon(pkg).renderToImageBitmap()
            }.getOrNull()
        }

        ModalBottomSheet(
            onDismissRequest = dismissMenuSheet,
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

                // Group selector — issue #78. Direct assignment instead of cycling
                // through groups by tap on the home tile.
                val currentGroup = remember(pkg, frozenVersion) { viewModel.getAppGroup(pkg) }
                Text(
                    "Группа",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)
                )
                GroupSelectionRow(
                    label = "Без VPN",
                    selected = currentGroup == AppGroup.LOCAL,
                    onClick = { viewModel.setAppGroup(pkg, AppGroup.LOCAL); dismissMenuSheet() }
                )
                GroupSelectionRow(
                    label = "Без VPN + уведомления",
                    selected = currentGroup == AppGroup.LOCAL_AUTO_UNFREEZE,
                    onClick = { viewModel.setAppGroup(pkg, AppGroup.LOCAL_AUTO_UNFREEZE); dismissMenuSheet() }
                )
                GroupSelectionRow(
                    label = "Только VPN",
                    selected = currentGroup == AppGroup.VPN_ONLY,
                    onClick = { viewModel.setAppGroup(pkg, AppGroup.VPN_ONLY); dismissMenuSheet() }
                )
                GroupSelectionRow(
                    label = "Запуск с VPN",
                    selected = currentGroup == AppGroup.LAUNCH_VPN,
                    onClick = { viewModel.setAppGroup(pkg, AppGroup.LAUNCH_VPN); dismissMenuSheet() }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Actions
                BottomSheetAction(
                    text = if (isFrozen) "Разморозить" else "Заморозить",
                    onClick = { viewModel.requestToggleAppFrozen(pkg); dismissMenuSheet() }
                )

                BottomSheetAction(
                    text = "Создать ярлык на рабочий стол",
                    onClick = {
                        viewModel.createShortcut(pkg)
                        dismissMenuSheet()
                    }
                )

                BottomSheetAction(
                    text = "Убрать из группы",
                    onClick = {
                        viewModel.removeFromGroup(pkg)
                        dismissMenuSheet()
                    }
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun GroupSelectionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
        )
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
    sortMode: HomeSortMode,
    searchQuery: String,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val pm = LocalContext.current.packageManager
    // Label lookup is the expensive part (PackageManager IPC). Cache once per
    // group and reuse for filter + sort — otherwise typing each character would
    // trigger 50+ PM calls per group.
    val labelledApps = remember(apps) {
        apps.map { app ->
            val label = runCatching {
                pm.getApplicationInfo(app.packageName, 0).loadLabel(pm).toString()
            }.getOrDefault(app.packageName)
            app to label
        }
    }
    val q = searchQuery.trim()
    val visibleApps = remember(labelledApps, sortMode, q) {
        val filtered = if (q.isEmpty()) labelledApps else labelledApps.filter { (app, label) ->
            app.packageName.contains(q, ignoreCase = true) || label.contains(q, ignoreCase = true)
        }
        when (sortMode) {
            HomeSortMode.NAME -> filtered.sortedBy { it.second.lowercase() }
            HomeSortMode.PACKAGE -> filtered.sortedBy { it.first.packageName.lowercase() }
        }.map { it.first }
    }

    // Hide the whole section while a search is active and this group has no
    // matches — otherwise the screen would be a wall of empty group headers.
    // With an empty query, a real (just empty) group still renders so the user
    // can use the "+" button to add the first app.
    if (q.isNotEmpty() && visibleApps.isEmpty()) return

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

    val rows = (visibleApps.size + 3) / 4
    val gridHeight = if (rows == 0) 0.dp else (rows * 88 - 8).dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxWidth().height(gridHeight),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false
    ) {
        items(visibleApps, key = { "${it.packageName}_$frozenVersion" }) { app ->
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
        runCatching {
            pm.getApplicationIcon(packageName).renderToImageBitmap()
        }.getOrNull()
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

