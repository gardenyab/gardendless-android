package com.fct.gardendless

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class LauncherUpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String?
)

internal sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: LauncherUpdateInfo) : UpdateCheckResult()
    data class UpToDate(val latestVersion: String?) : UpdateCheckResult()
    data class Error(val message: String?) : UpdateCheckResult()
}

internal object LauncherUpdateChecker {

    private const val REPO = "gardenyab/gardendless-android"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_LIST_URL = "https://api.github.com/repos/$REPO/releases?per_page=1"
    private const val TAGS_LIST_URL = "https://api.github.com/repos/$REPO/tags?per_page=1"
    private const val RELEASES_WEB_URL = "https://github.com/$REPO/releases"
    private const val TIMEOUT_MS = 8_000

    fun checkForUpdate(currentVersion: String): UpdateCheckResult {
        return try {
            val info = fetchLatestRelease()
                ?: fetchFirstRelease()
                ?: fetchLatestTag()
                ?: return UpdateCheckResult.UpToDate(null)

            if (isNewerVersion(info.latestVersion, currentVersion)) {
                UpdateCheckResult.UpdateAvailable(info)
            } else {
                UpdateCheckResult.UpToDate(info.latestVersion)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    private fun fetchLatestRelease(): LauncherUpdateInfo? {
        val response = httpGet(LATEST_RELEASE_URL) ?: return null
        return parseReleaseJson(JSONObject(response))
    }

    private fun fetchFirstRelease(): LauncherUpdateInfo? {
        val response = httpGet(RELEASES_LIST_URL) ?: return null
        val array = JSONArray(response)
        if (array.length() == 0) return null
        return parseReleaseJson(array.getJSONObject(0))
    }

    private fun fetchLatestTag(): LauncherUpdateInfo? {
        val response = httpGet(TAGS_LIST_URL) ?: return null
        val array = JSONArray(response)
        if (array.length() == 0) return null
        val tagName = array.getJSONObject(0).optString("name", "").trim()
        val normalized = normalizeVersion(tagName)
        if (normalized.isEmpty()) return null
        return LauncherUpdateInfo(
            latestVersion = normalized,
            downloadUrl = "$RELEASES_WEB_URL/tag/$tagName",
            releaseNotes = null
        )
    }

    internal fun parseReleaseJson(json: JSONObject): LauncherUpdateInfo? {
        val rawTag = json.optString("tag_name", "").ifBlank { json.optString("name", "") }.trim()
        val normalized = normalizeVersion(rawTag)
        if (normalized.isEmpty()) return null

        val htmlUrl = json.optString("html_url", "").ifBlank { RELEASES_WEB_URL }
        val apkUrl = findApkDownloadUrl(json.optJSONArray("assets"))
        val body = json.optString("body", "").trim().takeIf { it.isNotEmpty() }?.let { text ->
            if (text.length > 420) text.take(420).trimEnd() + "…" else text
        }
        return LauncherUpdateInfo(
            latestVersion = normalized,
            downloadUrl = apkUrl ?: htmlUrl,
            releaseNotes = body
        )
    }

    private fun findApkDownloadUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            val url = asset.optString("browser_download_url", "")
            if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                return url
            }
        }
        return null
    }

    /** Returns response body on HTTP 200, null on 404, throws on network/other HTTP errors. */
    private fun httpGet(urlString: String): String? {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PvZ-Gardendless-Android")
        }
        return try {
            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    /**
     * Compares dot-separated version strings numerically (e.g. "1.1" > "1.0", "1.10" > "1.2",
     * "1.0.1" > "1.0", "v2.0-beta" > "1.9").
     */
    internal fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = parseNumericSegments(remote)
        val currentParts = parseNumericSegments(current)
        if (remoteParts.isEmpty() || currentParts.isEmpty()) return false
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    private fun parseNumericSegments(version: String): List<Int> {
        val clean = normalizeVersion(version)
            .substringBefore('-')
            .substringBefore('+')
            .trim()
        if (clean.isEmpty()) return emptyList()
        return clean.split('.').mapNotNull { segment ->
            val digits = segment.takeWhile { it.isDigit() }
            digits.toIntOrNull()
        }
    }
}
