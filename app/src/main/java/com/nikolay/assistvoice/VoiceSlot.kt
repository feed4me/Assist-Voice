package com.nikolay.assistvoice

/**
 * The kind of action a slot performs when its wake word is heard.
 * Only LAUNCH_APP and CALL are supported on this Huawei/EMUI watch build:
 * a timer and system-app controls are signature-permission-blocked, and
 * there's no camera hardware for a flashlight.
 */
enum class SlotActionType {
    LAUNCH_APP,
    CALL
}

/**
 * One voice-trigger slot: a wake word paired with an action to
 * perform. Multiple slots let different spoken phrases trigger
 * different actions. `enabled` lets a slot be temporarily turned off
 * without deleting it.
 *
 * Fields are interpreted based on `actionType`:
 * - LAUNCH_APP: packageName, activityName, wakeWord, intentAction.
 *   `intentAction` is a free-text Intent Action string (e.g.
 *   "android.intent.action.MAIN" or "android.intent.action.ASSIST") —
 *   editable on every slot. It defaults to plain ACTION_MAIN for new
 *   slots; only the pre-filled Yandex Browser default slot ships with
 *   it set to ACTION_ASSIST (set once at creation time in
 *   TargetAppPrefs' first-run seeding, not otherwise special-cased).
 *   A free-text field was used instead of a dropdown because Android's
 *   PackageManager has no API to enumerate which actions a given
 *   component supports — only to check one specific action at a time.
 * - CALL: contactName (display only) + phoneNumber, wakeWord
 */
data class VoiceSlot(
    val id: String,
    val enabled: Boolean,
    val actionType: SlotActionType = SlotActionType.LAUNCH_APP,
    val wakeWord: String = "",

    // LAUNCH_APP fields
    val packageName: String = "",
    val activityName: String = "",
    val intentAction: String = TargetAppPrefs.DEFAULT_INTENT_ACTION,

    // CALL fields
    val contactName: String = "",
    val phoneNumber: String = ""
)
