package sgnv.anubis.app.ui.screens

import android.content.Context
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sgnv.anubis.app.R
import sgnv.anubis.app.data.DefaultRestrictedApps
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.InstalledAppInfo
import sgnv.anubis.app.ui.MainViewModel
import sgnv.anubis.app.ui.util.renderToImageBitmap

private val grayscaleFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private const val PREF_APP_LIST_LEGEND_EXPANDED = "app_list_legend_expanded"

private enum class SortBy(@StringRes val labelRes: Int) {
    GROUP(R.string.app_list_sort_group),
    NAME(R.string.app_list_sort_name),
    PACKAGE(R.string.app_list_sort_package)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val allApps by viewModel.installedApps.collectAsState()
    // Observing this triggers recomposition when any app is frozen/unfrozen externally
    // (e.g. via the Home screen context menu). Without this, app.isDisabled stays stale
    // until the next pull-to-refresh — issue #126.
    val frozenVersion by viewModel.frozenVersion.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortBy by rememberSaveable { mutableStateOf(SortBy.PACKAGE) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var legendExpanded by remember {
        mutableStateOf(prefs.getBoolean(PREF_APP_LIST_LEGEND_EXPANDED, true))
    }

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
                placeholder = { Text(stringResource(R.string.app_list_search_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                leadingIcon = {
                    IconButton(onClick = {
                        searchActive = false
                        searchQuery = ""
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.app_list_cd_close_search))
                    }
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_clear))
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
                    Text(stringResource(R.string.app_list_auto_select), style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    stringResource(
                        R.string.app_list_group_counts,
                        noVpnCount,
                        autoUnfreezeCount,
                        vpnOnlyCount,
                        launchCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                IconButton(onClick = { searchActive = true }) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.common_search))
                }
                Box {
                    TextButton(onClick = { sortMenuOpen = true }) {
                        Text(stringResource(sortBy.labelRes), style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false }
                    ) {
                        SortBy.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.labelRes)) },
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    legendExpanded = !legendExpanded
                    prefs.edit { putBoolean(PREF_APP_LIST_LEGEND_EXPANDED, legendExpanded) }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.app_list_group_statuses_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (legendExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.ArrowDropDown,
                contentDescription = null
            )
        }

        if (legendExpanded) {
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LegendRow(
                    color = MaterialTheme.colorScheme.error,
                    label = stringResource(R.string.group_label_no_vpn),
                    description = stringResource(R.string.app_list_legend_desc_local)
                )
                LegendRow(
                    color = MaterialTheme.colorScheme.secondary,
                    label = stringResource(R.string.group_label_notify_short),
                    description = stringResource(R.string.app_list_legend_desc_local_auto)
                )
                LegendRow(
                    color = MaterialTheme.colorScheme.tertiary,
                    label = stringResource(R.string.app_list_legend_label_vpn_only),
                    description = stringResource(R.string.app_list_legend_desc_vpn_only)
                )
                LegendRow(
                    color = MaterialTheme.colorScheme.primary,
                    label = stringResource(R.string.group_label_with_vpn),
                    description = stringResource(R.string.app_list_legend_desc_launch_vpn)
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.app_list_tab_user_label))
                        Text(
                            stringResource(R.string.app_list_tab_count, userApps.size),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.app_list_tab_system_label))
                        Text(
                            stringResource(R.string.app_list_tab_count, systemApps.size),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            )
        }

        val pullState = rememberPullToRefreshState()

        LaunchedEffect(pullState.isRefreshing) {
            if (pullState.isRefreshing) {
                viewModel.refreshInstalledAppsSync()
                pullState.endRefresh()
            }
        }

        // weight(1f) instead of fillMaxSize: when this Column lives inside a Scaffold slot
        // with edge-to-edge insets (issue #117 — Poco X7 Pro / HyperOS / Android 15), incoming
        // height constraints can be Infinity, which makes a fillMaxSize Box collapse to its
        // intrinsic min height — the LazyColumn ends up looking like a "small scrollable window
        // inside a bigger scroll area". weight forces the Box to consume the remaining vertical
        // space in the parent Column deterministically.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
                            if (normalizedQuery.isBlank()) {
                                stringResource(R.string.app_list_empty)
                            } else {
                                stringResource(R.string.app_list_not_found, normalizedQuery)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                }
                items(sortedList, key = { "${it.packageName}_$frozenVersion" }) { app ->
                    // Live frozen status (issue #126) — viewModel.isAppFrozen() reflects current
                    // ShizukuManager state, not the cached InstalledAppInfo.isDisabled which only
                    // refreshes on pull-to-refresh.
                    val isFrozen = viewModel.isAppFrozen(app.packageName)
                    AppRow(
                        app = app,
                        isFrozen = isFrozen,
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
            title = { Text(stringResource(R.string.app_list_first_add_title)) },
            text = {
                Text(stringResource(R.string.app_list_first_add_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit { putBoolean("seen_first_add_warning", true) }
                    viewModel.cycleAppGroup(pkg)
                    dismissFirstAddDialog()
                }) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = dismissFirstAddDialog) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showAutoWarning) {
        AlertDialog(
            onDismissRequest = dismissAutoWarning,
            title = { Text(stringResource(R.string.app_list_auto_warning_title)) },
            text = {
                Text(stringResource(R.string.app_list_auto_warning_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit { putBoolean("seen_auto_warning", true)}
                    dismissAutoWarning()
                    viewModel.autoSelectRestricted()
                }) { Text(stringResource(R.string.common_freeze)) }
            },
            dismissButton = {
                TextButton(onClick = dismissAutoWarning) { Text(stringResource(R.string.common_cancel)) }
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
private fun AppRow(
    app: InstalledAppInfo,
    isFrozen: Boolean,
    isKnownRestricted: Boolean,
    onCycleGroup: () -> Unit,
) {
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
                    colorFilter = if (isFrozen) grayscaleFilter else null
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isFrozen) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
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
                    AppGroup.LOCAL -> stringResource(R.string.group_label_no_vpn)
                    AppGroup.LOCAL_AUTO_UNFREEZE -> stringResource(R.string.group_label_notify_short)
                    AppGroup.VPN_ONLY -> stringResource(R.string.app_list_group_badge_vpn_only)
                    AppGroup.LAUNCH_VPN -> stringResource(R.string.group_label_with_vpn)
                    null -> stringResource(R.string.app_list_group_badge_none)
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
