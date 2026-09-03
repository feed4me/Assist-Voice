package com.nikolay.assistvoice

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * One asset from a GitHub release worth updating to: a newer version tag
 * plus the direct download URL of its .apk asset.
 */
data class UpdateInfo(
    val versionName: String,
    val apkUrl: String,
    val apkFileName: String,
    val releaseNotes: String?
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * Drives the info page's update section (see SlotsAdapter.InfoViewHolder and
 * MainActivity.updateStatus) through one check → download → install cycle.
 */
sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    object UpToDate : UpdateStatus()
    data class Available(val info: UpdateInfo) : UpdateStatus()
    data class Downloading(val info: UpdateInfo, val percent: Int = -1) : UpdateStatus()
    data class ReadyToInstall(val info: UpdateInfo, val apkFile: java.io.File) : UpdateStatus()
    /** Button was tapped on ReadyToInstall — connecting over ADB and/or
     * installing now. See AdbUpdateInstaller and MainActivity.installUpdate. */
    data class Installing(
        val info: UpdateInfo,
        val apkFile: java.io.File,
        val message: String = "Подключаюсь по ADB…"
    ) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

/**
 * Checks GitHub's "latest release" API for a newer build than the one
 * currently installed. No auth token: this app runs on the watch, so any
 * token baked into the APK would be trivially extractable — which is also
 * exactly why this only works once github.com/feed4me/Assist-Voice is
 * public. Against a private repo the API returns a plain 404 (not a
 * permission error), which performCheck() turns into an explicit message
 * rather than a confusing generic failure.
 *
 * See .github/workflows/release.yml for how a release (and its .apk asset)
 * gets published in the first place.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO_OWNER = "feed4me"
    private const val REPO_NAME = "Assist-Voice"
    private const val API_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun checkForUpdate(currentVersionName: String, callback: (UpdateCheckResult) -> Unit) {
        Thread {
            val result = try {
                performCheck(currentVersionName)
            } catch (e: UnknownHostException) {
                UpdateCheckResult.Error("Нет подключения к интернету")
            } catch (e: IOException) {
                Log.e(TAG, "Update check failed", e)
                UpdateCheckResult.Error("Ошибка сети при проверке обновлений")
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                UpdateCheckResult.Error("Не удалось проверить обновления")
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    private fun performCheck(currentVersionName: String): UpdateCheckResult {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub's API rejects requests with no User-Agent outright.
            setRequestProperty("User-Agent", "AssistVoice-UpdateChecker")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val code = connection.responseCode
            if (code == 404) {
                // The one case worth calling out by name: this is what a
                // private repo looks like to an unauthenticated request,
                // not a "no releases yet" or permissions error.
                return UpdateCheckResult.Error(
                    "Репозиторий приватный или в нём ещё нет релизов"
                )
            }
            if (code !in 200..299) {
                return UpdateCheckResult.Error("GitHub вернул ошибку: $code")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")
            if (tagName.isEmpty()) {
                return UpdateCheckResult.Error("В ответе GitHub нет версии релиза")
            }

            val assets: JSONArray = json.optJSONArray("assets") ?: JSONArray()
            var apkUrl: String? = null
            var apkName: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    val downloadUrl = asset.optString("browser_download_url", "")
                    if (downloadUrl.isNotEmpty()) {
                        apkUrl = downloadUrl
                        apkName = name
                        break
                    }
                }
            }
            if (apkUrl == null) {
                return UpdateCheckResult.Error("В релизе $tagName нет прикреплённого APK")
            }

            val remoteVersion = tagName.removePrefix("v").removePrefix("V")
            return if (isNewer(remoteVersion, currentVersionName)) {
                UpdateCheckResult.UpdateAvailable(
                    UpdateInfo(
                        versionName = remoteVersion,
                        apkUrl = apkUrl,
                        apkFileName = apkName ?: "AssistVoice-update.apk",
                        releaseNotes = json.optString("body", "").takeIf { it.isNotBlank() }
                    )
                )
            } else {
                UpdateCheckResult.UpToDate
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Plain numeric-segment comparison ("1.10" > "1.9"), not a string
     * comparison — good enough for the "1.2", "1.2.3" style tags this
     * project uses without pulling in a real semver dependency.
     */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = normalize(remote)
        val l = normalize(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun normalize(version: String): List<Int> =
        version.trim().split(".").map { segment ->
            segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
}
