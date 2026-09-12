package com.nikolay.assistvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Stores the list of voice-trigger slots as JSON in SharedPreferences.
 * Starts empty — every slot is hand-added through the "Команды" screen.
 */
object TargetAppPrefs {

    const val PREFS_NAME = "target_app"
    private const val KEY_SLOTS = "slots_json"
    private const val KEY_VIBRATE_ON_COMMAND = "vibrate_on_command"
    private const val KEY_LISTEN_EVERYWHERE = "listen_everywhere"

    const val DEFAULT_WAKE_WORD = "алиса"
    const val DEFAULT_INTENT_ACTION = "android.intent.action.MAIN"
    const val ASSIST_INTENT_ACTION = "android.intent.action.ASSIST"

    /** Short haptic feedback the instant a voice command is recognized — see
     * VoiceAccessibilityService.vibrateOnCommandMatch(). On by default. */
    fun isVibrateOnCommandEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBRATE_ON_COMMAND, true)

    fun saveVibrateOnCommand(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATE_ON_COMMAND, enabled).apply()
    }

    /** Off by default: the mic only listens on the watch face (see
     * VoiceAccessibilityService.isOnWatchFace() and its WATCH_FACE_PACKAGES/
     * WEAR_OS_SYSUI_CLASS_NAME). On, it listens with any app in the
     * foreground — the escape hatch for a watch whose home-screen package
     * this app doesn't recognize. */
    fun isListenEverywhereEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LISTEN_EVERYWHERE, false)

    fun saveListenEverywhere(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LISTEN_EVERYWHERE, enabled).apply()
    }

    fun getSlots(context: Context): List<VoiceSlot> {
        val json = prefs(context).getString(KEY_SLOTS, null) ?: return emptyList()
        return parseSlots(json)
    }

    fun saveSlots(context: Context, slots: List<VoiceSlot>) {
        val array = JSONArray()
        for (slot in slots) {
            val obj = JSONObject()
            obj.put("id", slot.id)
            obj.put("enabled", slot.enabled)
            obj.put("actionType", slot.actionType.name)
            obj.put("wakeWord", slot.wakeWord.trim().lowercase())
            obj.put("packageName", slot.packageName)
            obj.put("activityName", slot.activityName)
            obj.put("intentAction", slot.intentAction.trim())
            obj.put("contactName", slot.contactName)
            obj.put("phoneNumber", slot.phoneNumber)
            array.put(obj)
        }
        prefs(context).edit()
            .putString(KEY_SLOTS, array.toString())
            .apply()
    }

    fun addEmptySlot(context: Context): List<VoiceSlot> {
        val slots = getSlots(context).toMutableList()
        slots.add(
            VoiceSlot(
                id = UUID.randomUUID().toString(),
                enabled = true,
                actionType = SlotActionType.LAUNCH_APP
                // intentAction defaults to DEFAULT_INTENT_ACTION (plain
                // ACTION_MAIN); ASSIST_INTENT_ACTION is only ever picked
                // explicitly for a slot that needs it via SlotEditActivity.
            )
        )
        saveSlots(context, slots)
        return slots
    }

    fun deleteSlot(context: Context, slotId: String): List<VoiceSlot> {
        val slots = getSlots(context).filterNot { it.id == slotId }
        saveSlots(context, slots)
        return slots
    }

    private fun parseSlots(json: String): List<VoiceSlot> {
        val result = mutableListOf<VoiceSlot>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val actionType = try {
                    SlotActionType.valueOf(obj.optString("actionType", "LAUNCH_APP"))
                } catch (e: IllegalArgumentException) {
                    // Handles slots saved by an older build that had
                    // TIMER/FLASHLIGHT/MEDIA — fall back to LAUNCH_APP
                    // rather than crash on an unknown enum value.
                    SlotActionType.LAUNCH_APP
                }
                // Backward compatibility: slots saved by a build that had
                // the old useAssistAction Boolean (instead of a free-text
                // intentAction) get migrated automatically here.
                val intentAction = if (obj.has("intentAction")) {
                    obj.optString("intentAction", DEFAULT_INTENT_ACTION)
                } else if (obj.optBoolean("useAssistAction", false)) {
                    ASSIST_INTENT_ACTION
                } else {
                    DEFAULT_INTENT_ACTION
                }
                result.add(
                    VoiceSlot(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        enabled = obj.optBoolean("enabled", true),
                        actionType = actionType,
                        wakeWord = obj.optString("wakeWord", "").lowercase(),
                        packageName = obj.optString("packageName", ""),
                        activityName = obj.optString("activityName", ""),
                        intentAction = intentAction.ifBlank { DEFAULT_INTENT_ACTION },
                        contactName = obj.optString("contactName", ""),
                        phoneNumber = obj.optString("phoneNumber", "")
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupt prefs — fall back to an empty list rather than crash;
            // the person can re-add slots via the UI.
        }
        return result
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
