package com.duascontas.pokeidle

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant

data class PokeIdleNormalizationStats(
    val rawLinesRead: Int = 0,
    val rawEventsEmitted: Int = 0,
    val blankLines: Int = 0,
    val missingPayload: Int = 0,
    val invalidTimestamp: Int = 0,
    val malformedLines: Int = 0,
    val fileReadErrors: Int = 0,
) {
    fun toDiagnosticDetail(): String =
        "lines=$rawLinesRead emitted=$rawEventsEmitted blank=$blankLines " +
            "no_payload=$missingPayload bad_ts=$invalidTimestamp malformed=$malformedLines io=$fileReadErrors"
}

object PokeIdleNormalizer {
    fun forEachRecord(context: Context, sessionId: String, emit: (JSONObject) -> Unit): PokeIdleNormalizationStats =
        forEachSessionDirectory(CaptureSession.sessionDir(context, sessionId), sessionId, emit)

    internal fun forEachSessionDirectory(
        directory: File,
        sessionId: String,
        emit: (JSONObject) -> Unit,
    ): PokeIdleNormalizationStats {
        var rawLinesRead = 0
        var rawEventsEmitted = 0
        var blankLines = 0
        var missingPayload = 0
        var invalidTimestamp = 0
        var malformedLines = 0
        var fileReadErrors = 0

        for (accountId in 1..3) {
            val rawFile = File(directory, "account_$accountId.jsonl")
            if (rawFile.isFile) {
                var index = 0
                try {
                    rawFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            rawLinesRead++
                            if (line.isBlank()) {
                                blankLines++
                                return@forEach
                            }
                            try {
                                val envelope = JSONObject(line)
                                val payload = envelope.optString("payload")
                                if (payload.isBlank()) {
                                    missingPayload++
                                    return@forEach
                                }
                                val timestamp = envelope.optLong("timestamp", 0L)
                                if (timestamp <= 0L) {
                                    invalidTimestamp++
                                    return@forEach
                                }
                                val eventType = try {
                                    JSONObject(payload).optString("t").takeIf { it.isNotBlank() } ?: "unparsed"
                                } catch (_: Exception) { "unparsed" }
                                val data = JSONObject()
                                    .put("eventType", eventType)
                                    .put("payload", payload)
                                emit(record(
                                    sessionId, accountId, "raw", index++,
                                    "raw_event", "websocket", timestamp, data,
                                ))
                                rawEventsEmitted++
                            } catch (_: Exception) {
                                malformedLines++
                                index++
                            }
                        }
                    }
                } catch (_: Exception) {
                    fileReadErrors++
                }
            }
            val snapshotFile = File(File(directory, "snapshots"), "account_$accountId.json")
            val snapshot = try {
                if (snapshotFile.isFile) JSONObject(snapshotFile.readText(Charsets.UTF_8)) else null
            } catch (_: Exception) { null } ?: continue
            val observedAt = snapshot.optLong("updatedAt", 0L)
            val player = snapshot.optJSONObject("player")
            val accountName = snapshot.optString("nick").takeIf { it.isNotBlank() }

            val pokemonList = player?.optJSONArray("pokemons")
            if (pokemonList != null) {
                for (i in 0 until pokemonList.length()) {
                    val pokemon = pokemonList.optJSONObject(i) ?: continue
                    val data = JSONObject()
                    copy(pokemon, data, "id", "pokemonId")
                    if (!data.has("pokemonId")) copy(pokemon, data, "pokeId", "pokemonId")
                    copy(pokemon, data, "speciesId")
                    copy(pokemon, data, "nome", "name")
                    for (key in listOf("level", "xp", "ivs", "stats", "tipos", "tmAoe", "tmElemental",
                        "shiny", "hp", "maxHp", "potencia", "nota", "quality", "iv", "slot", "poder", "refino")) {
                        copy(pokemon, data, key)
                    }
                    if (accountName != null) data.put("accountName", accountName)
                    if (observedAt > 0L) emit(record(sessionId, accountId, "pokemon", i, "pokemon", "snapshot", observedAt, data))
                }
            }

