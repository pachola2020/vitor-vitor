package com.duascontas.pokeidle

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Atualizador manual e verificável. Nunca instala silenciosamente.
 * Confere o SHA-256 publicado pelo GitHub antes de abrir o instalador Android.
 */
object AppUpdater {
    private const val API = "https://api.github.com/repos/pachola2020/vitor-vitor/releases?per_page=20"
    private const val APK_NAME = "PokeIdle-Assistant.apk"
    private const val PREFS = "assistant_updater"
    private const val KEY_DOWNLOAD = "download_id"
    private const val KEY_PENDING_URI = "pending_install_uri"
    private const val KEY_DIGEST = "pending_digest"
    private const val KEY_PERMISSION_REQUESTED = "install_permission_requested"
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recoveryRunning = false

    data class UpdateInfo(val versionName: String, val downloadUrl: String, val sha256: String)

    fun check(
        activity: Activity,
        onResult: (UpdateInfo?) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val connection = (URL(API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "PokeIdle-Assistant-Updater")
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val body = try {
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("GitHub respondeu HTTP ${connection.responseCode}")
                    }
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
                val releases = JSONArray(body)
                var newest: UpdateInfo? = null
                for (i in 0 until releases.length()) {
                    val release = releases.getJSONObject(i)
                    val tag = release.optString("tag_name")
                    if (!release.optBoolean("prerelease") || !tag.startsWith("assistant-v")) continue
                    val assets = release.optJSONArray("assets") ?: continue
                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        if (asset.optString("name") == APK_NAME) {
                            val digest = asset.optString("digest")
                            if (!digest.startsWith("sha256:")) continue
                            newest = UpdateInfo(
                                tag.removePrefix("assistant-v"),
                                asset.getString("browser_download_url"),
                                digest.removePrefix("sha256:").lowercase()
                            )
                            break
                        }
                    }
                    if (newest != null) break
                }
                val available = newest?.let { info ->
                    val localName = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"
                    if (versionCodeFromName(info.versionName) > versionCodeFromName(localName)) info else null
                }
                main.post { if (!activity.isFinishing) onResult(available) }
            } catch (e: Exception) {
                main.post { if (!activity.isFinishing) onError(e.message ?: "erro desconhecido") }
            }
        }
    }

    private fun versionCodeFromName(name: String): Long =
        name.substringBefore("-").split(".").fold(0L) { acc, part ->
            acc * 1000 + (part.toLongOrNull() ?: 0L)
        }

    fun downloadAndInstall(activity: Activity, update: UpdateInfo) {
        var receiver: BroadcastReceiver? = null
        try {
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val id = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD, -1L)
                    if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id || id < 0) return
                    try { activity.unregisterReceiver(this) } catch (_: Exception) {}
                    completeDownload(activity, id, update.sha256)
                }
            }
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") activity.registerReceiver(receiver, filter)

            val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
                .setTitle("PokeIdle Assistant ${update.versionName}")
                .setDescription("Baixando e verificando a atualização")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val id = manager.enqueue(request)
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_DOWNLOAD, id)
                .putString(KEY_DIGEST, update.sha256)
                .putBoolean(KEY_PERMISSION_REQUESTED, false)
                .apply()
            Toast.makeText(activity, "Download iniciado. O APK será verificado antes da instalação.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            if (receiver != null) try { activity.unregisterReceiver(receiver) } catch (_: Exception) {}
            Toast.makeText(activity, "Não foi possível iniciar o download: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun completeDownload(activity: Activity, id: Long, expectedDigest: String) {
        try {
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst() ||
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL
                ) {
                    Toast.makeText(activity, "Falha ao baixar o APK. Tente novamente.", Toast.LENGTH_LONG).show()
                    return
                }
            }
            val uri = manager.getUriForDownloadedFile(id)
            if (uri == null) {
                Toast.makeText(activity, "O download terminou, mas o APK não foi localizado.", Toast.LENGTH_LONG).show()
                return
            }
            val actual = sha256(activity, uri)
            if (!actual.equals(expectedDigest, ignoreCase = true)) {
                Toast.makeText(activity, "Verificação de segurança falhou. O APK não será instalado.", Toast.LENGTH_LONG).show()
                return
            }
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_URI, uri.toString()).apply()
            installOrRequestPermission(activity, uri)
        } catch (e: Exception) {
            Toast.makeText(activity, "Não foi possível verificar o APK: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sha256(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "arquivo não pode ser lido" }
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun resumePendingInstall(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PENDING_URI, null)
        if (raw != null) {
            if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) {
                prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, false).apply()
                install(activity, Uri.parse(raw))
            } else if (!prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)) {
                prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            return
        }

        val id = prefs.getLong(KEY_DOWNLOAD, -1L)
        val digest = prefs.getString(KEY_DIGEST, null) ?: return
        if (id < 0 || recoveryRunning) return
        recoveryRunning = true
        executor.execute {
            try {
                val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                var successful = false
                manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    successful = cursor.moveToFirst() &&
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                }
                if (successful) {
                    val uri = manager.getUriForDownloadedFile(id)
                    if (uri != null && sha256(activity, uri).equals(digest, ignoreCase = true)) {
                        main.post {
                            recoveryRunning = false
                            if (activity.isFinishing) return@post
                            prefs.edit().putString(KEY_PENDING_URI, uri.toString()).apply()
                            resumePendingInstall(activity)
                        }
                        return@execute
                    }
                }
            } catch (_: Exception) {
                // Uma recuperação malsucedida não interfere no funcionamento normal do app.
            }
            main.post { recoveryRunning = false }
        }
    }

    private fun installOrRequestPermission(activity: Activity, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
            Toast.makeText(activity, "Autorize a instalação de apps para concluir a atualização.", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        install(activity, uri)
    }

    private fun install(activity: Activity, uri: Uri) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING_URI).apply()
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Não foi possível abrir o instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
