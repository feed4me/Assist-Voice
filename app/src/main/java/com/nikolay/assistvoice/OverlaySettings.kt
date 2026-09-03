package com.nikolay.assistvoice

import android.content.Context

/**
 * Appearance and placement of the on-screen microphone indicator.
 *
 * Stored in the same preferences file as the VAD settings on purpose: both are
 * live runtime tuning that the service re-reads through one listener, and both
 * must stay out of the slot preferences, where a change means the decode
 * grammar is stale and has to be rebuilt.
 */
object OverlaySettings {

    private const val KEY_ENABLED = "icon_enabled"
    private const val KEY_SIZE_DP = "icon_size_dp"
    private const val KEY_OFFSET_X_DP = "icon_offset_x_dp"
    private const val KEY_OFFSET_Y_DP = "icon_offset_y_dp"
    private const val KEY_COLOR_INIT = "icon_color_init"
    private const val KEY_COLOR_ACTIVE = "icon_color_active"

    const val DEFAULT_ENABLED = true

    const val DEFAULT_SIZE_DP = 28
    const val MIN_SIZE_DP = 12
    const val MAX_SIZE_DP = 64

    /**
     * Offsets are measured from the centre of the screen, matching the mental
     * model of axes crossing at the middle. X grows to the right, Y grows
     * DOWNWARD — Android's own screen convention, kept rather than inverted so
     * that what the slider says matches what the window manager does.
     */
    const val DEFAULT_OFFSET_X_DP = 0
    const val DEFAULT_OFFSET_Y_DP = -70
    const val MIN_OFFSET_DP = -120
    const val MAX_OFFSET_DP = 120

    /**
     * The five choices offered, stored by index rather than by ARGB value.
     *
     * An index survives a change to the palette; a raw colour int saved from an
     * older build would silently become an option that is no longer offered and
     * that no swatch can show as selected.
     */
    val PALETTE = intArrayOf(
        android.graphics.Color.RED,
        android.graphics.Color.BLUE,
        android.graphics.Color.GREEN,
        android.graphics.Color.WHITE,
        android.graphics.Color.BLACK
    )

    val PALETTE_NAMES = arrayOf("Красный", "Синий", "Зелёный", "Белый", "Чёрный")

    private const val INDEX_RED = 0
    private const val INDEX_BLACK = 4

    /** Matches the original hard-coded behaviour: black starting up, red recording. */
    const val DEFAULT_COLOR_INIT_INDEX = INDEX_BLACK
    const val DEFAULT_COLOR_ACTIVE_INDEX = INDEX_RED

    fun colorAt(index: Int): Int = PALETTE[index.coerceIn(0, PALETTE.size - 1)]

    data class Params(
        val enabled: Boolean,
        val sizeDp: Int,
        val offsetXDp: Int,
        val offsetYDp: Int,
        val colorInitIndex: Int,
        val colorActiveIndex: Int
    ) {
        val colorInit: Int get() = colorAt(colorInitIndex)
        val colorActive: Int get() = colorAt(colorActiveIndex)

        /**
         * True when the window itself has to be rebuilt. Colour is repainted in
         * place, so it deliberately isn't part of this — otherwise dragging
         * through the palette would tear the overlay down and put it back for
         * each swatch.
         */
        fun sameGeometryAs(other: Params): Boolean =
            enabled == other.enabled &&
                sizeDp == other.sizeDp &&
                offsetXDp == other.offsetXDp &&
                offsetYDp == other.offsetYDp
    }

    fun load(context: Context): Params {
        val prefs = prefs(context)
        return Params(
            enabled = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
            sizeDp = prefs.getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP)
                .coerceIn(MIN_SIZE_DP, MAX_SIZE_DP),
            offsetXDp = prefs.getInt(KEY_OFFSET_X_DP, DEFAULT_OFFSET_X_DP)
                .coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP),
            offsetYDp = prefs.getInt(KEY_OFFSET_Y_DP, DEFAULT_OFFSET_Y_DP)
                .coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP),
            colorInitIndex = prefs.getInt(KEY_COLOR_INIT, DEFAULT_COLOR_INIT_INDEX)
                .coerceIn(0, PALETTE.size - 1),
            colorActiveIndex = prefs.getInt(KEY_COLOR_ACTIVE, DEFAULT_COLOR_ACTIVE_INDEX)
                .coerceIn(0, PALETTE.size - 1),
        )
    }

    fun saveEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun saveSize(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_SIZE_DP, value.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)).apply()
    }

    fun saveOffsetX(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_OFFSET_X_DP, value.coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP)).apply()
    }

    fun saveOffsetY(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_OFFSET_Y_DP, value.coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP)).apply()
    }

    fun saveColorInit(context: Context, index: Int) {
        prefs(context).edit()
            .putInt(KEY_COLOR_INIT, index.coerceIn(0, PALETTE.size - 1)).apply()
    }

    fun saveColorActive(context: Context, index: Int) {
        prefs(context).edit()
            .putInt(KEY_COLOR_ACTIVE, index.coerceIn(0, PALETTE.size - 1)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(VadSettings.PREFS_NAME, Context.MODE_PRIVATE)
}
