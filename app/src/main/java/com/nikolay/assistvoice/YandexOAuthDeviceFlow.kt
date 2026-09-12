package com.nikolay.assistvoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/** The code/URL pair shown on SmartHomeAuthActivity's QR screen. */
data class DeviceCodeInfo(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Long,
    val expiresInSeconds: Long
)

sealed class DeviceCodeResult {
    data class Success(val info: DeviceCodeInfo) : DeviceCodeResult()
    data class Error(val message: String) : DeviceCodeResult()
}

sealed class TokenPollResult {
    data class Success(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long) :
        TokenPollResult()
    /** Person hasn't confirmed the code yet — keep polling at the same interval. */
    object Pending : TokenPollResult()
    /** Server asked for a longer interval between polls. */
    object SlowDown : TokenPollResult()
    /** The code expired, or the person declined it — start over from step 1. */
    data class Denied(val message: String) : TokenPollResult()
    data class Error(val message: String) : TokenPollResult()
}

sealed class RefreshTokenResult {
    data class Success(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long) :
        RefreshTokenResult()
    data class Error(val message: String) : RefreshTokenResult()
}

/**
 * OAuth 2.0 Device Flow against oauth.yandex.ru — same "QR/code on screen, no
 * in-app browser" pattern as the sibling YandexMusicWatch project.
 *
 * Every call here is a single request; the polling loop itself (repeating
 * pollToken() every intervalSeconds until success/expiry) is owned by
 * SmartHomeAuthActivity so it can stop the moment the screen goes away,
 * rather than living on past the Activity that started it.
 *
 * Same HttpURLConnection + org.json + background Thread style as
 * UpdateChecker — this project has no coroutines dependency.
 */
object YandexOAuthDeviceFlow {

    private const val TAG = "YandexOAuthDeviceFlow"

