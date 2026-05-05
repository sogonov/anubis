package sgnv.anubis.app.ui.screens

import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.EditText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sgnv.anubis.app.R
import sgnv.anubis.app.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CONTENT_PADDING = 16.dp
private val BACK_ICON_SPACING = 4.dp
private val HEADER_BOTTOM_SPACING = 12.dp
private const val LOG_TEXT_SIZE_SP = 12f
private const val NO_PADDING = 0
private const val NEW_LINE = "\n"
private const val JOURNAL_SHARE_DIR = "journal-share"

@Composable
fun LogScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lines by AppLogger.lines.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    val dismissClearDialog: () -> Unit = { showClearDialog = false }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(CONTENT_PADDING)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(BACK_ICON_SPACING))
                Text(stringResource(R.string.log_screen_back))
            }
            Text(
                stringResource(R.string.log_screen_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row {
                val shareSubject = stringResource(R.string.log_screen_share_subject)
                val shareChooser = stringResource(R.string.log_screen_share_chooser)
                TextButton(
                    onClick = {
                        val snapshot = lines.joinToString(separator = NEW_LINE, postfix = NEW_LINE)
                        coroutineScope.launch {
                            val file = withContext(Dispatchers.IO) {
                                val dir = File(context.cacheDir, JOURNAL_SHARE_DIR).apply { mkdirs() }
                                val stamp = SimpleDateFormat(
                                    "yyyyMMdd-HHmmss",
                                    Locale.US
                                ).format(Date())
                                File(dir, "anubis-journal-$stamp.log")
                                    .also { it.writeText(snapshot, Charsets.UTF_8) }
                            }
                            val authority = "${context.packageName}.fileprovider"
                            val uri = FileProvider.getUriForFile(context, authority, file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, shareChooser))
                        }
                    },
                    enabled = lines.isNotEmpty()
                ) {
                    Text(stringResource(R.string.log_screen_share))
                }
                TextButton(onClick = { showClearDialog = true }) {
                    Text(stringResource(R.string.log_screen_clear))
                }
            }
        }

        Spacer(Modifier.height(HEADER_BOTTOM_SPACING))

        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.log_screen_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val text = lines.joinToString(separator = NEW_LINE)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    EditText(context).apply {
                        isSingleLine = false
                        maxLines = Int.MAX_VALUE
                        typeface = Typeface.MONOSPACE
                        textSize = LOG_TEXT_SIZE_SP
                        setTextIsSelectable(true)
                        isLongClickable = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        showSoftInputOnFocus = false
                        setPadding(NO_PADDING, NO_PADDING, NO_PADDING, NO_PADDING)
                        gravity = Gravity.TOP or Gravity.START
                        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        background = null
                        keyListener = null
                    }
                },
                update = { editText ->
                    if (editText.text.toString() != text) {
                        editText.setText(text)
                        editText.setSelection(editText.text.length)
                    }
                }
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = dismissClearDialog,
            title = { Text(stringResource(R.string.log_screen_clear_confirm_title)) },
            text = { Text(stringResource(R.string.log_screen_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppLogger.clear()
                        dismissClearDialog()
                    }
                ) {
                    Text(stringResource(R.string.log_screen_clear_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = dismissClearDialog) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
