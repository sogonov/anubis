package sgnv.anubis.app.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Доступно обновление: v${info.latestVersion}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Текущая версия: v${info.currentVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (info.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        info.releaseNotes,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = info.apkUrl ?: info.releaseUrl
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                onDismiss()
            }) { Text(if (info.apkUrl != null) "Скачать APK" else "Открыть релиз") }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Пропустить") }
        },
    )
}
