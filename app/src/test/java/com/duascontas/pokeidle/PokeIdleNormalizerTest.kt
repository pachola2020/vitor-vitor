package com.duascontas.pokeidle

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PokeIdleNormalizerTest {
    @Test
    fun normalizesRawEventsPokemonResourcesAndMarketWithoutInventingFields() {
        val directory = Files.createTempDirectory("pokeidle-normalizer").toFile()
        val sessionId = "session_1234567890"
        val rawPayload = JSONObject().put("t", "estado").put("estado", JSONObject().put("level", 20)).toString()
        val rawLine = JSONObject()
            .put("timestamp", 1795521660000L)
            .put("accountId", 1)
            .put("source", "websocket")
            .put("type", "raw_event")
            .put("payload", rawPayload)
        File(directory, "account_1.jsonl").writeText(rawLine.toString() + "\n")

        val pokemon = JSONObject()
            .put("id", "pk-1").put("speciesId", 25).put("nome", "Pikachu")
            .put("level", 40).put("xp", 1234).put("tmAoe", true)
        val marketLine = JSONObject()
            .put("id", "offer-1").put("moeda", "Gold").put("preco", 1500000)
            .put("ficha", JSONObject().put("nome", "Pikachu").put("speciesId", 25)
                .put("level", 40).put("shiny", false).put("tmAoe", true))
        val snapshot = JSONObject()
            .put("updatedAt", 1795521665000L)
            .put("nick", "ContaTeste")
            .put("coinsBalance", 900000)
            .put("player", JSONObject().put("level", 60).put("xp", 8000)
                .put("pokemons", JSONArray().put(pokemon)))
            .put("gameSession", JSONObject().put("xpPokemon", 1000).put("gold", 2500)
                .put("bolas", JSONObject().put("pokeball", 12)))
            .put("market", JSONObject().put("atualizadoEm", 1795521665000L)
                .put("linhas", JSONArray().put(marketLine)))
        val snapshots = File(directory, "snapshots").apply { mkdirs() }
        File(snapshots, "account_1.json").writeText(snapshot.toString())

        val records = mutableListOf<JSONObject>()
        PokeIdleNormalizer.forEachSessionDirectory(directory, sessionId) { records.add(it) }

        assertEquals(6, records.size)
        val diagnostics = records.filter { it.optString("type") == "capture_diagnostic" }
        assertEquals(setOf(1, 2), diagnostics.map { it.getInt("accountId") }.toSet())
        val raw = records.first { it.optString("type") == "raw_event" }
        assertEquals("estado", raw.getJSONObject("data").getString("eventType"))
        assertEquals(rawPayload, raw.getJSONObject("data").getString("payload"))
        assertEquals(1, raw.getInt("accountId"))

        val normalizedPokemon = records.first { it.optString("type") == "pokemon" }.getJSONObject("data")
        assertEquals("pk-1", normalizedPokemon.getString("pokemonId"))
        assertEquals("Pikachu", normalizedPokemon.getString("name"))
        assertFalse(normalizedPokemon.has("ivs"))
        assertFalse(normalizedPokemon.has("stats"))

        val resources = records.first { it.optString("type") == "resources" }.getJSONObject("data")
        assertEquals(2500, resources.getInt("gold"))
        assertEquals(900000, resources.getInt("coinsBalance"))
        assertEquals(12, resources.getJSONObject("bolas").getInt("pokeball"))

        val market = records.first { it.optString("type") == "market_offer" }.getJSONObject("data")
        assertEquals("offer-1", market.getString("offerId"))
        assertEquals(1500000.0, market.getDouble("price"), 0.0)
        assertEquals("Pikachu", market.getString("species"))
        assertEquals(true, market.getBoolean("tmAoe"))
    }

    @Test
    fun reportsEveryRawLineThatWasNotNormalized() {
        val directory = Files.createTempDirectory("pokeidle-normalizer-diagnostics").toFile()
        try {
            val validPayload = JSONObject().put("t", "estado").toString()
            val validLine = JSONObject().put("timestamp", 1795521660000L)
                .put("payload", validPayload).toString()
            val missingPayloadLine = JSONObject().put("timestamp", 1795521660000L).toString()
            val invalidTimestampLine = JSONObject().put("timestamp", 0L)
                .put("payload", validPayload).toString()
            File(directory, "account_1.jsonl").writeText(
                listOf(validLine, "", missingPayloadLine, invalidTimestampLine, "{malformed")
                    .joinToString("\n"),
            )

            val emitted = mutableListOf<JSONObject>()
            val stats = PokeIdleNormalizer.forEachSessionDirectory(directory, "session_1234567890") {
                emitted.add(it)
            }

            assertEquals(5, stats.rawLinesRead)
            assertEquals(1, stats.rawEventsEmitted)
            assertEquals(1, stats.blankLines)
            assertEquals(1, stats.missingPayload)
            assertEquals(1, stats.invalidTimestamp)
            assertEquals(1, stats.malformedLines)
            assertEquals(0, stats.fileReadErrors)
            val rawRecords = emitted.filter { it.optString("type") == "raw_event" }
            assertEquals(1, rawRecords.size)
            assertEquals("raw_event", rawRecords.single().getString("type"))
            assertEquals(2, emitted.count { it.optString("type") == "capture_diagnostic" })
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun recognizesOnlyWellFormedSnapshotDrainBarriersForTheMatchingAccount() {
        val sessionId = "session_1234567890"
        val valid = JSONObject()
            .put("_captureBarrier", true)
            .put("accountId", 2)
            .put("sessionId", sessionId)
            .toString()

        assertEquals(sessionId, GameDataCapture.snapshotDrainBarrierSessionId(valid, 2))
        assertNull(GameDataCapture.snapshotDrainBarrierSessionId(valid, 1))
        val rawBarrier = JSONObject()
            .put("kind", "__POKEIDLE_STOP_BARRIER__")
            .put("accountId", 2)
            .put("sessionId", sessionId)
            .toString()
        assertEquals(sessionId, GameDataCapture.rawStopBarrierSessionId(rawBarrier, 2))
        assertNull(GameDataCapture.rawStopBarrierSessionId(rawBarrier, 1))
        assertNull(GameDataCapture.snapshotDrainBarrierSessionId(
            JSONObject().put("_captureBarrier", true).put("accountId", 2)
                .put("sessionId", "not-a-session").toString(),
            2,
        ))
        assertNull(GameDataCapture.snapshotDrainBarrierSessionId(
            JSONObject().put("kind", "state").put("level", 20).toString(),
            2,
        ))
        assertNull(GameDataCapture.snapshotDrainBarrierSessionId("{malformed", 2))
        assertNull(GameDataCapture.rawStopBarrierSessionId("{malformed", 2))
    }

}
