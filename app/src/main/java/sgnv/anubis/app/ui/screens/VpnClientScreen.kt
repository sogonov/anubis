package sgnv.anubis.app.ui.screens

import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import sgnv.anubis.app.ui.MainViewModel
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientControls
import sgnv.anubis.app.vpn.VpnClientType
import sgnv.anubis.app.vpn.VpnControlMode

@Composable
fun VpnClientScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val selectedClient by viewModel.selectedVpnClient.collectAsState()
    val installedClients by viewModel.installedVpnClients.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val context = LocalContext.current
    val pm = context.packageManager

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = query.trim()
    val focusRequester = remember { FocusRequester() }

    val grouped = remember(normalizedQuery) {
        val all = VpnClientType.entries.groupBy { it.brand ?: "__standalone__${it.name}" }
        if (normalizedQuery.isBlank()) all
        else all.mapValues { (_, variants) ->
            variants.filter { variant ->
                val brandMatch = variant.brand?.contains(normalizedQuery, ignoreCase = true) == true
                val nameMatch = variant.displayName.contains(normalizedQuery, ignoreCase = true) ||
                    variant.fullDisplayName.contains(normalizedQuery, ignoreCase = true)
                val pkgMatch = variant.packageName.contains(normalizedQuery, ignoreCase = true)
                brandMatch || nameMatch || pkgMatch
            }
        }.filterValues { it.isNotEmpty() }
    }

    val knownPackages = remember { VpnClientType.entries.map { it.packageName }.toSet() }
    val otherApps = remember(installedApps, normalizedQuery) {
        installedApps
            .asSequence()
            .filter { !it.isSystem && !it.isDisabled }
            .filter { it.packageName !in knownPackages }
            .filter {
                normalizedQuery.isBlank() ||
                    it.label.contains(normalizedQuery, ignoreCase = true) ||
                    it.packageName.contains(normalizedQuery, ignoreCase = true)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    val isCustomSelected = selectedClient.knownType == null

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        if (searchActive) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text("Название или package") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                leadingIcon = {
                    IconButton(onClick = {
                        searchActive = false
                        query = ""
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть поиск")
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("VPN-клиент", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Выберите VPN-клиент для управления",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { searchActive = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Поиск")
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (grouped.isEmpty() && otherApps.isEmpty()) {
                item {
                    Text(
                        "Ничего не найдено по запросу \"$normalizedQuery\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                    )
                }
            }

            grouped.forEach { (_, variants) ->
                val brand = variants.first().brand
                val installedVariants = variants.filter { installedClients.contains(it) }

                item(key = "group_${variants.first().name}") {
                    if (brand != null && installedVariants.size >= 2) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                brand,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                installedVariants.forEach { client ->
                                    VpnClientTile(
                                        client = client,
                                        label = client.displayName,
                                        isInstalled = true,
                                        isSelected = selectedClient.packageName == client.packageName,
                                        isFrozen = !viewModel.isVpnClientEnabled(client.packageName),
                                        onClick = {
                                            viewModel.selectVpnClient(SelectedVpnClient.fromKnown(client))
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    } else {
                        val client = installedVariants.firstOrNull() ?: variants.first()
                        val isInstalled = installedClients.contains(client)
                        VpnClientTile(
                            client = client,
                            label = client.fullDisplayName,
                            isInstalled = isInstalled,
                            isSelected = selectedClient.packageName == client.packageName,
                            isFrozen = isInstalled && !viewModel.isVpnClientEnabled(client.packageName),
                            onClick = {
                                if (isInstalled) viewModel.selectVpnClient(SelectedVpnClient.fromKnown(client))
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                    }
                }
            }

            item(key = "other_divider") {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Другой клиент",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Любое установленное приложение (ручной режим)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            if (isCustomSelected) {
                item(key = "custom_selected_${selectedClient.packageName}") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Выбрано: ${selectedClient.displayName}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    selectedClient.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RadioButton(selected = true, onClick = null)
                        }
                    }
                }
            }

            items(otherApps, key = { "app_${it.packageName}" }) { app ->
                val iconBitmap = remember(app.packageName) {
                    try {
                        val drawable = pm.getApplicationIcon(app.packageName)
                        val bmp = createBitmap(
                            drawable.intrinsicWidth.coerceAtLeast(1),
                            drawable.intrinsicHeight.coerceAtLeast(1)
                        )
                        val canvas = Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bmp.asImageBitmap()
                    } catch (e: Exception) { null }
                }
                val isSelected = isCustomSelected && selectedClient.packageName == app.packageName
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.selectVpnClient(SelectedVpnClient.fromPackage(app.packageName))
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = app.label,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isSelected) {
                        RadioButton(selected = true, onClick = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun VpnClientTile(
    client: VpnClientType,
    label: String,
    isInstalled: Boolean,
    isSelected: Boolean,
    isFrozen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val control = VpnClientControls.getControl(client)
    Card(
        modifier = modifier.clickable(enabled = isInstalled) { onClick() },
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
                    label,
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
            RadioButton(
                selected = isSelected,
                onClick = { if (isInstalled) onClick() },
                enabled = isInstalled
            )
        }
    }
}
