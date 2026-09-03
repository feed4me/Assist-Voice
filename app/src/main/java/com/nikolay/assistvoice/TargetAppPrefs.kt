package com.nikolay.assistvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Stores the list of voice-trigger slots as JSON in SharedPreferences.
 * Defaults to a single slot pre-filled for stock Yandex Browser's
 * voice assistant (LAUNCH_APP, intentAction = ACTION_ASSIST — the
 * only slot that ever ships with that value; every other/new slot
 * defaults to plain ACTION_MAIN, see VoiceSlot's doc).
 */
object TargetAppPrefs {

    const val PREFS_NAME = "target_app"
    private const val KEY_SLOTS = "slots_json"

    const val DEFAULT_PACKAGE = "com.yandex.browser"
    const val DEFAULT_ACTIVITY = "com.yandex.browser.YandexBrowserMainActivity"
    const val DEFAULT_WAKE_WORD = "алиса"
    const val DEFAULT_INTENT_ACTION = "android.intent.action.MAIN"
    const val ASSIST_INTENT_ACTION = "android.intent.action.ASSIST"

    fun getSlots(context: Context): List<VoiceSlot> {
        val json = prefs(context).getString(KEY_SLOTS, null)
        if (json == null) {
            val initial = listOf(
                VoiceSlot(
                    id = UUID.randomUUID().toString(),
                    enabled = true,
                    actionType = SlotActionType.LAUNCH_APP,
                    wakeWord = DEFAULT_WAKE_WORD,
                    packageName = DEFAULT_PACKAGE,
                    activityName = DEFAULT_ACTIVITY,
                    intentAction = ASSIST_INTENT_ACTION
                )
            )
            saveSlots(context, initial)
            return initial
        }
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
                // ACTION_MAIN) — only the pre-filled Yandex Browser slot
                // ever gets ACTION_ASSIST, set above in getSlots()'s
                // first-run seeding.
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
