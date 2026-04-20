package sgnv.anubis.app.ui.screens

import android.graphics.Canvas
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.ui.MainViewModel
import androidx.core.graphics.createBitmap

/**
 * Bottom sheet for inline add-to-group flow on the Home screen.
 * Lists non-system, non-disabled installed apps (filtered by search) and lets user
 * select several apps for adding to [targetGroup] at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAppSheet(
    viewModel: MainViewModel,
    targetGroup: AppGroup,
    onDismiss: () -> Unit,
) {
    val allApps by viewModel.installedApps.collectAsState()
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var selectedPackages by rememberSaveable { mutableStateOf(setOf<String>()) }
    var isApplying by remember { mutableStateOf(false) }
    val normalizedQuery = query.trim()

    val candidates = remember(allApps) {
        allApps.filter { !it.isSystem && !it.isDisabled }
            .sortedBy { it.label.lowercase() }
    }
    val filtered = candidates.filter { app ->
        app.group != targetGroup && (
            normalizedQuery.isBlank() ||
                app.label.contains(normalizedQuery, ignoreCase = true) ||
                app.packageName.contains(normalizedQuery, ignoreCase = true)
        )
    }

    val groupLabel = when (targetGroup) {
        AppGroup.LOCAL -> "Без VPN"
        AppGroup.LOCAL_AUTO_UNFREEZE -> "Без VPN + уведомления"
        AppGroup.VPN_ONLY -> "Только VPN"
        AppGroup.LAUNCH_VPN -> "С VPN"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .imePadding()
        ) {
            Text(
                "Добавить в «$groupLabel»",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Поиск по названию или package") }
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.height(480.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (normalizedQuery.isBlank())
                                "Нет приложений, подходящих для добавления."
                            else
                                "Ничего не найдено по запросу \"$normalizedQuery\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        )
                    }
                }
                items(filtered, key = { it.packageName }) { app ->
                    val isSelected = app.packageName in selectedPackages
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedPackages = if (isSelected) {
                                    selectedPackages - app.packageName
                                } else {
                                    selectedPackages + app.packageName
                                }
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (app.group != null) {
                            val currentLabel = when (app.group) {
                                AppGroup.LOCAL -> "Без VPN"
                                AppGroup.LOCAL_AUTO_UNFREEZE -> "Увед."
                                AppGroup.VPN_ONLY -> "Только VPN"
                                AppGroup.LAUNCH_VPN -> "С VPN"
                            }
                            Text(
                                currentLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                selectedPackages = if (checked) {
                                    selectedPackages + app.packageName
                                } else {
                                    selectedPackages - app.packageName
                                }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = { selectedPackages = emptySet() },
                    enabled = selectedPackages.isNotEmpty() && !isApplying,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Сбросить")
                }
                Button(
                    onClick = {
                        val packagesToAssign = selectedPackages.toList()
                        scope.launch {
                            isApplying = true
                            viewModel.setAppsGroup(packagesToAssign, targetGroup)
                            val message = "Добавлено ${packagesToAssign.size} приложений"
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                    enabled = selectedPackages.isNotEmpty() && !isApplying,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Готово")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
