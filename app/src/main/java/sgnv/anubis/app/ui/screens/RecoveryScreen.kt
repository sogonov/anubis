package sgnv.anubis.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons.AutoMirrored.Filled
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import sgnv.anubis.app.R
import sgnv.anubis.app.shizuku.shizukuUnavailableMessageRes
import sgnv.anubis.app.ui.MainViewModel

@Composable
fun RecoveryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val shizukuStatus by viewModel.shizukuStatus.collectAsState()

    var showResetDialog by remember { mutableStateOf(false) }
    val dismissResetDialog: () -> Unit = { showResetDialog = false }

    var showScanDialog by remember { mutableStateOf(false) }
    val dismissScanDialog: () -> Unit = { showScanDialog = false }

    val requireShizukuReady: () -> Boolean = {
        val messageRes = shizukuUnavailableMessageRes(shizukuStatus)
        messageRes == null || run {
            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
            false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.resetCompleted.collect { count ->
            Toast.makeText(
                context,
                context.getString(R.string.recovery_reset_completed, count),
                Toast.LENGTH_LONG
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
                Icon(imageVector = Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.recovery_back), style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.recovery_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.recovery_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // Safe reset — DB-based
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.recovery_normal_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.recovery_normal_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (!requireShizukuReady()) return@Button
                        showResetDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.recovery_reset_anubis_button))
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
                    stringResource(R.string.recovery_emergency_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.recovery_emergency_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        if (!requireShizukuReady()) return@OutlinedButton
                        showScanDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(stringResource(R.string.recovery_unfreeze_all_disabled_button))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = dismissResetDialog,
            title = { Text(stringResource(R.string.recovery_reset_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.recovery_reset_dialog_text)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unfreezeAllAndClear()
                    dismissResetDialog()
                }) {
                    Text(stringResource(R.string.recovery_reset_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = dismissResetDialog) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showScanDialog) {
        AlertDialog(
            onDismissRequest = dismissScanDialog,
            title = { Text(stringResource(R.string.recovery_unfreeze_disabled_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.recovery_unfreeze_disabled_dialog_text)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unfreezeAllUserDisabled()
                    dismissScanDialog()
                }) {
                    Text(stringResource(R.string.recovery_unfreeze_all_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = dismissScanDialog) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}
