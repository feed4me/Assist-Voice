package com.nikolay.assistvoice

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** One device from /v1.0/user/info's `devices` array, filtered down to what the UI needs. */
data class SmartHomeDevice(
    val id: String,
    val name: String,
    val room: String,
    /** Raw "devices.types.xxx" from the API — see YandexIotClient.readableType(). */
    val type: String,
    /** Ids of every group (see SmartHomeGroup) this device is a member of. */
    val groupIds: List<String>,
    val hasOnOff: Boolean,
    /** Current on/off state as last reported by the API, or null when
     * unknown (device offline, or the API simply didn't include a value) —
     * see YandexIotClient.onOffCapability(). Only meaningful when
     * [hasOnOff] is true. */
    val isOn: Boolean?
)

/**
 * One entry from /v1.0/user/info's `groups` array — a Yandex-side group of
 * several devices (e.g. "весь свет в кабинете") that exposes its own on_off
 * capability, controlling every member device at once. This is the likely
 * answer to "why does one command turn on two lamps": if what looked like a
 * single device in the account is actually one of these, on_off on the
 * group id fans out to all its members — nothing this app does on its own.
 *
 * Sending an action to a group uses a different endpoint than a device (see
 * YandexIotClient.performSendAction) — SmartHomeCommand.targetType is what
 * tells sendAction() which one to call.
 */
data class SmartHomeGroup(
    val id: String,
    val name: String,
    val type: String,
    val hasOnOff: Boolean,
    /** Same meaning as SmartHomeDevice.isOn. */
    val isOn: Boolean?
)

sealed class DevicesResult {
    data class Success(val devices: List<SmartHomeDevice>, val groups: List<SmartHomeGroup>) : DevicesResult()
    data class Error(val message: String) : DevicesResult()
}

sealed class SendActionResult {
    object Success : SendActionResult()
    data class Error(val message: String) : SendActionResult()
}

/**
 * Client for https://api.iot.yandex.net — listing the person's devices/groups
 * and sending on/off actions to them. Plain HttpURLConnection + org.json,
 * same as UpdateChecker/YandexOAuthDeviceFlow — no Retrofit/OkHttp in this
 * project. Every call runs on a background Thread and calls back on the
 * main thread; never call from VoiceAccessibilityService's audio-reading
 * thread directly.
 */
object YandexIotClient {

    private const val TAG = "YandexIotClient"
    private const val BASE_URL = "https://api.iot.yandex.net"
    private const val ON_OFF_CAPABILITY = "devices.capabilities.on_off"

    /**
     * "devices.types.xxx" → a short Russian label for the device row badge.
     * Not an exhaustive enum — Yandex documents roughly these top-level
     * types today (light/socket/switch/thermostat/humidifier/purifier/
     * vacuum_cleaner/kettle/coffee_maker/cooking (multicooker etc.)/sensor/
     * openable/media_device/pet_feeder/dishwasher/washing_machine/iron/camera/other),
     * but it's not a closed set guaranteed to never grow — readableType()
     * below falls back to the raw last segment for anything unmapped rather
     * than a generic "unknown", so a new type Yandex adds later still shows
     * something readable instead of nothing.
     */
    private val TYPE_LABELS: Map<String, String> = mapOf(
        "devices.types.light" to "Свет",
        "devices.types.socket" to "Розетка",
        "devices.types.switch" to "Выключатель",
        "devices.types.thermostat" to "Термостат",
        "devices.types.thermostat.ac" to "Кондиционер",
        "devices.types.humidifier" to "Увлажнитель",
        "devices.types.purifier" to "Очиститель воздуха",
        "devices.types.vacuum_cleaner" to "Пылесос",
        "devices.types.kettle" to "Чайник",
        "devices.types.coffee_maker" to "Кофеварка",
        "devices.types.cooking" to "Кухонная техника",
        "devices.types.cooking.multicooker" to "Мультиварка",
        "devices.types.cooking.microwave" to "Микроволновка",
        "devices.types.cooking.oven" to "Духовка",
        "devices.types.sensor" to "Датчик",
        "devices.types.openable" to "Штора/жалюзи",
        "devices.types.openable.curtain" to "Шторы",
        "devices.types.media_device" to "Медиаустройство",
        "devices.types.media_device.tv" to "Телевизор",
        "devices.types.media_device.receiver" to "Ресивер",
        "devices.types.pet_feeder" to "Кормушка",
        "devices.types.dishwasher" to "Посудомойка",
        "devices.types.washing_machine" to "Стиральная машина",
        "devices.types.iron" to "Утюг",
        "devices.types.camera" to "Камера",
        "devices.types.other" to "Другое"
    )

