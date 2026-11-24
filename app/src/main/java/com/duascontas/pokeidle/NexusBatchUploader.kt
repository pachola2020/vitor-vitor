package com.duascontas.pokeidle

import org.json.JSONArray
import org.json.JSONObject

data class NexusUploadSummary(val records: Int, val batches: Int)

object NexusBatchUploader {
    private const val MAX_RECORDS = 100
    private const val MAX_BATCH_BYTES = 2_500_000

    fun upload(
        sessionId: String,
        emitRecords: (emit: (JSONObject) -> Unit) -> Unit,
        sendBatch: (JSONObject) -> Unit,
    ): NexusUploadSummary {
        var batchIndex = 0
        var totalRecords = 0
        val pending = ArrayList<JSONObject>()

        fun flush() {
            if (pending.isEmpty()) return
            val body = JSONObject()
                .put("schemaVersion", 1)
                .put("batchId", "batch_${sessionId}_${batchIndex}")
                .put("sessionId", sessionId)
                .put("records", JSONArray(pending))
            sendBatch(body)
            totalRecords += pending.size
            batchIndex++
            pending.clear()
        }

        var pendingBytes = 0
        emitRecords { record ->
            val size = record.toString().toByteArray(Charsets.UTF_8).size
            if (pending.isNotEmpty() && (pending.size >= MAX_RECORDS || pendingBytes + size > MAX_BATCH_BYTES)) {
                flush()
                pendingBytes = 0
            }
            pending.add(record)
            pendingBytes += size
        }
        flush()
        return NexusUploadSummary(totalRecords, batchIndex)
    }
}
