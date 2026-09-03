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
}