    private const val DEVICE_CODE_URL = "https://oauth.yandex.ru/device/code"
    private const val TOKEN_URL = "https://oauth.yandex.ru/token"
    private const val SCOPE = "iot:control iot:view"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun requestDeviceCode(callback: (DeviceCodeResult) -> Unit) {
        Thread {
            val result = try {
                performDeviceCodeRequest()
            } catch (e: UnknownHostException) {
                DeviceCodeResult.Error("Нет подключения к интернету")
            } catch (e: IOException) {
                Log.e(TAG, "Device code request failed", e)
                DeviceCodeResult.Error("Ошибка сети")
            } catch (e: Exception) {
                Log.e(TAG, "Device code request failed", e)
                DeviceCodeResult.Error("Не удалось получить код авторизации")
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    fun pollToken(deviceCode: String, callback: (TokenPollResult) -> Unit) {
        Thread {
            val result = try {
                performTokenRequest(
                    "grant_type=device_code&code=${encode(deviceCode)}&" +
                        "client_id=${encode(SmartHomeConfig.YANDEX_CLIENT_ID)}&" +
                        "client_secret=${encode(SmartHomeConfig.YANDEX_CLIENT_SECRET)}"
                ).toPollResult()
            } catch (e: UnknownHostException) {
                TokenPollResult.Error("Нет подключения к интернету")
            } catch (e: IOException) {
                Log.e(TAG, "Token poll failed", e)
                TokenPollResult.Error("Ошибка сети")
            } catch (e: Exception) {
                Log.e(TAG, "Token poll failed", e)
                TokenPollResult.Error("Не удалось проверить авторизацию")
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    fun refreshToken(refreshToken: String, callback: (RefreshTokenResult) -> Unit) {
        Thread {
            val result = try {
                performTokenRequest(
                    "grant_type=refresh_token&refresh_token=${encode(refreshToken)}&" +
                        "client_id=${encode(SmartHomeConfig.YANDEX_CLIENT_ID)}&" +
                        "client_secret=${encode(SmartHomeConfig.YANDEX_CLIENT_SECRET)}"
                ).toRefreshResult()
            } catch (e: UnknownHostException) {
                RefreshTokenResult.Error("Нет подключения к интернету")
            } catch (e: IOException) {
                Log.e(TAG, "Token refresh failed", e)
                RefreshTokenResult.Error("Ошибка сети")
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed", e)
                RefreshTokenResult.Error("Не удалось обновить токен")
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    /**
     * Returns a usable access token, refreshing it first if it's expired or
     * close to it — the one entry point VoiceAccessibilityService and the
     * smart-home screens should call rather than reading SmartHomePrefs'
     * access token directly. Calls back with null when there is no token at
     * all (never authorized / disconnected) or the refresh itself failed.
     */
    fun getValidAccessToken(context: Context, callback: (String?) -> Unit) {
        val token = SmartHomePrefs.getAccessToken(context)
        if (token == null) {
            callback(null)
            return
        }
        if (!SmartHomePrefs.isAccessTokenExpiringSoon(context)) {
            callback(token)
            return
        }
        val refreshToken = SmartHomePrefs.getRefreshToken(context)
        if (refreshToken.isNullOrBlank()) {
            // No refresh token on file (shouldn't normally happen) — try the
            // stale access token rather than fail outright; the caller's own
            // request will surface an auth error if it's really expired.
            callback(token)
            return
        }
        refreshToken(refreshToken) { result ->
            when (result) {
                is RefreshTokenResult.Success -> {
                    if (result.refreshToken != null) {
                        SmartHomePrefs.saveTokens(
                            context, result.accessToken, result.refreshToken, result.expiresInSeconds
                        )
                    } else {
                        SmartHomePrefs.saveRefreshedAccessToken(
                            context, result.accessToken, result.expiresInSeconds
                        )
                    }
                    callback(result.accessToken)
                }
                is RefreshTokenResult.Error -> {
                    Log.e(TAG, "Token refresh failed: ${result.message}")
                    callback(null)
                }
            }
        }
    }

    // ---- Requests ----

    private fun performDeviceCodeRequest(): DeviceCodeResult {
        val body = "client_id=${encode(SmartHomeConfig.YANDEX_CLIENT_ID)}&scope=${encode(SCOPE)}"
        val connection = openPostConnection(DEVICE_CODE_URL, body)
        try {
            val code = connection.responseCode
            val text = readBody(connection, code)
            if (code !in 200..299) {
                return DeviceCodeResult.Error("Яндекс вернул ошибку: $code")
            }
            val json = JSONObject(text)
            val deviceCode = json.optString("device_code", "")
            val userCode = json.optString("user_code", "")
            val verificationUrl = json.optString("verification_url", "")
            if (deviceCode.isEmpty() || userCode.isEmpty()) {
                return DeviceCodeResult.Error("Некорректный ответ сервера")
            }
            return DeviceCodeResult.Success(
                DeviceCodeInfo(
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUrl = verificationUrl.ifBlank { "https://oauth.yandex.ru/device" },
                    intervalSeconds = json.optLong("interval", 5L).coerceAtLeast(1L),
                    expiresInSeconds = json.optLong("expires_in", 600L)
                )
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Raw (httpCode, parsedBody) — shared by the device_code and refresh_token grants. */
    private data class TokenResponse(val httpCode: Int, val json: JSONObject)

    private fun performTokenRequest(body: String): TokenResponse {
        val connection = openPostConnection(TOKEN_URL, body)
        try {
            val code = connection.responseCode
            val text = readBody(connection, code)
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            return TokenResponse(code, json)
        } finally {
            connection.disconnect()
        }
    }

    private fun TokenResponse.toPollResult(): TokenPollResult {
        if (httpCode in 200..299) {
            val accessToken = json.optString("access_token", "")
            val refreshToken = json.optString("refresh_token", "")
            if (accessToken.isEmpty()) return TokenPollResult.Error("В ответе нет access_token")
            return TokenPollResult.Success(accessToken, refreshToken, json.optLong("expires_in", 3600L))
        }
        return when (json.optString("error", "")) {
            "authorization_pending" -> TokenPollResult.Pending
            "slow_down" -> TokenPollResult.SlowDown
            "expired_token" -> TokenPollResult.Denied("Код истёк — попробуй ещё раз")
            "access_denied" -> TokenPollResult.Denied("Доступ отклонён")
            else -> TokenPollResult.Error("Яндекс вернул ошибку: $httpCode")
        }
    }

    private fun TokenResponse.toRefreshResult(): RefreshTokenResult {
        if (httpCode in 200..299) {
            val accessToken = json.optString("access_token", "")
            if (accessToken.isEmpty()) return RefreshTokenResult.Error("В ответе нет access_token")
            return RefreshTokenResult.Success(
                accessToken = accessToken,
                refreshToken = json.optString("refresh_token", "").ifBlank { null },
                expiresInSeconds = json.optLong("expires_in", 3600L)
            )
        }
        return RefreshTokenResult.Error("Яндекс вернул ошибку: $httpCode")
    }

    private fun openPostConnection(url: String, body: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val bytes = body.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        val out: OutputStream = connection.outputStream
        out.use { it.write(bytes) }
        return connection
    }

    /** Yandex puts the useful error body on the error stream for 4xx responses, not inputStream. */
    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
