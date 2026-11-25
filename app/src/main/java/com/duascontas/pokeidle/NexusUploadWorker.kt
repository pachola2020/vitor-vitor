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

class CaptureDrainTimeoutException : Exception("Raw capture writers did not finish before upload.")

class NexusUploadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val baseUrl = NexusSyncSettings.baseUrl(applicationContext)
        val token = DeviceCredentialStore.load(applicationContext)
        if (!NexusSyncSettings.isAllowedBaseUrl(baseUrl) || token.isNullOrBlank()) {
            CaptureSession.markUploadFailed(applicationContext, sessionId, "configuration_missing")
            return Result.failure()
        }

        CaptureSession.markUploadProgress(applicationContext, sessionId, "worker_started")
        return try {
            CaptureSession.markUploadProgress(applicationContext, sessionId, "waiting_for_capture_files")
            awaitRawWriters(sessionId)
            CaptureSession.markUploadProgress(applicationContext, sessionId, "normalizing_records")
            var sentBatches = 0
            var sentRecords = 0
            val summary = NexusBatchUploader.upload(
                sessionId,
                { emit -> PokeIdleNormalizer.forEachRecord(applicationContext, sessionId, emit) },
                { batch ->
                    CaptureSession.markUploadProgress(applicationContext, sessionId, "sending_batch", sentBatches, sentRecords)
                    NexusApi.postJson(baseUrl, "/api/pokeidle/ingest", batch, token)
                    sentBatches++
                    sentRecords += batch.optJSONArray("records")?.length() ?: 0
                    CaptureSession.markUploadProgress(applicationContext, sessionId, "batch_accepted", sentBatches, sentRecords)
                },
            )
            CaptureSession.markUploadProgress(applicationContext, sessionId, "server_confirmed", summary.batches, summary.records)
            CaptureSession.markUploaded(applicationContext, sessionId, summary.records, summary.batches)
            CaptureSession.cleanupUploadedPayload(applicationContext, sessionId)
            Result.success(workDataOf("uploadedRecords" to summary.records, "batches" to summary.batches))
        } catch (_: CaptureDrainTimeoutException) {
            CaptureSession.markUploadProgress(applicationContext, sessionId, "retrying", detail = "capture_drain_timeout")
            CaptureSession.markUploadFailed(applicationContext, sessionId, "capture_drain_timeout")
            Result.retry()
        } catch (error: NexusHttpException) {
            val code = when {
                error.status == 401 -> "unauthorized"
                error.status == 403 -> "forbidden"
                error.retryable -> "http_retryable"
                else -> "http_client_error"
            }
            CaptureSession.markUploadProgress(applicationContext, sessionId, "http_error", detail = "HTTP ${error.status}")
            CaptureSession.markUploadFailed(applicationContext, sessionId, code, error.status)
            if (error.retryable) Result.retry() else Result.failure()
        } catch (_: IOException) {
            CaptureSession.markUploadProgress(applicationContext, sessionId, "network_retry", detail = "network_error")
            CaptureSession.markUploadFailed(applicationContext, sessionId, "network_error")
            Result.retry()
        } catch (_: Exception) {
            CaptureSession.markUploadProgress(applicationContext, sessionId, "unexpected_error", detail = error.javaClass.simpleName + ": " + (error.message ?: ""))
            CaptureSession.markUploadFailed(applicationContext, sessionId, "unexpected_error")
            Result.failure()
        }
    }

    private fun awaitRawWriters(sessionId: String) {
        val directory = CaptureSession.sessionDir(applicationContext, sessionId)
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < 15_000) {
            val openFiles = (1..3).filter { account ->
                File(directory, "started_account_$account").exists() &&
                    (!File(directory, "closed_account_$account").exists() ||
                        !File(directory, "closed_drops_account_$account").exists())
            }
            if (openFiles.isEmpty()) return
            SystemClock.sleep(250)
        }
        val stillOpen = (1..3).filter { account ->
            File(directory, "started_account_$account").exists() &&
                (!File(directory, "closed_account_$account").exists() ||
                    !File(directory, "closed_drops_account_$account").exists())
        }
        if (stillOpen.isNotEmpty()) throw CaptureDrainTimeoutException()
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
        CaptureSession.markUploadProgress(context, sessionId, "queued")
        WorkManager.getInstance(context).enqueueUniqueWork(
            "pokeidle-nexus-upload-$sessionId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
