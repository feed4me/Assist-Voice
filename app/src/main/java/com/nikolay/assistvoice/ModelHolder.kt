package com.nikolay.assistvoice

import android.util.Log
import org.vosk.Model

/**
 * Single owner of the recognition Model, published so the settings screen can
 * borrow it instead of loading a second copy.
 *
 * VoiceAccessibilityService and MainActivity live in the same process, so when
 * the service is running its Model is already in memory — loading another one
 * just to look a word up wastes tens of megabytes on a watch.
 *
 * Every use of the Model and the close itself happen under the same lock, so
 * a close can never overlap a lookup. Callers that find nothing here (service
 * not running) load their own short-lived copy — see WakeWordDictionary.
 *
 * Lookups are microseconds; recognizer construction is the one genuinely slow
 * thing done under this lock, and it only happens when the grammar changes.
 */
object ModelHolder {

    private const val TAG = "ModelHolder"

    private val lock = Any()
    private var model: Model? = null

    fun publish(newModel: Model?) {
        synchronized(lock) {
            model = newModel
        }
    }

    /**
     * Runs [block] with the shared Model if there is one, returning its result;
     * returns null when no Model is currently published.
     *
     * Note the ambiguity if [block] itself can return null — every caller here
     * returns a non-null type, so a null result unambiguously means "no shared
     * model available, fall back".
     */
    fun <T> withModel(block: (Model) -> T): T? = synchronized(lock) {
        val current = model ?: return null
        return block(current)
    }

    fun closeAndClear() {
        synchronized(lock) {
            val current = model
            model = null
            if (current != null) {
                try {
                    current.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Model.close failed", e)
                }
            }
        }
    }
}