    /** Readable Russian label for a raw "devices.types.xxx" string. */
    fun readableType(type: String): String {
        TYPE_LABELS[type]?.let { return it }
        val lastSegment = type.substringAfterLast('.', "")
        return lastSegment.ifBlank { "Устройство" }
            .replaceFirstChar { it.uppercase() }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun getDevices(accessToken: String, callback: (DevicesResult) -> Unit) {
        Thread {
            val result = try {
                performGetDevices(accessToken)
            } catch (e: UnknownHostException) {
                DevicesResult.Error("Нет подключения к интернету")
            } catch (e: SocketTimeoutException) {
                DevicesResult.Error("Яндекс не ответил вовремя")
            } catch (e: IOException) {
                Log.e(TAG, "Device list request failed", e)
                DevicesResult.Error("Ошибка сети")
            } catch (e: Exception) {
                Log.e(TAG, "Device list request failed", e)
                DevicesResult.Error("Не удалось получить список устройств")
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    fun sendAction(accessToken: String, command: SmartHomeCommand, callback: (SendActionResult) -> Unit) {
        Thread {
            val result = try {
                performSendAction(accessToken, command)
            } catch (e: UnknownHostException) {
                SendActionResult.Error("Нет подключения к интернету")
            } catch (e: SocketTimeoutException) {
                SendActionResult.Error("Яндекс не ответил вовремя")
            } catch (e: IOException) {
                Log.e(TAG, "Send action failed", e)
                SendActionResult.Error("Ошибка сети")
            } catch (e: Exception) {
                Log.e(TAG, "Send action failed", e)
                SendActionResult.Error("Не удалось выполнить команду")
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    // ---- Requests ----

    private fun performGetDevices(accessToken: String): DevicesResult {
        val connection = openConnection("$BASE_URL/v1.0/user/info", accessToken, "GET")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                return DevicesResult.Error("Яндекс вернул ошибку: $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val roomNameById = mutableMapOf<String, String>()
            val rooms: JSONArray = json.optJSONArray("rooms") ?: JSONArray()
            for (i in 0 until rooms.length()) {
                val room = rooms.optJSONObject(i) ?: continue
                roomNameById[room.optString("id", "")] = room.optString("name", "")
            }

            val devices = mutableListOf<SmartHomeDevice>()
            val devicesJson: JSONArray = json.optJSONArray("devices") ?: JSONArray()
            for (i in 0 until devicesJson.length()) {
                val device = devicesJson.optJSONObject(i) ?: continue
                val id = device.optString("id", "")
                if (id.isEmpty()) continue

                // "room" is documented as the room's id, JSON null if
                // unassigned — org.json's optString("room", "") does NOT
                // fall back to the default on a real JSON null (a known
                // org.json quirk: it stringifies JSONObject.NULL to the
                // literal text "null" instead), so isNull() is checked
                // explicitly first. Otherwise trusted as-is if it doesn't
                // match any known room id, just in case.
                val rawRoom = if (device.isNull("room")) "" else device.optString("room", "")
                val room = roomNameById[rawRoom] ?: rawRoom

                val groupIds = mutableListOf<String>()
                val groupIdsJson = device.optJSONArray("groups")
                if (groupIdsJson != null) {
                    for (g in 0 until groupIdsJson.length()) {
                        groupIdsJson.optString(g, null)?.let { groupIds.add(it) }
                    }
                }

                val onOff = onOffCapability(device.optJSONArray("capabilities"))
                devices.add(
                    SmartHomeDevice(
                        id = id,
                        name = device.optString("name", "Без названия"),
                        room = room,
                        type = device.optString("type", ""),
                        groupIds = groupIds,
                        hasOnOff = onOff.present,
                        isOn = onOff.value
                    )
                )
            }

            val groups = mutableListOf<SmartHomeGroup>()
            val groupsJson: JSONArray = json.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until groupsJson.length()) {
                val group = groupsJson.optJSONObject(i) ?: continue
                val id = group.optString("id", "")
                if (id.isEmpty()) continue
                val onOff = onOffCapability(group.optJSONArray("capabilities"))
                groups.add(
                    SmartHomeGroup(
                        id = id,
                        name = group.optString("name", "Без названия"),
                        type = group.optString("type", ""),
                        hasOnOff = onOff.present,
                        isOn = onOff.value
                    )
                )
            }

            return DevicesResult.Success(devices, groups)
        } finally {
            connection.disconnect()
        }
    }

    private data class OnOffCapability(val present: Boolean, val value: Boolean?)

    /**
     * Finds the on_off capability, if any, and reads its current value.
     *
     * Same org.json null quirk as the room id above (see performGetDevices'
     * comment): a real JSON null for "value" must be checked with isNull()
     * first, since optBoolean's own fallback wouldn't reliably distinguish
     * "unknown" from a genuine false.
     */
    private fun onOffCapability(capabilities: JSONArray?): OnOffCapability {
        if (capabilities == null) return OnOffCapability(present = false, value = null)
        for (c in 0 until capabilities.length()) {
            val capability = capabilities.optJSONObject(c) ?: continue
            if (capability.optString("type", "") != ON_OFF_CAPABILITY) continue
            val state = capability.optJSONObject("state")
            val value = if (state == null || state.isNull("value")) null else state.optBoolean("value")
            return OnOffCapability(present = true, value = value)
        }
        return OnOffCapability(present = false, value = null)
    }

    private fun performSendAction(accessToken: String, command: SmartHomeCommand): SendActionResult {
        val actionsPayload = JSONObject().apply {
            put("type", command.capabilityType)
            put(
                "state",
                JSONObject().apply {
                    put("instance", command.instance)
                    put("value", command.value)
                }
            )
        }

        val url: String
        val body: String
        if (command.targetType == SmartHomeCommand.TARGET_GROUP) {
            url = "$BASE_URL/v1.0/groups/${command.deviceId}/actions"
            body = JSONObject().apply {
                put("actions", JSONArray().put(actionsPayload))
            }.toString()
        } else {
            url = "$BASE_URL/v1.0/devices/actions"
            body = JSONObject().apply {
                put(
                    "devices",
                    JSONArray().put(
                        JSONObject().apply {
                            put("id", command.deviceId)
                            put("actions", JSONArray().put(actionsPayload))
                        }
                    )
                )
            }.toString()
        }

        val connection = openConnection(url, accessToken, "POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            if (code !in 200..299) {
                return SendActionResult.Error("Яндекс вернул ошибку: $code")
            }

            // A 2xx here only means the request was accepted — the response
            // body carries a per-device/per-capability status that can still
            // be an error (device offline, capability rejected, etc).
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val actionError = firstActionError(responseText)
            return if (actionError != null) SendActionResult.Error(actionError) else SendActionResult.Success
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A capability's outcome lives at devices[].capabilities[].state.action_result
     * .{status, error_code, error_message} — nested, not flat fields directly
     * on `state`. Some responses wrap the whole thing in a top-level
     * "payload" object instead of putting "devices" at the root; both shapes
     * are tried since Yandex's own docs aren't fully consistent about which
     * is current.
     */
    private fun firstActionError(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            val root = json.optJSONObject("payload") ?: json
            val devices: JSONArray = root.optJSONArray("devices") ?: return null
            for (i in 0 until devices.length()) {
                val device = devices.optJSONObject(i) ?: continue
                val capabilities: JSONArray = device.optJSONArray("capabilities") ?: continue
                for (c in 0 until capabilities.length()) {
                    val capability = capabilities.optJSONObject(c) ?: continue
                    val state = capability.optJSONObject("state") ?: continue
                    val actionResult = state.optJSONObject("action_result")
                    val status = actionResult?.optString("status", "DONE") ?: "DONE"
                    if (status == "ERROR") {
                        return actionResult?.optString("error_message", "Устройство отклонило команду")
                            ?: "Устройство отклонило команду"
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun openConnection(url: String, accessToken: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return connection
    }
}
