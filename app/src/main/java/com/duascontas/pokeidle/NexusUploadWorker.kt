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
            val summary = NexusBatchUploader.upload(
                sessionId,
                { emit -> PokeIdleNormalizer.forEachRecord(applicationContext, sessionId, emit) },
                { batch -> NexusApi.postJson(baseUrl, "/api/pokeidle/ingest", batch, token) },
            )
            CaptureSession.markUploaded(applicationContext, sessionId, summary.records, summary.batches)
            CaptureSession.cleanupUploadedPayload(applicationContext, sessionId)
            Result.success(workDataOf("uploadedRecords" to summary.records, "batches" to summary.batches))
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
