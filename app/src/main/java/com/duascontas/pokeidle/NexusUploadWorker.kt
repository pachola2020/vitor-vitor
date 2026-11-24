package com.duascontas.pokeidle

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class NexusUploadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val baseUrl = NexusSyncSettings.baseUrl(applicationContext)
        val token = DeviceCredentialStore.load(applicationContext)
        if (!baseUrl.startsWith("https://") || token.isNullOrBlank()) return Result.failure()

        return try {
            awaitRawWriters(sessionId)
            var batchIndex = 0
            var totalRecords = 0
            val pending = ArrayList<JSONObject>()
            var pendingBytes = 0

            fun uploadPending() {
                if (pending.isEmpty()) return
                val body = JSONObject()
                    .put("schemaVersion", 1)
                    .put("batchId", "batch_${sessionId}_${batchIndex}")
                    .put("sessionId", sessionId)
                    .put("records", JSONArray(pending))
                NexusApi.postJson(baseUrl, "/api/pokeidle/ingest", body, token)
                totalRecords += pending.size
                batchIndex++
                pending.clear()
                pendingBytes = 0
            }

            PokeIdleNormalizer.forEachRecord(applicationContext, sessionId) { record ->
                val size = record.toString().toByteArray(Charsets.UTF_8).size
                if (pending.isNotEmpty() && (pending.size >= 100 || pendingBytes + size > 2_500_000)) {
                    uploadPending()
                }
                pending.add(record)
                pendingBytes += size
            }
            uploadPending()
            Result.success(workDataOf("uploadedRecords" to totalRecords, "batches" to batchIndex))
        } catch (error: NexusHttpException) {
            if (error.retryable) Result.retry() else Result.failure()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }

    private fun awaitRawWriters(sessionId: String) {
        val directory = CaptureSession.sessionDir(applicationContext, sessionId)
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < 15_000) {
            val openFiles = (1..3).filter { account ->
                File(directory, "account_$account.jsonl").isFile &&
                    !File(directory, "closed_account_$account").exists()
            }
            if (openFiles.isEmpty()) return
            SystemClock.sleep(250)
        }
    }

    companion object {
        const val KEY_SESSION_ID = "sessionId"
    }
}

object NexusSyncScheduler {
    fun enqueue(context: Context, sessionId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<NexusUploadWorker>()
            .setInputData(workDataOf(NexusUploadWorker.KEY_SESSION_ID to sessionId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "pokeidle-nexus-upload-$sessionId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
