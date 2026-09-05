package com.nikolay.assistvoice

/**
 * Builds the spoken command phrase for a slot.
 *
 * Every command is deliberately two words: a fixed prefix determined by the
 * action type, then the slot's own word. This is the main defence against
 * false triggers.
 *
 * Why two words works: a grammar-constrained Vosk recognizer is a closed-set
 * classifier — presented with any sound at all, the decoder must pick the
 * nearest entry it knows. With a single-word command, acoustically similar
 * noise lands on it almost every time («але», footsteps). Requiring two
 * specific words *in sequence*, each with a plausible duration, is a far
 * harder target for noise to hit by accident, and costs nothing: Vosk's
 * grammar accepts whole phrases as entries, so the decode graph stays a loop
 * over a handful of alternatives rather than a free word loop.
 *
 * Both prefixes were verified to exist in vosk-model-small-ru-0.22's
 * vocabulary (via vosk_model_find_word). Do not swap them for synonyms
 * casually — «запусти», for one, is NOT in this model and would silently
 * break grammar construction. Known-present alternatives, if ever needed:
 * «открыть», «включи», «позвонить», «набери».
 */
object VoicePhrases {

    const val PREFIX_LAUNCH_APP = "открой"
    const val PREFIX_CALL = "позвони"

    /**
     * Extra grammar entries added purely to give the decoder somewhere else to
     * land, phonetically close to [PREFIX_LAUNCH_APP]/[PREFIX_CALL], when the
     * audio isn't actually a clean command.
     *
     * Without these, a grammar-constrained decoder's only choices for
     * ambiguous audio are the exact two-word command phrases or the generic
     * `[unk]` catch-all — and it must pick *something*. Audio that merely
     * resembles a prefix can snap onto a real command this way, and since
     * VoiceAccessibilityService checks partial results too, it can do so
     * before the person has finished saying the second word.
     *
     * Entries are morphological siblings or near-rhymes of each prefix, so
     * they're plausible landing spots for audio that only resembles the real
     * word. Confirmed present in vosk-model-small-ru-0.22's vocabulary:
     * «открыть», «позвонить». The rest are not individually re-verified.
     * VoiceAccessibilityService.ensureRecognizer() tries phrases+decoys first
     * and falls back cleanly to the plain phrase grammar if construction is
     * rejected (e.g. because one of these turns out to be absent from the
     * model), so a wrong guess here degrades gracefully rather than breaking
     * recognition. Spot-check any addition with WakeWordDictionary.checkPhrase
     * before relying on it.
     */
    val DECOY_WORDS_LAUNCH_APP = listOf(
        "открыть", "открыл", "открою", "покрой", "закрой", "накрой", "помой"
    )
    val DECOY_WORDS_CALL = listOf(
        "позвонить", "позвонил", "позвоню", "позволь", "похвали", "победи", "догони"
    )

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
     * don't go through phraseFor(). They are folded into every grammar build
     * unconditionally (see VoiceAccessibilityService.ensureRecognizer), never
     * exposed as a slot type, and not configurable through any settings
     * screen.
     */
    const val PHRASE_FLASHLIGHT_ON = "включи фонарик"
    const val PHRASE_FLASHLIGHT_OFF = "выключи фонарик"

    val FLASHLIGHT_PHRASES = listOf(PHRASE_FLASHLIGHT_ON, PHRASE_FLASHLIGHT_OFF)

    // ------------------------------------------------------------------
    // YandexMusicWatch playback control — see YandexMusicController.
    // ------------------------------------------------------------------

    /**
     * Same kind of fixed, non-slot reserved commands as the flashlight
     * phrases above — folded into every grammar build unconditionally (see
     * VoiceAccessibilityService.ensureRecognizer), never exposed as a slot
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

    val MUSIC_PHRASES = listOf(
        PHRASE_MUSIC_WAVE, PHRASE_MUSIC_LIKES, PHRASE_MUSIC_ON,
        PHRASE_MUSIC_OFF, PHRASE_MUSIC_NEXT, PHRASE_MUSIC_PREV
    )

    /**
     * Decoy words for the "включи"/"выключи" prefixes shared by the phrases
     * above (and by PHRASE_FLASHLIGHT_ON/OFF) — same purpose as
     * DECOY_WORDS_LAUNCH_APP/CALL: without these, ambiguous audio that only
     * resembles one of these prefixes has nowhere cheap to land, so a
     * closed grammar tends to force it onto a full real command instead
     * (this is why plain "включи", said alone, used to snap onto "включи
     * фонарик" — there was no decoy list for this prefix family at all).
     * Includes the bare prefixes themselves, unlike the open/call decoy
     * lists, specifically to give a truncated "включи"/"выключи" with
     * nothing (or noise) after it an exact one-word match instead of
     * forcing the decoder to invent a second word from that noise.
     * Not individually re-verified against vosk-model-small-ru-0.22's
     * vocabulary (see VoicePhrases' class doc) — a wrong guess here just
     * falls back to the decoy-free grammar, same safety net as the others.
     */
    val DECOY_WORDS_MUSIC = listOf(
        "включи", "включить", "включил", "включу",
        "выключи", "выключить", "выключил", "выключу"
    )
}