            val gameSession = snapshot.optJSONObject("gameSession")
            val resources = JSONObject()
            if (accountName != null) resources.put("accountName", accountName)
            if (player != null) {
                copy(player, resources, "level", "trainerLevel")
                copy(player, resources, "xp", "trainerXp")
            }
            copy(snapshot, resources, "coinsBalance")
            copy(snapshot, resources, "currencySource")
            if (gameSession != null) {
                for (key in listOf("ms", "xpTreinador", "xpPokemon", "gold", "abates", "capturas",
                    "shiniesVistos", "shinies", "bossTokens", "fragChave", "fragShiny",
                    "fragBicicleta", "fragMega", "fragMegaShiny", "tmPeca", "tmPecaAoe",
                    "ticketMistico", "ticketSafari", "fragOvoShiny", "bolas", "pocoes")) {
                    copy(gameSession, resources, key)
                }
            }
            if (resources.length() > 0 && observedAt > 0L) {
                emit(record(sessionId, accountId, "resources", 0, "resources", "snapshot", observedAt, resources))
            }

            val market = snapshot.optJSONObject("market")
            val marketLines = market?.optJSONArray("linhas")
            if (marketLines != null) {
                val marketAt = market.optLong("atualizadoEm", 0L).takeIf { it > 0L } ?: observedAt
                if (marketAt <= 0L) continue
                for (i in 0 until marketLines.length()) {
                    val line = marketLines.optJSONObject(i) ?: continue
                    val ficha = line.optJSONObject("ficha") ?: line.optJSONObject("pokemon")
                    val data = JSONObject()
                    copy(line, data, "id", "offerId")
                    copy(line, data, "tipo")
                    copy(line, data, "moeda", "currency")
                    copyNumber(line, data, "preco", "price")
                    copyNumber(line, data, "bruto", "grossPrice")
                    copy(line, data, "status")
                    copy(line, data, "itemId")
                    copy(line, data, "pokemonId")
                    copy(line, data, "categoria")
                    copy(line, data, "quantidade")
                    copy(line, data, "comissao")
                    copy(line, data, "taxa")
                    copy(line, data, "liquido")
                    copy(line, data, "valorLiquido")
                    if (ficha != null) {
                        val pokemon = JSONObject()
                        for (key in listOf("id", "speciesId", "nome", "level", "ivTotal", "quality",
                            "potencia", "shiny", "tmAoe", "tmElemental", "refinoTotal", "stats", "ivs",
                            "poder", "refino", "natureza", "habilidade", "tipo1", "tipo2")) {
                            copy(ficha, pokemon, key)
                        }
                        data.put("pokemon", pokemon)
                        copy(ficha, data, "nome", "species")
                        copy(ficha, data, "speciesId")
                        copy(ficha, data, "level")
                        copy(ficha, data, "shiny")
                        copy(ficha, data, "tmAoe")
                    }
                    if (accountName != null) data.put("accountName", accountName)
                    emit(record(sessionId, accountId, "market", i, "market_offer", "snapshot", marketAt, data))
                }
            }
        }
        return PokeIdleNormalizationStats(
            rawLinesRead, rawEventsEmitted, blankLines, missingPayload,
            invalidTimestamp, malformedLines, fileReadErrors,
        )
    }

    private fun record(
        sessionId: String, accountId: Int, kind: String, index: Int,
        type: String, source: String, timestamp: Long, data: JSONObject,
    ) = JSONObject()
        .put("id", "rec_${sessionId}_${accountId}_${kind}_${index}")
        .put("accountId", accountId)
        .put("type", type)
        .put("source", source)
        .put("occurredAt", Instant.ofEpochMilli(timestamp).toString())
        .put("data", data)

    private fun copy(source: JSONObject, target: JSONObject, from: String, to: String = from) {
        if (source.has(from) && !source.isNull(from)) target.put(to, source.get(from))
    }

    private fun copyNumber(source: JSONObject, target: JSONObject, from: String, to: String) {
        if (!source.has(from) || source.isNull(from)) return
        val value = source.opt(from)
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
        if (number != null && number.isFinite()) target.put(to, number)
    }
}
