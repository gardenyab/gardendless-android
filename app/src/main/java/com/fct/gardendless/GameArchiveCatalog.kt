package com.fct.gardendless

import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

internal data class GameArchiveEntry(
    val name: String,
    val gpNext: String?,
    val url: String
)

internal sealed class GameArchiveCatalogResult {
    data class Success(val entries: List<GameArchiveEntry>) : GameArchiveCatalogResult()
    data class Error(val message: String?) : GameArchiveCatalogResult()
}

internal object GameArchiveCatalog {

    private const val REMOTE_JSON_URL =
        "https://raw.githubusercontent.com/gardenyab/gardendless-android/main/game-archives.json"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 6
    private const val BUFFER_SIZE = 16 * 1024

    fun loadCatalog(): GameArchiveCatalogResult {
        return try {
            val rawJson = fetchRemoteJson()
            GameArchiveCatalogResult.Success(parseCatalogJson(rawJson))
        } catch (e: Exception) {
            GameArchiveCatalogResult.Error(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    internal fun parseCatalogJson(rawJson: String): List<GameArchiveEntry> {
        val root = JSONObject(rawJson)
        val versions = root.optJSONArray("versions") ?: return emptyList()
        val result = ArrayList<GameArchiveEntry>(versions.length())
        for (i in 0 until versions.length()) {
            val item = versions.optJSONObject(i) ?: continue
            val entry = buildEntry(
                name = item.optString("name", ""),
                gpNext = item.optString("gpNext", ""),
                url = item.optString("url", "")
            ) ?: continue
            result.add(entry)
        }
        return result
    }

    internal fun buildEntry(name: String, gpNext: String?, url: String): GameArchiveEntry? {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty() || !cleanUrl.startsWith("https://")) return null
        val cleanGpNext = gpNext?.trim()?.takeIf { it.isNotEmpty() }
        return GameArchiveEntry(name = cleanName, gpNext = cleanGpNext, url = cleanUrl)
    }

    private fun fetchRemoteJson(): String {
        val connection = openFollowingRedirects(REMOTE_JSON_URL)
        return try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadArchiveToFile(
        urlString: String,
        targetFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val connection = openFollowingRedirects(urlString)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code")
            }
            val totalBytes = connection.contentLengthLong
            var downloaded = 0L
            var lastReported = -1L
            onProgress(0L, totalBytes)
            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastReported >= 64 * 1024 || (totalBytes > 0 && downloaded >= totalBytes)) {
                            lastReported = downloaded
                            onProgress(downloaded, totalBytes)
                        }
                    }
                    output.flush()
                }
            }
            onProgress(downloaded, if (totalBytes > 0) totalBytes else downloaded)
        } finally {
            connection.disconnect()
        }
    }

    private fun openFollowingRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("User-Agent", "PvZ-Gardendless-Android")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect without Location header")
                currentUrl = URL(URL(currentUrl), location).toString()
                continue
            }
            return conn
        }
        throw IOException("Too many redirects")
    }
}
