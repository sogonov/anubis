package sgnv.anubis.app.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sgnv.anubis.app.ui.MainViewModel

@Composable
fun RecoveryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showResetDialog by remember { mutableStateOf(false) }
    var showScanDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.resetCompleted.collect { count ->
            android.widget.Toast.makeText(
                context,
                "Разморожено приложений: $count. Группы очищены.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            onBack()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("‹ Назад", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Восстановление",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Если после использования Anubis с рабочего стола пропали иконки или приложения не открываются — " +
            "выберите один из сценариев восстановления ниже.\n\n" +
            "Ярлыки на рабочем столе автоматически не вернутся — это ограничение лаунчеров Android. " +
            "Приложения будут доступны в системном меню и поиске, оттуда их можно перетащить обратно.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // Safe reset — DB-based
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Обычный сброс",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Разморозить только те приложения, которые замораживал Anubis, и очистить группы. " +
                    "Безопасный вариант — не трогает то, что вы отключали вручную.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Сбросить Anubis")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Emergency scan — PM-based
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Аварийная разморозка",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Найти и разморозить ВСЕ отключённые пользовательские приложения. " +
                    "Нужно, если вы переустанавливали Anubis и данные о группах утеряны. " +
                    "Внимание: разморозит и те приложения, которые вы отключали вручную через настройки Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showScanDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Разморозить все отключённые")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Сбросить Anubis?") },
            text = {
                Text(
                    "Все приложения в группах будут разморожены, группы очищены. " +
                    "Anubis перестанет чем-либо управлять до повторной настройки."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unfreezeAllAndClear()
                    showResetDialog = false
                }) {
                    Text("Сбросить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showScanDialog) {
        AlertDialog(
            onDismissRequest = { showScanDialog = false },
            title = { Text("Разморозить все отключённые?") },
            text = {
                Text(
                    "Anubis найдёт все отключённые пользовательские приложения на устройстве и разморозит их " +
                    "через Shizuku. Это коснётся и тех приложений, которые вы отключали вручную через настройки Android.\n\n" +
                    "Системные приложения не затрагиваются."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unfreezeAllUserDisabled()
                    showScanDialog = false
                }) {
                    Text("Разморозить всё", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showScanDialog = false }) { Text("Отмена") }
            }
        )
    }
}
