package io.talkcan.service

import android.util.Log as AndroidLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

enum class LogLevel(val androidLevel: Int, val label: String) {
    Verbose(android.util.Log.VERBOSE, "V"),
    Debug(android.util.Log.DEBUG, "D"),
    Info(android.util.Log.INFO, "I"),
    Warn(android.util.Log.WARN, "W"),
    Error(android.util.Log.ERROR, "E");

    companion object {
        fun fromAndroidLevel(level: Int): LogLevel =
            entries.firstOrNull { it.androidLevel == level } ?: Debug

        fun fromLabel(label: String): LogLevel =
            entries.firstOrNull { it.label == label } ?: Debug
    }
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
)

private const val MAX_FILE_SIZE = 1_048_576L // 1 MB per file → 2 MB total
private const val MAX_IN_MEMORY = 2000

private val LOG_DATE_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

object TalkcanLogger {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<LogEntry>(capacity = Channel.UNLIMITED)
    private val pendingPluginLogCount = AtomicInteger(0)
    private const val MAX_PENDING_PLUGIN_LOGS = 500
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _globalLevel = MutableStateFlow(LogLevel.Debug)
    val globalLevelFlow: StateFlow<LogLevel> = _globalLevel.asStateFlow()
    val globalLevel: LogLevel get() = _globalLevel.value

    private val _perTagLevel = MutableStateFlow<Map<String, LogLevel>>(emptyMap())
    val perTagLevelFlow: StateFlow<Map<String, LogLevel>> = _perTagLevel.asStateFlow()

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var activeFile: File? = null

    @Volatile
    private var previousFile: File? = null

    @Volatile
    private var activeFileSize: Long = 0L

    private var dispatcherJob: Job? = null

    fun initialize(cacheDir: File) {
        val dir = File(cacheDir, "talkcan-logs").apply { mkdirs() }
        logDir = dir
        activeFile = File(dir, "talkcan_logs.0.log")
        previousFile = File(dir, "talkcan_logs.1.log")
        activeFileSize = activeFile?.length() ?: 0L

        loadHistoricalLogs()

        if (dispatcherJob?.isActive != true) {
            dispatcherJob = scope.launch { dispatchLoop() }
        }
    }

    private fun loadHistoricalLogs() {
        val dir = logDir ?: return
        val loaded = mutableListOf<LogEntry>()

        // Load oldest first: previous file then active file
        for (fileName in listOf("talkcan_logs.1.log", "talkcan_logs.0.log")) {
            val file = File(dir, fileName)
            if (!file.exists()) continue
            try {
                file.forEachLine { line ->
                    parseLogLine(line)?.let { loaded.add(it) }
                }
            } catch (_: Exception) {
                // Corrupted file — skip
            }
        }

        if (loaded.isNotEmpty()) {
            val trimmed = if (loaded.size > MAX_IN_MEMORY) loaded.takeLast(MAX_IN_MEMORY) else loaded
            _entries.value = trimmed
        }
    }

    private fun parseLogLine(line: String): LogEntry? {
        return try {
            val json = JSONObject(line)
            val level = LogLevel.fromLabel(json.optString("level"))
            val ts = json.optLong("ts", 0L)
            if (ts == 0L) return null
            val tag = json.optString("tag")
            val message = json.optString("msg")
            val stackTrace = json.optString("throwable", "").ifEmpty { null }
            val throwable = stackTrace?.let { ThrowableWithStack(it) }
            LogEntry(ts, level, tag, message, throwable)
        } catch (_: Exception) {
            null
        }
    }

    private fun serializeEntry(entry: LogEntry): String {
        val json = JSONObject()
        json.put("level", entry.level.label)
        json.put("tag", entry.tag)
        json.put("ts", entry.timestamp)
        json.put("msg", entry.message)
        entry.throwable?.let { throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            json.put("throwable", sw.toString())
        }
        return json.toString()
    }

    private suspend fun dispatchLoop() {
        for (entry in channel) {
            if (entry.tag == "LuaChannel") {
                pendingPluginLogCount.decrementAndGet()
            }
            try {
                appendToDisk(entry)
            } catch (_: Exception) {
                // Disk write failure — don't crash the app
            }
            _entries.update { current ->
                val combined = current + entry
                if (combined.size > MAX_IN_MEMORY) combined.takeLast(MAX_IN_MEMORY) else combined
            }
        }
    }

