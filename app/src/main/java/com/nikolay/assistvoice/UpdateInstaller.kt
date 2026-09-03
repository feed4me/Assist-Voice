package com.nikolay.assistvoice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the .apk UpdateChecker found and hands it to the system package
 * installer. Kept separate from UpdateChecker: that class only ever talks to
 * the GitHub API, this one touches the filesystem, FileProvider and the
 * install-permission/intent dance — different enough concerns to split.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun updatesDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * Downloads [info]'s APK into the app's cache dir. [onProgress] fires on
     * the main thread with 0-100 (or is skipped entirely if the server
     * doesn't send a Content-Length). [onComplete] also fires on the main
     * thread with exactly one of (file, error) non-null.
     */
    fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onComplete: (File?, String?) -> Unit
    ) {
        val appContext = context.applicationContext
        Thread {
            var result: File? = null
            var error: String? = null
            var connection: HttpURLConnection? = null
            try {
                val dir = updatesDir(appContext)
                // Clear the dir first: a previous attempt interrupted
                // mid-write would otherwise leave a stale .part (or even a
                // stale finished apk under a different version's file name)
                // sitting around indefinitely.
                dir.listFiles()?.forEach { it.delete() }
                val destFile = File(dir, info.apkFileName)
                val tmpFile = File(dir, "${info.apkFileName}.part")

                connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AssistVoice-UpdateChecker")
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    error = "Не удалось скачать обновление (код $code)"
                } else {
                    val total = connection.contentLength
                    var written = 0L
                    var lastPercent = -1
                    connection.inputStream.use { input ->
                        tmpFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total > 0) {
                                    val percent = ((written * 100) / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        mainHandler.post { onProgress(percent) }
                                    }
                                }
                            }
                        }
                    }
                    result = if (tmpFile.renameTo(destFile)) {
                        destFile
                    } else {
                        error = "Не удалось сохранить скачанный файл"
                        null
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Update download failed", e)
                error = "Ошибка сети при скачивании обновления"
            } catch (e: Exception) {
                Log.e(TAG, "Update download failed", e)
                error = "Не удалось скачать обновление"
            } finally {
                connection?.disconnect()
            }
            mainHandler.post { onComplete(result, error) }
        }.start()
    }

    /**
     * Hands [apkFile] to the system installer via a FileProvider content://
     * URI. Returns false when REQUEST_INSTALL_PACKAGES hasn't been granted
     * yet — in that case this also opens the "install unknown apps" settings
     * screen for this app (same defensive try/catch as MainActivity's
     * overlay-permission request: not every screen this launches exists on
     * every ROM). There's no reliable callback for "the person just granted
     * it and came back", so the caller just leaves the status as
     * ReadyToInstall and lets them tap "Установить" again.
     */
    fun promptInstall(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not open unknown-sources settings", e)
            }
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch package installer", e)
            false
        }
    }
}
