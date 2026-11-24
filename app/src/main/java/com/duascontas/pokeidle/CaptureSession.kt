package com.duascontas.pokeidle

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object CaptureSession {
    private fun stateFile(context: Context) = AtomicFile(File(context.filesDir, "pokeidle_capture_session.json"))

    fun current(context: Context): JSONObject? = try {
        stateFile(context).openRead().use { JSONObject(String(it.readBytes(), StandardCharsets.UTF_8)) }
    } catch (_: Exception) { null }

    @Synchronized
    fun start(context: Context): JSONObject {
        val now = System.currentTimeMillis()
        val state = JSONObject()
            .put("sessionId", "session_" + UUID.randomUUID().toString().replace("-", ""))
            .put("startedAt", now)
            .put("active", true)
        writeAtomic(stateFile(context), state.toString())
        File(sessionDir(context, state.getString("sessionId")), "snapshots").mkdirs()
        return state
    }

    @Synchronized
    fun stop(context: Context): JSONObject? {
        val state = current(context) ?: return null
        if (!state.optBoolean("active", false)) return null
        state.put("active", false).put("endedAt", System.currentTimeMillis())
        writeAtomic(stateFile(context), state.toString())
        return state
    }

    fun sessionDir(context: Context, sessionId: String): File {
        require(Regex("^session_[A-Za-z0-9_-]{8,80}$").matches(sessionId))
        return File(File(context.filesDir, "pokeidle_capture_sessions"), sessionId)
    }

    fun saveSnapshot(context: Context, sessionId: String, accountId: Int, snapshot: JSONObject?) {
        if (accountId !in 1..3 || snapshot == null) return
        val dir = File(sessionDir(context, sessionId), "snapshots")
        if (!dir.exists() && !dir.mkdirs()) return
        writeAtomic(AtomicFile(File(dir, "account_$accountId.json")), snapshot.toString())
    }

    fun markUploaded(context: Context, sessionId: String, records: Int, batches: Int) {
        val dir = sessionDir(context, sessionId)
        if (!dir.exists()) dir.mkdirs()
        writeAtomic(
            AtomicFile(File(dir, "uploaded.json")),
            JSONObject().put("uploadedAt", System.currentTimeMillis())
                .put("records", records).put("batches", batches).toString(),
        )
    }

    fun isUploaded(context: Context, sessionId: String): Boolean =
        File(sessionDir(context, sessionId), "uploaded.json").isFile

    fun cleanupUploadedPayload(context: Context, sessionId: String) {
        val dir = sessionDir(context, sessionId)
        for (account in 1..3) File(dir, "account_$account.jsonl").delete()
        File(dir, "snapshots").deleteRecursively()
    }

    fun stats(context: Context, sessionId: String): CaptureStats {
        var events = 0L
        var dropped = 0L
        var bytes = 0L
        for (account in 1..3) {
            val file = File(sessionDir(context, sessionId), "stats_account_$account.json")
            try {
                val value = AtomicFile(file).openRead().use { JSONObject(String(it.readBytes(), StandardCharsets.UTF_8)) }
                events += value.optLong("events", 0L)
                dropped += value.optLong("dropped", 0L)
                bytes += value.optLong("bytes", 0L)
            } catch (_: Exception) { }
        }
        return CaptureStats(events, dropped, bytes)
    }

    internal fun writeAtomic(target: AtomicFile, value: String) {
        var stream: FileOutputStream? = null
        try {
            stream = target.startWrite()
            stream.write(value.toByteArray(StandardCharsets.UTF_8))
            target.finishWrite(stream)
        } catch (_: Exception) {
            if (stream != null) target.failWrite(stream)
        }
    }
}

data class CaptureStats(val events: Long, val dropped: Long, val bytes: Long)

object RawEventRecorder {
    private val writers = ConcurrentHashMap<Int, ThreadPoolExecutor>()
    private val dropWriters = ConcurrentHashMap<Int, ExecutorService>()

    private fun dropWriter(accountId: Int) = dropWriters.getOrPut(accountId) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "RawCaptureDrops-$accountId").apply { isDaemon = true } }
    }

    private fun writer(accountId: Int) = writers.getOrPut(accountId) {
        ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(64),
            { task -> Thread(task, "RawCapture-$accountId").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    fun append(context: Context, accountId: Int, sessionId: String, payload: String) {
        if (accountId !in 1..3 || payload.isEmpty()) return
        val app = context.applicationContext
        try {
            writer(accountId).execute {
                try {
                    val dir = CaptureSession.sessionDir(app, sessionId)
                    if (!dir.exists() && !dir.mkdirs()) {
                        updateStats(app, sessionId, accountId, dropped = 1)
                        return@execute
                    }
                    val file = File(dir, "account_$accountId.jsonl")
                    val record = JSONObject()
                        .put("timestamp", System.currentTimeMillis())
                        .put("accountId", accountId)
                        .put("source", "websocket")
                        .put("type", "raw_event")
                        .put("eventType", "unparsed")
                        .put("payload", payload)
                    val bytes = (record.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
                    if (bytes.size > 3_500_000) {
                        updateStats(app, sessionId, accountId, dropped = 1)
                        return@execute
                    }
                    FileOutputStream(file, true).use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                    updateStats(app, sessionId, accountId, events = 1, bytes = bytes.size.toLong())
                } catch (_: Exception) {
                    updateStats(app, sessionId, accountId, dropped = 1)
                }
            }
        } catch (_: RejectedExecutionException) {
            recordDrop(app, accountId, sessionId)
        }
    }

    fun recordDrop(context: Context, accountId: Int, sessionId: String?) {
        if (accountId !in 1..3 || sessionId.isNullOrBlank()) return
        val app = context.applicationContext
        try {
            dropWriter(accountId).execute {
                updateStats(app, sessionId, accountId, dropped = 1)
            }
        } catch (_: RejectedExecutionException) { }
    }

    fun closeSession(context: Context, accountId: Int, sessionId: String) {
        if (accountId !in 1..3) return
        val app = context.applicationContext
        try {
            writer(accountId).execute {
                try {
                    val dir = CaptureSession.sessionDir(app, sessionId)
                    if (!dir.exists()) dir.mkdirs()
                    File(dir, "closed_account_$accountId").writeText(System.currentTimeMillis().toString())
                } catch (_: Exception) { }
            }
        } catch (_: RejectedExecutionException) { }
    }

    private fun updateStats(
        context: Context, sessionId: String, accountId: Int,
        events: Long = 0, dropped: Long = 0, bytes: Long = 0,
    ) {
        try {
            val file = File(CaptureSession.sessionDir(context, sessionId), "stats_account_$accountId.json")
            val target = AtomicFile(file)
            val stats = try {
                target.openRead().use { JSONObject(String(it.readBytes(), StandardCharsets.UTF_8)) }
            } catch (_: Exception) { JSONObject() }
            stats.put("events", stats.optLong("events", 0L) + events)
            stats.put("dropped", stats.optLong("dropped", 0L) + dropped)
            stats.put("bytes", stats.optLong("bytes", 0L) + bytes)
            CaptureSession.writeAtomic(target, stats.toString())
        } catch (_: Exception) { }
    }
}
