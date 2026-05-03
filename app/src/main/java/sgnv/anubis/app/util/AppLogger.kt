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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AppLogger {
    private const val MAX_LINES = 2000

    // Compact the on-disk file only when it grows past this threshold. Avoids the
    // pathological "rewrite every line after the 2001st" of the previous impl,
    // where every overflow message rewrote the whole file. With 2x MAX_LINES, the
    // file is rewritten roughly once per MAX_LINES new entries.
    private const val COMPACTION_THRESHOLD = MAX_LINES * 2

    private const val FILE_NAME = "journal.log"
    private const val TIME_PATTERN = "HH:mm:ss"
    private const val LEVEL_ERROR = "E"
    private const val LEVEL_INFO = "I"
    private const val LINE_SEPARATOR = "\n"
    private const val INDENT = "    "
    private const val EMPTY = ""

    private val linesLock = Any()

    // SimpleDateFormat is not thread-safe; previously a single shared instance
    // was used without locking from formatLine, which races on concurrent
    // logger calls (NetworkCallback + freeze loop + UI). One formatter per
    // thread sidesteps the race without needing a lock on the hot path.
    private val timeFormatter = ThreadLocal.withInitial {
        SimpleDateFormat(TIME_PATTERN, Locale.getDefault())
    }

    // Single-threaded executor preserves write ordering and keeps disk I/O off
    // hot paths (callbacks, UI, freeze coroutines).
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLogger-IO").apply { isDaemon = true }
    }

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Volatile
    private var logFile: File? = null

    // Approximate count of lines currently in the on-disk file. Owned by ioExecutor.
    @Volatile
    private var fileLineCount = 0

    fun init(context: Context) {
        synchronized(linesLock) {
            if (logFile != null) return
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            logFile = file
            val restored = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            _lines.value = restored.takeLast(MAX_LINES)
            fileLineCount = restored.size
            if (restored.size > COMPACTION_THRESHOLD) {
                val snapshot = restored.takeLast(MAX_LINES)
                ioExecutor.execute { compact(file, snapshot) }
            }
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
        synchronized(linesLock) {
            _lines.value = emptyList()
        }
        ioExecutor.execute {
            runCatching { logFile?.writeText(EMPTY, Charsets.UTF_8) }
            fileLineCount = 0
        }
    }

    private fun formatLine(level: String, tag: String, message: String): String {
        val time = timeFormatter.get()!!.format(Date())
        return "$time $level/$tag: $message"
    }

    private fun appendLines(newLines: List<String>) {
        if (newLines.isEmpty()) return
        val snapshotForCompaction: List<String>
        synchronized(linesLock) {
            val updated = ArrayList(_lines.value)
            updated.addAll(newLines)
            val extra = updated.size - MAX_LINES
            if (extra > 0) {
                updated.subList(0, extra).clear()
            }
            _lines.value = updated
            snapshotForCompaction = updated.toList()
        }
        ioExecutor.execute {
            val file = logFile ?: return@execute
            runCatching {
                file.appendText(
                    newLines.joinToString(separator = LINE_SEPARATOR, postfix = LINE_SEPARATOR),
                    Charsets.UTF_8
                )
            }
            fileLineCount += newLines.size
            if (fileLineCount > COMPACTION_THRESHOLD) {
                compact(file, snapshotForCompaction)
            }
        }
    }

    /** Must run on ioExecutor — assumes single-writer ordering. */
    private fun compact(file: File, snapshot: List<String>) {
        runCatching {
            file.writeText(
                snapshot.joinToString(separator = LINE_SEPARATOR, postfix = LINE_SEPARATOR),
                Charsets.UTF_8
            )
            fileLineCount = snapshot.size
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
