package com.nikolay.assistvoice

/**
 * One voice command bound to a single Yandex Smart Home device or group —
 * the smart-home equivalent of VoiceSlot. Every target always has exactly
 * two of these, one per [value] (on and off — e.g. [phrase] "свет в
 * спальне" for both, spoken as "включи свет в спальне"/"выключи свет в
 * спальне" for the same lamp), never more and never deletable, only ever
 * edited — see SmartHomeDeviceCommandsActivity. These live in their own
 * list rather than being folded into TargetAppPrefs' slots.
 *
 * The spoken phrase is the integration's current start word (see
 * SmartHomePrefs.getStartWord/saveStartWord — user-editable, "алиса" by
 * default for Yandex), then the verb derived from [value] ("включи"/
 * "выключи" — never typed, never stored in [phrase] itself), then
 * [phrase] — see VoicePhrases.smartHomeYandexPhraseFor(). Only on_off is
 * supported (capabilityType/instance are fixed defaults, not exposed in
 * the UI) — brightness/color control is explicitly out of scope.
 *
 * [deviceId]/[deviceName] hold a device's id/name when [targetType] is
 * [TARGET_DEVICE], or a group's when it's [TARGET_GROUP] — sending an
 * action to a group uses a different API endpoint than a device (see
 * YandexIotClient.sendAction), so this is what tells it which one to call.
 */
data class SmartHomeCommand(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val phrase: String,
    val capabilityType: String = "devices.capabilities.on_off",
    val instance: String = "on",
    val value: Boolean,
    val targetType: String = TARGET_DEVICE
) {
    companion object {
        const val TARGET_DEVICE = "device"
        const val TARGET_GROUP = "group"
    }
}
