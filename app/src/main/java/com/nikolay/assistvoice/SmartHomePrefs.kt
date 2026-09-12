package com.nikolay.assistvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores everything specific to the Yandex Smart Home integration: OAuth
 * tokens, its list of voice commands (SmartHomeCommand, JSON in
 * SharedPreferences — the same pattern TargetAppPrefs uses for VoiceSlot),
 * and the integration's start word.
 *
 * A separate prefs file (not TargetAppPrefs' own, and not VadSettings'
 * either) so that VoiceAccessibilityService can register its own
 * OnSharedPreferenceChangeListener here without it firing on every ordinary
 * slot edit or VAD-tuning change, and vice versa.
 */
object SmartHomePrefs {

    const val PREFS_NAME = "smart_home_yandex"

    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at_millis"
    private const val KEY_COMMANDS = "commands_json"
    private const val KEY_START_WORD = "start_word"

    /** Refresh a bit before the real expiry so a request never races it. */
    private const val EXPIRY_MARGIN_MS = 60_000L

    // ---- Start word ----

    /** "алиса" unless changed — see VoicePhrases.PREFIX_SMART_HOME_YANDEX
     * for the default and VoicePhrases.smartHomeYandexPhraseFor() for where
     * this is actually used to build phrases. */
    fun getStartWord(context: Context): String =
        prefs(context).getString(KEY_START_WORD, null)?.trim()?.lowercase()
            ?.ifBlank { VoicePhrases.PREFIX_SMART_HOME_YANDEX }
            ?: VoicePhrases.PREFIX_SMART_HOME_YANDEX

    fun saveStartWord(context: Context, word: String) {
        prefs(context).edit()
            .putString(KEY_START_WORD, word.trim().lowercase())
            .apply()
    }

    // ---- Tokens ----

    fun isAuthorized(context: Context): Boolean =
        !getAccessToken(context).isNullOrBlank()

    fun getAccessToken(context: Context): String? =
        prefs(context).getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(context: Context): String? =
        prefs(context).getString(KEY_REFRESH_TOKEN, null)

    fun isAccessTokenExpiringSoon(context: Context): Boolean {
        val expiresAt = prefs(context).getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt == 0L) return true
        return System.currentTimeMillis() >= expiresAt - EXPIRY_MARGIN_MS
    }

    fun saveTokens(context: Context, accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000L)
            .apply()
    }

    /** A refresh response may omit refresh_token when the old one is still valid. */
    fun saveRefreshedAccessToken(context: Context, accessToken: String, expiresInSeconds: Long) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000L)
            .apply()
    }

    /** Disconnects the integration — commands are left in place, only tokens are dropped. */
    fun clearTokens(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    // ---- Commands ----

    fun getCommands(context: Context): List<SmartHomeCommand> {
        val json = prefs(context).getString(KEY_COMMANDS, null) ?: return emptyList()
        return parseCommands(json)
    }

    fun saveCommands(context: Context, commands: List<SmartHomeCommand>) {
        val array = JSONArray()
        for (command in commands) {
            val obj = JSONObject()
            obj.put("id", command.id)
            obj.put("deviceId", command.deviceId)
            obj.put("deviceName", command.deviceName)
            obj.put("phrase", command.phrase.trim().lowercase())
            obj.put("capabilityType", command.capabilityType)
            obj.put("instance", command.instance)
            obj.put("value", command.value)
            obj.put("targetType", command.targetType)
            array.put(obj)
        }
        prefs(context).edit()
            .putString(KEY_COMMANDS, array.toString())
            .apply()
    }

    fun addCommand(context: Context, command: SmartHomeCommand) {
        val commands = getCommands(context).toMutableList()
        commands.add(command)
        saveCommands(context, commands)
    }

    /** Replaces the command with a matching id (no-op if it's gone). */
    fun updateCommand(context: Context, updated: SmartHomeCommand) {
        val commands = getCommands(context).toMutableList()
        val index = commands.indexOfFirst { it.id == updated.id }
        if (index < 0) return
        commands[index] = updated
        saveCommands(context, commands)
    }

    fun commandsForDevice(context: Context, deviceId: String): List<SmartHomeCommand> =
        getCommands(context).filter { it.deviceId == deviceId }

    /** The on/off command for one target, if it's been configured yet — see
     * SmartHomeDeviceCommandsActivity, which always shows exactly one ON and
     * one OFF slot per target and never lets either be deleted, only edited. */
    fun commandFor(context: Context, deviceId: String, value: Boolean): SmartHomeCommand? =
        commandsForDevice(context, deviceId).firstOrNull { it.value == value }

    private fun parseCommands(json: String): List<SmartHomeCommand> {
        val result = mutableListOf<SmartHomeCommand>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    SmartHomeCommand(
                        id = obj.optString("id", ""),
                        deviceId = obj.optString("deviceId", ""),
                        deviceName = obj.optString("deviceName", ""),
                        phrase = obj.optString("phrase", "").lowercase(),
                        capabilityType = obj.optString("capabilityType", "devices.capabilities.on_off"),
                        instance = obj.optString("instance", "on"),
                        value = obj.optBoolean("value", true),
                        targetType = obj.optString("targetType", SmartHomeCommand.TARGET_DEVICE)
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupt prefs — fall back to an empty list rather than crash;
            // matches TargetAppPrefs.parseSlots()'s handling.
        }
        return result.filter { it.id.isNotBlank() && it.deviceId.isNotBlank() }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