    private fun appendToDisk(entry: LogEntry) {
        val file = activeFile ?: return
        val line = serializeEntry(entry) + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)

        if (activeFileSize + bytes.size > MAX_FILE_SIZE) {
            rotateFiles()
        }

        val target = activeFile ?: return
        try {
            target.appendBytes(bytes)
            activeFileSize += bytes.size
        } catch (_: Exception) {
            // Ignore write errors
        }
    }

    private fun rotateFiles() {
        val dir = logDir ?: return
        val current = activeFile ?: return

        // Delete old previous file, rotate current → previous
        try {
            previousFile?.delete()
        } catch (_: Exception) {
            // Ignore
        }

        try {
            current.renameTo(File(dir, "talkcan_logs.1.log"))
        } catch (_: Exception) {
            // Ignore
        }

        activeFile = File(dir, "talkcan_logs.0.log").apply { createNewFile() }
        previousFile = File(dir, "talkcan_logs.1.log")
        activeFileSize = 0L
    }

    fun setGlobalLevel(level: LogLevel) {
        _globalLevel.value = level
    }

    fun setTagLevel(tag: String, level: LogLevel) {
        _perTagLevel.value = _perTagLevel.value + (tag to level)
    }

    fun clearTagLevel(tag: String) {
        _perTagLevel.value = _perTagLevel.value - tag
    }

    fun clearAllTagLevels() {
        _perTagLevel.value = emptyMap()
    }

    fun tagLevels(): Map<String, LogLevel> = _perTagLevel.value.toMap()

    fun globalLevel(): LogLevel = _globalLevel.value

    private fun shouldLog(tag: String, level: LogLevel): Boolean {
        val tagThreshold = _perTagLevel.value[tag]
        val threshold = tagThreshold ?: _globalLevel.value
        return level.ordinal >= threshold.ordinal
    }

    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (!shouldLog(tag, level)) return

        // Mirror to Android Logcat
        val fullMessage = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            "$message\n$sw"
        } else {
            message
        }
        AndroidLog.println(level.androidLevel, tag, fullMessage)

        // Push to disk channel (non-blocking)
        val entry = LogEntry(
            timestamp = timestamp,
            level = level,
            tag = tag,
            message = message,
            throwable = throwable,
        )
        channel.trySend(entry)
    }

    fun tryLogPlugin(
        level: LogLevel,
        tag: String,
        message: String,
        timestamp: Long
    ): Boolean {
        if (!shouldLog(tag, level)) return false

        while (true) {
            val current = pendingPluginLogCount.get()
            if (current >= MAX_PENDING_PLUGIN_LOGS) {
                return false
            }
            if (pendingPluginLogCount.compareAndSet(current, current + 1)) {
                break
            }
        }

        // Mirror to Android Logcat
        AndroidLog.println(level.androidLevel, tag, message)

        // Push to disk channel (non-blocking)
        val entry = LogEntry(
            timestamp = timestamp,
            level = level,
            tag = tag,
            message = message,
            throwable = null,
        )
        val sent = channel.trySend(entry).isSuccess
        if (!sent) {
            pendingPluginLogCount.decrementAndGet()
        }
        return sent
    }

    fun d(tag: String, message: String) = log(LogLevel.Debug, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.Info, tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.Warn, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.Error, tag, message, throwable)
    fun v(tag: String, message: String) = log(LogLevel.Verbose, tag, message)

    fun clear() {
        val dir = logDir ?: return
        _entries.value = emptyList()
        try {
            activeFile?.delete()
            previousFile?.delete()
            activeFile = File(dir, "talkcan_logs.0.log").apply { createNewFile() }
            previousFile = File(dir, "talkcan_logs.1.log")
            activeFileSize = 0L
        } catch (_: Exception) {
            // Ignore
        }
    }

    fun formatTimestamp(ts: Long): String {
        return LOG_DATE_FORMAT.format(Date(ts))
    }
}

/**
 * Throwable that carries a pre-serialized stack trace for round-tripping
 * through disk persistence. Overrides [printStackTrace] so the original
 * trace is output, not this synthetic Throwable's own call stack.
 */
private class ThrowableWithStack(private val stackTraceText: String) : Throwable(stackTraceText) {
    override fun printStackTrace(writer: java.io.PrintWriter) {
        writer.print(stackTraceText)
    }
    override fun printStackTrace(stream: java.io.PrintStream) {
        stream.print(stackTraceText)
    }
}