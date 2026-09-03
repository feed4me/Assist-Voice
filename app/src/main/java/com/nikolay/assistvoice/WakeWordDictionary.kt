package com.nikolay.assistvoice

import android.content.Context
import android.util.Log
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.vosk.Model
import org.vosk.android.StorageService

/**
 * Checks whether the words of a candidate command exist in the Vosk model's
 * vocabulary, so a slot is rejected at save time rather than silently never
 * triggering.
 *
 * Vosk's vocabulary is closed and fixed, baked into the model's Gr.fst/HCLr.fst
 * graph. Nothing this app does can add a word to it, and a word that isn't
 * there will never be recognized — so the only honest options are to check, or
 * to let the person configure a command that quietly does nothing forever.
 *
 * The check matters beyond the slot itself: the decode grammar is built
 * *entirely* from these words, so an unknown one doesn't just break its own
 * slot, it makes grammar construction fail and drops the whole recognizer
 * into the slow open-vocabulary fallback.
 *
 * Reading the vocabulary out of the model directly is not an option here:
 * this model ships without `graph/words.txt`, and the FSTs store only numeric
 * ids whose symbol names live in exactly that missing file. `vosk_model_find_word`
 * — which answers "is this word present" against the model's own symbol table —
 * is the only available route.
 *
 * ## Model lifetime
 *
 * Two sources, in order:
 *
 *  1. The Model VoiceAccessibilityService already has loaded, borrowed through
 *     ModelHolder under its lock. Same process, so this costs nothing and is
 *     instant.
 *  2. Otherwise a Model loaded here and closed again in the same call.
 *
 * ModelHolder's lock makes case 1 safe against a concurrent close, and in
 * case 2 the Model never escapes this function, so there is nothing to race
 * with either.
 *
 * The trade is that case 2 pays a full model load (seconds, off a watch's
 * flash) per save with a changed word. That's deliberate: it's a rare,
 * explicitly user-initiated action with a progress state already shown, and
 * the memory is wanted back more than the seconds are.
 */
object WakeWordDictionary {

    private const val TAG = "WakeWordDictionary"

    /** Outcome of a check — three states, because "couldn't check" must not look like "rejected". */
    sealed class Result {
        /** Every word is present in the model. */
        object Ok : Result()

        /** [word] is not in the model's vocabulary. */
        data class Missing(val word: String) : Result()

        /** The check couldn't be performed (no model, native binding missing, lookup threw). */
        object Unavailable : Result()
    }

    /**
     * The missing native declaration. Bound against the same "vosk" shared
     * library vosk-android's own LibVosk registers against (see LibVosk.java in
     * the vosk-android source — `Native.register(LibVosk.class, "vosk")`), so
     * this reuses the .so already loaded for recognition rather than a second
     * copy. vosk-android simply doesn't declare this function, though the C
     * library exports it and Vosk's Python bindings expose it.
     */
    private interface NativeExtras : com.sun.jna.Library {
        fun vosk_model_find_word(model: Pointer?, word: String): Int
    }

    private val nativeExtras: NativeExtras? = try {
        Native.load("vosk", NativeExtras::class.java) as NativeExtras
    } catch (e: Throwable) {
        // Shouldn't happen — vosk-android already requires this library to load
        // for recognition to work — but degrade to "unavailable" rather than
        // crashing the settings screen.
        Log.e(TAG, "Failed to bind vosk_model_find_word", e)
        null
    }

    /**
     * Checks every word of [phrase] (split on whitespace) against the model.
     *
     * Pass the whole command, prefix included — the prefixes are ordinary
     * dictionary words and are what the grammar will actually contain, so
     * checking them here is what makes "the grammar will build" the thing being
     * verified, rather than just "the person's own word exists".
     *
     * Blocks on a model load in the worst case, so never call this from the
     * main thread. SlotsAdapter runs it on a background executor.
     */
    fun checkPhrase(context: Context, phrase: String): Result {
        val extras = nativeExtras ?: return Result.Unavailable

        val words = phrase.trim().lowercase()
            .split(' ', '\t', '\n')
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return Result.Unavailable

        // 1. Borrow the service's model if it has one. withModel returns null
        //    only when nothing is published — Result is never null itself.
        ModelHolder.withModel { sharedModel ->
            lookupAll(extras, sharedModel, words)
        }?.let { return it }

        // 2. Load our own, use it, drop it.
        var ownModel: Model? = null
        return try {
            // sync() is the blocking counterpart of the unpack() callback API
            // the service uses; it re-copies only when the model's uuid file
            // changes, so this is a cheap no-op after first run.
            val path = StorageService.sync(context.applicationContext, "model-ru", "model")
            val loaded = Model(path)
            ownModel = loaded
            lookupAll(extras, loaded, words)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model for word-checking", e)
            Result.Unavailable
        } finally {
            try {
                ownModel?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Model.close failed", e)
            }
        }
    }

    private fun lookupAll(
        extras: NativeExtras,
        model: Model,
        words: List<String>
    ): Result = try {
        var missing: String? = null
        for (word in words) {
            if (extras.vosk_model_find_word(model.pointer, word) == -1) {
                missing = word
                break
            }
        }
        if (missing == null) Result.Ok else Result.Missing(missing)
    } catch (e: Exception) {
        Log.e(TAG, "Word lookup failed for $words", e)
        Result.Unavailable
    }
}
