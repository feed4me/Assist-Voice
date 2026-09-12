package com.nikolay.assistvoice

import android.content.Context

/**
 * Builds the spoken command phrase for a slot.
 *
 * Every command is deliberately two words: a fixed prefix determined by the
 * action type, then the slot's own word. This is the main defence against
 * false triggers: Vosk runs full open-vocabulary recognition (see
 * VoiceAccessibilityService.ensureRecognizer), so nothing constrains what it
 * can transcribe — the two-word requirement means noise or an unrelated
 * word has to coincidentally produce both specific words *in sequence* to
 * match, a far harder accident than matching either word alone.
 */
object VoicePhrases {

    const val PREFIX_LAUNCH_APP = "открой"
    const val PREFIX_CALL = "позвони"

    fun prefixFor(actionType: SlotActionType): String = when (actionType) {
        SlotActionType.LAUNCH_APP -> PREFIX_LAUNCH_APP
        SlotActionType.CALL -> PREFIX_CALL
    }

    /**
     * The full phrase the person has to say for this slot, e.g. "открой алиса".
     * Blank when the slot has no word configured yet.
     */
    fun phraseFor(slot: VoiceSlot): String = phraseFor(slot.actionType, slot.wakeWord)

    fun phraseFor(actionType: SlotActionType, wakeWord: String): String {
        val word = wakeWord.trim().lowercase()
        if (word.isEmpty()) return ""
        return "${prefixFor(actionType)} $word"
    }

    // ------------------------------------------------------------------
    // Screen "flashlight" — see FlashlightActivity/FlashlightController.
    // ------------------------------------------------------------------

    /**
     * Unlike a slot phrase, these two are whole fixed commands, not a prefix
     * combined with a user-chosen word — there is no variable part, so they
     * don't go through phraseFor(). Matched unconditionally in
     * VoiceAccessibilityService.handleHypothesis(), never exposed as a slot
     * type, and not configurable through any settings screen.
     */
    const val PHRASE_FLASHLIGHT_ON = "включи фонарик"
    const val PHRASE_FLASHLIGHT_OFF = "выключи фонарик"

    val FLASHLIGHT_PHRASES = listOf(PHRASE_FLASHLIGHT_ON, PHRASE_FLASHLIGHT_OFF)

    // ------------------------------------------------------------------
    // YandexMusicWatch playback control — see YandexMusicController.
    // ------------------------------------------------------------------

    /**
     * Same kind of fixed, non-slot reserved commands as the flashlight
     * phrases above — matched unconditionally in
     * VoiceAccessibilityService.handleHypothesis(), never exposed as a slot
     * type or a settings screen.
     */
    const val PHRASE_MUSIC_WAVE = "включи волну"
    const val PHRASE_MUSIC_LIKES = "включи любимую музыку"
    /** No target section — just resumes whatever was last playing (or
     * starts the wave if nothing was), via YandexMusicController.resume(). */
    const val PHRASE_MUSIC_ON = "включи музыку"
    const val PHRASE_MUSIC_OFF = "выключи музыку"
    const val PHRASE_MUSIC_NEXT = "следующий трек"
    const val PHRASE_MUSIC_PREV = "предыдущий трек"
    /** Unlike PHRASE_MUSIC_ON, this never touches YandexMusicWatch — always
     * routed generically (see YandexMusicController.continuePlayback) to
     * whatever currently holds media-button focus. */
    const val PHRASE_MUSIC_CONTINUE = "продолжи воспроизведение"

    val MUSIC_PHRASES = listOf(
        PHRASE_MUSIC_WAVE, PHRASE_MUSIC_LIKES, PHRASE_MUSIC_ON,
        PHRASE_MUSIC_OFF, PHRASE_MUSIC_NEXT, PHRASE_MUSIC_PREV,
        PHRASE_MUSIC_CONTINUE
    )

    // ------------------------------------------------------------------
    // Incoming call answer/decline — see VoiceAccessibilityService's call
    // handling section.
    // ------------------------------------------------------------------

    /**
     * Same kind of fixed, non-slot reserved commands as the flashlight/music
     * phrases above — always matched, never a slot type or a settings
     * screen. Unlike those, the action they trigger is itself gated on a
     * call actually being in the CALL_STATE_RINGING state (see
     * VoiceAccessibilityService.onCallAnswerCommand/onCallDeclineCommand) —
     * recognizing the phrase and acting on it are deliberately separate,
     * since ending a call that isn't ringing ends whatever call IS active.
     */
    const val PHRASE_CALL_ANSWER = "прими звонок"
    const val PHRASE_CALL_DECLINE = "отклони звонок"

    val CALL_ACTION_PHRASES = listOf(PHRASE_CALL_ANSWER, PHRASE_CALL_DECLINE)

    // ------------------------------------------------------------------
    // Smart-home integrations — see SmartHomeCommand/VoiceAccessibilityService's
    // smart-home cache. Unlike LAUNCH_APP/CALL's prefix (tied to the action
    // type), each *integration* gets its own start word — "алиса" for
    // Yandex Smart Home by default, others to follow as more integrations
    // are added. Editable per-integration (see SmartHomePrefs.getStartWord/
    // saveStartWord and SmartHomeHubActivity's start-word row), unlike a
    // slot's wakeWord it has no per-command variant, one word covers every
    // command of that integration. The rest of the phrase after the verb
    // (the device/target description, e.g. "свет на кухне") is free text
    // set by Nikolay per command, same as a slot's wakeWord — but the verb
    // itself ("включи"/"выключи") is never typed: every command is
    // structurally one of exactly two states (see SmartHomeCommand's doc),
    // so the verb is derived from [SmartHomeCommand.value] and always
    // prepended automatically.
    // ------------------------------------------------------------------

    /** Default/fallback start word for the Yandex Smart Home integration —
     * see SmartHomePrefs.getStartWord() for the (possibly user-changed) one
     * actually in effect. */
    const val PREFIX_SMART_HOME_YANDEX = "алиса"

    const val VERB_SMART_HOME_ON = "включи"
    const val VERB_SMART_HOME_OFF = "выключи"

    /** The verb a command's phrase always starts with, right after the
     * start word — never typed, always derived from [value]. */
    fun smartHomeVerbFor(value: Boolean): String =
        if (value) VERB_SMART_HOME_ON else VERB_SMART_HOME_OFF

    /**
     * The full phrase for one SmartHomeCommand, e.g. "алиса включи свет на
     * кухне". Blank when the command has no free-text part configured.
     */
    fun smartHomePhraseFor(prefix: String, value: Boolean, freeText: String): String {
        val text = freeText.trim().lowercase()
        if (text.isEmpty()) return ""
        return "$prefix ${smartHomeVerbFor(value)} $text"
    }

    /** Uses the Yandex integration's current start word — see
     * SmartHomePrefs.getStartWord() — not always [PREFIX_SMART_HOME_YANDEX]. */
    fun smartHomeYandexPhraseFor(context: Context, value: Boolean, freeText: String): String =
        smartHomePhraseFor(SmartHomePrefs.getStartWord(context), value, freeText)
}
