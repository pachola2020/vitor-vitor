package com.duascontas.pokeidle

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object NexusSyncSettings {
    private const val PREFS = "nexus_sync_settings"
    private const val BASE_URL = "base_url"

    fun baseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BASE_URL, "").orEmpty()

    fun saveBaseUrl(context: Context, value: String): Boolean {
        val normalized = value.trim().trimEnd('/')
        return try {
            val url = URL(normalized)
            if (url.protocol != "https" || url.host.isBlank() || url.userInfo != null ||
                url.query != null || url.ref != null || (url.path.isNotBlank() && url.path != "/")) {
                false
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(BASE_URL, url.protocol + "://" + url.authority).apply()
                true
            }
        } catch (_: Exception) { false }
    }
}

object DeviceCredentialStore {
    private const val ALIAS = "pokeidle_nexus_device_aes_v1"
    private fun tokenFile(context: Context) = AtomicFile(File(context.filesDir, "nexus_device_credential.bin"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @Synchronized
    fun save(context: Context, token: String) {
        require(token.matches(Regex("^[A-Za-z0-9_-]{40,128}$")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        val bytes = cipher.iv + encrypted
        val target = tokenFile(context)
        var stream: FileOutputStream? = null
        try {
            stream = target.startWrite()
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) target.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun load(context: Context): String? {
        val bytes = try { tokenFile(context).openRead().use { it.readBytes() } } catch (_: Exception) { return null }
        if (bytes.size <= 12) return null
        return try {
            val iv = bytes.copyOfRange(0, 12)
            val encrypted = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (_: Exception) { null }
    }

    @Synchronized
    fun clear(context: Context) {
        try { tokenFile(context).baseFile.delete() } catch (_: Exception) { }
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        } catch (_: Exception) { }
    }
}

class NexusHttpException(val status: Int, message: String) : Exception(message) {
    val retryable: Boolean get() = status == 408 || status == 425 || status == 429 || status >= 500
}

object NexusApi {
    fun postJson(baseUrl: String, path: String, body: JSONObject, bearerToken: String? = null): JSONObject {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (!bearerToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearerToken")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw NexusHttpException(status, responseText.take(300))
            return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    fun pair(context: Context, pairingCode: String): String {
        val baseUrl = NexusSyncSettings.baseUrl(context)
        require(baseUrl.startsWith("https://")) { "Configure primeiro o endereço HTTPS do Nexus." }
        val body = JSONObject()
            .put("pairingCode", pairingCode.trim())
            .put("deviceName", "PokeIdle Android " + Build.MODEL.take(40))
        val response = postJson(baseUrl, "/api/pokeidle/pair", body)
        val token = response.optString("deviceToken")
        require(token.matches(Regex("^[A-Za-z0-9_-]{40,128}$"))) { "Resposta de pareamento inválida." }
        DeviceCredentialStore.save(context, token)
        return response.optString("deviceId")
    }
}
