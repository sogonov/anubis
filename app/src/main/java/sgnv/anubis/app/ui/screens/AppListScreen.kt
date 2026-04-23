package sgnv.anubis.app.ui.screens

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sgnv.anubis.app.data.DefaultRestrictedApps
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.InstalledAppInfo
import sgnv.anubis.app.ui.MainViewModel
import sgnv.anubis.app.ui.util.renderToImageBitmap

private val grayscaleFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

private enum class SortBy(val label: String) {
    GROUP("По группе"),
    NAME("По названию"),
    PACKAGE("По package")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val allApps by viewModel.installedApps.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortBy by rememberSaveable { mutableStateOf(SortBy.PACKAGE) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    var showAutoWarning by remember { mutableStateOf(false) }
    val dismissAutoWarning: () -> Unit = { showAutoWarning = false }

    var pendingFirstAdd by remember { mutableStateOf<String?>(null) }
    val dismissFirstAddDialog: () -> Unit = { pendingFirstAdd = null }

    val focusRequester = remember { FocusRequester() }

    val userApps = allApps.filter { !it.isSystem }
    val systemApps = allApps.filter { it.isSystem }
    val currentList = if (selectedTab == 0) userApps else systemApps
    val normalizedQuery = searchQuery.trim()
    val filteredList = currentList.filter { app ->
        normalizedQuery.isBlank() ||
            app.label.contains(normalizedQuery, ignoreCase = true) ||
            app.packageName.contains(normalizedQuery, ignoreCase = true)
    }
    val sortedList = when (sortBy) {
        SortBy.NAME -> filteredList.sortedBy { it.label.lowercase() }
        SortBy.PACKAGE -> filteredList.sortedBy { it.packageName }
        SortBy.GROUP -> filteredList.sortedWith(
            compareBy({ it.group?.ordinal ?: Int.MAX_VALUE }, { it.label.lowercase() })
        )
    }

    val noVpnCount = allApps.count { it.group == AppGroup.LOCAL }
    val autoUnfreezeCount = allApps.count { it.group == AppGroup.LOCAL_AUTO_UNFREEZE }
    val vpnOnlyCount = allApps.count { it.group == AppGroup.VPN_ONLY }
    val launchCount = allApps.count { it.group == AppGroup.LAUNCH_VPN }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        if (searchActive) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text("Название или package") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                leadingIcon = {
                    IconButton(onClick = {
                        searchActive = false
                        searchQuery = ""
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть поиск")
                    }
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить")
                        }
                    }
                }
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        if (prefs.getBoolean("seen_auto_warning", false)) {
                            viewModel.autoSelectRestricted()
                        } else {
                            showAutoWarning = true
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Авто-выбор", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    "Без VPN: $noVpnCount | Увед.: $autoUnfreezeCount | Только VPN: $vpnOnlyCount | С VPN: $launchCount",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                IconButton(onClick = { searchActive = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Поиск")
                }
                Box {
                    TextButton(onClick = { sortMenuOpen = true }) {
                        Text(sortBy.label, style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false }
                    ) {
                        SortBy.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    sortBy = option
                                    sortMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Legend
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LegendRow(
                color = MaterialTheme.colorScheme.error,
                label = "Без VPN",
                description = "При включении VPN — заморожено. Запуск только без VPN."
            )
            LegendRow(
                color = MaterialTheme.colorScheme.secondary,
                label = "Увед.",
                description = "При VPN заморожено, без VPN — автоматически разморожено (для уведомлений)."
            )
            LegendRow(
                color = MaterialTheme.colorScheme.tertiary,
                label = "Только VPN",
                description = "Без VPN заморожено. Запуск только через VPN."
            )
            LegendRow(
                color = MaterialTheme.colorScheme.primary,
                label = "С VPN",
                description = "Не замораживается. При запуске автоматически включается VPN."
            )
        }

        Spacer(Modifier.height(8.dp))

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Пользовательские (${userApps.size})") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Системные (${systemApps.size})") }
            )
        }

        val pullState = rememberPullToRefreshState()

        LaunchedEffect(pullState.isRefreshing) {
            if (pullState.isRefreshing) {
                viewModel.refreshInstalledAppsSync()
                pullState.endRefresh()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                if (sortedList.isEmpty()) {
                    item {
                        Text(
                            if (normalizedQuery.isBlank()) "Список пуст. Потяните вниз, чтобы обновить." else "Ничего не найдено по запросу \"$normalizedQuery\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                }
                items(sortedList, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        isKnownRestricted = DefaultRestrictedApps.isKnownRestricted(app.packageName),
                        onCycleGroup = {
                            if (app.group == null && !prefs.getBoolean("seen_first_add_warning", false)) {
                                pendingFirstAdd = app.packageName
                            } else {
                                viewModel.cycleAppGroup(app.packageName)
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // Material3 1.2.x has a bug: PullToRefreshContainer stays visible at rest because
            // translationY math doesn't fully hide it until 1.3.0. Hide it manually unless
            // the user is actively pulling or we're refreshing.
            if (pullState.isRefreshing || pullState.progress > 0f) {
                PullToRefreshContainer(
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }

    pendingFirstAdd?.let { pkg ->
        AlertDialog(
            onDismissRequest = dismissFirstAddDialog,
            title = { Text("Добавить в группу?") },
            text = {
                Text(
                    "Приложение будет заморожено — после этого его ярлык исчезнет с рабочего стола. " +
                    "Восстановить можно в любой момент через долгое нажатие на Главной → «Разморозить» → «Убрать из группы». " +
                    "Для массовой отмены — раздел «Восстановление» в настройках."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit { putBoolean("seen_first_add_warning", true) }
                    viewModel.cycleAppGroup(pkg)
                    dismissFirstAddDialog()
                }) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = dismissFirstAddDialog) { Text("Отмена") }
            }
        )
    }

    if (showAutoWarning) {
        AlertDialog(
            onDismissRequest = dismissAutoWarning,
            title = { Text("Перед заморозкой") },
            text = {
                Text(
                    "Anubis заморозит известные российские приложения через системный pm disable. " +
                    "Большинство лаунчеров уберут их иконки с рабочего стола — это не удаление, " +
                    "приложения и данные сохранятся. Но после разморозки ярлыки не вернутся на прежние позиции — " +
                    "придётся расставить руками.\n\n" +
                    "Если что-то пойдёт не так — в настройках есть раздел «Восстановление» с массовой разморозкой."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit { putBoolean("seen_auto_warning", true)}
                    dismissAutoWarning()
                    viewModel.autoSelectRestricted()
                }) { Text("Заморозить") }
            },
            dismissButton = {
                TextButton(onClick = dismissAutoWarning) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun LegendRow(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    description: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Card(
            modifier = Modifier.size(10.dp).padding(top = 4.dp),
            colors = CardDefaults.cardColors(containerColor = color)
        ) {}
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppRow(app: InstalledAppInfo, isKnownRestricted: Boolean, onCycleGroup: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager

    val iconBitmap = remember(app.packageName) {
        runCatching {
            pm.getApplicationIcon(app.packageName).renderToImageBitmap()
        }.getOrNull()
    }

    val containerColor = when (app.group) {
        AppGroup.LOCAL -> MaterialTheme.colorScheme.errorContainer
        AppGroup.LOCAL_AUTO_UNFREEZE -> MaterialTheme.colorScheme.secondaryContainer
        AppGroup.VPN_ONLY -> MaterialTheme.colorScheme.tertiaryContainer
        AppGroup.LAUNCH_VPN -> MaterialTheme.colorScheme.primaryContainer
        null -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCycleGroup() },
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.label,
                    modifier = Modifier.size(40.dp),
                    colorFilter = if (app.isDisabled) grayscaleFilter else null
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (app.isDisabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (isKnownRestricted) {
                        Text(
                            " *",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Group badge
            Text(
                when (app.group) {
                    AppGroup.LOCAL -> "Без VPN"
                    AppGroup.LOCAL_AUTO_UNFREEZE -> "Увед."
                    AppGroup.VPN_ONLY -> "VPN"
                    AppGroup.LAUNCH_VPN -> "С VPN"
                    null -> "—"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = when (app.group) {
                    AppGroup.LOCAL -> MaterialTheme.colorScheme.error
                    AppGroup.LOCAL_AUTO_UNFREEZE -> MaterialTheme.colorScheme.secondary
                    AppGroup.VPN_ONLY -> MaterialTheme.colorScheme.tertiary
                    AppGroup.LAUNCH_VPN -> MaterialTheme.colorScheme.primary
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
