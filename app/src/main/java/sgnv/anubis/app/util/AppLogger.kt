package sgnv.anubis.app.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val MAX_LINES = 2000
    private const val FILE_NAME = "journal.log"
    private const val TIME_PATTERN = "HH:mm:ss"
    private const val LEVEL_ERROR = "E"
    private const val LEVEL_INFO = "I"
    private const val LINE_SEPARATOR = "\n"
    private const val INDENT = "    "
    private const val EMPTY = ""

    private val lock = Any()
    private val timeFormatter = SimpleDateFormat(TIME_PATTERN, Locale.getDefault())
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            logFile = file
            val restored = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            _lines.value = restored.takeLast(MAX_LINES)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        runCatching { Log.e(tag, message, throwable) }
        val base = formatLine(level = LEVEL_ERROR, tag = tag, message = message)
        val stackLines = throwable
            ?.toSafeStackTraceString()
            ?.lineSequence()
            ?.map { "$INDENT$it" }
            ?.toList()
            .orEmpty()
        appendLines(listOf(base) + stackLines)
    }

    fun i(tag: String, message: String) {
        runCatching { Log.i(tag, message) }
        appendLines(listOf(formatLine(level = LEVEL_INFO, tag = tag, message = message)))
    }

    fun clear() {
        synchronized(lock) {
            _lines.value = emptyList()
            runCatching { logFile?.writeText(EMPTY, Charsets.UTF_8) }
        }
    }

    private fun formatLine(level: String, tag: String, message: String): String {
        val time = timeFormatter.format(Date())
        return "$time $level/$tag: $message"
    }

    private fun appendLines(newLines: List<String>) {
        if (newLines.isEmpty()) return
        synchronized(lock) {
            val updated = ArrayList(_lines.value)
            updated.addAll(newLines)
            val extra = updated.size - MAX_LINES
            if (extra > 0) {
                updated.subList(0, extra).clear()
            }
            _lines.value = updated

            val file = logFile
            if (file != null) {
                runCatching {
                    file.appendText(
                        newLines.joinToString(separator = LINE_SEPARATOR, postfix = LINE_SEPARATOR),
                        Charsets.UTF_8
                    )
                }
                if (extra > 0) {
                    runCatching {
                        file.writeText(
                            updated.joinToString(separator = LINE_SEPARATOR, postfix = LINE_SEPARATOR),
                            Charsets.UTF_8
                        )
                    }
                }
            }
        }
    }

    private fun Throwable.toSafeStackTraceString(): String {
        return runCatching {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            printStackTrace(pw)
            pw.flush()
            sw.toString()
        }.getOrDefault("${this::class.java.name}: ${message.orEmpty()}")
    }
}
