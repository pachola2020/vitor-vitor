package com.duascontas.pokeidle

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NexusBatchUploaderTest {
    private fun records(count: Int) = (0 until count).map { index ->
        JSONObject().put("id", "record_$index").put("accountId", 1)
            .put("type", "raw_event").put("source", "websocket")
            .put("occurredAt", "2026-11-24T12:00:00Z")
            .put("data", JSONObject().put("eventType", "estado").put("payload", "{\"t\":\"estado\"}"))
    }

    @Test
    fun splitsBatchesAndKeepsDeterministicIdsForRetries() {
        val first = mutableListOf<JSONObject>()
        val second = mutableListOf<JSONObject>()
        val input = records(205)
        val emit: ((JSONObject) -> Unit) -> Unit = { callback -> input.forEach(callback) }

        val resultA = NexusBatchUploader.upload("session_1234567890", emit) { first.add(JSONObject(it.toString())) }
        val resultB = NexusBatchUploader.upload("session_1234567890", emit) { second.add(JSONObject(it.toString())) }

        assertEquals(205, resultA.records)
        assertEquals(3, resultA.batches)
        assertEquals(resultA, resultB)
        assertEquals(first.map { it.toString() }, second.map { it.toString() })
        assertEquals("batch_session_1234567890_0", first[0].getString("batchId"))
        assertEquals(100, first[0].getJSONArray("records").length())
        assertEquals(100, first[1].getJSONArray("records").length())
        assertEquals(5, first[2].getJSONArray("records").length())
    }

    @Test
    fun propagatesTransportFailureSoWorkManagerCanRetry() {
        val input = records(1)
        try {
            NexusBatchUploader.upload("session_1234567890", { emit -> input.forEach(emit) }) {
                throw IllegalStateException("mock network failure")
            }
            fail("expected transport exception")
        } catch (error: IllegalStateException) {
            assertEquals("mock network failure", error.message)
        }
    }
}
