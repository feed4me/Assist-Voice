package com.nikolay.assistvoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * The microphone pictogram: just the glyph, no fill behind it, so it reads
 * on top of an arbitrary watch face.
 *
 * Drawn on a Canvas rather than shipped as a vector drawable because the size
 * is user-configurable at runtime and every proportion here is expressed as a
 * fraction of the view's side — one drawable would have to be re-tinted and
 * re-scaled on every change instead of just redrawn.
 */
class MicIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State {
        /** Bringing the microphone up — foreground service, probe, recognizer. */
        INITIALIZING,

        /** Capture is running and audio is reaching the pipeline. */
        RECORDING
    }

    var state: State = State.INITIALIZING
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Glyph colour while the microphone is coming up. */
    var initColor: Int = Color.BLACK
        set(value) {
            if (field == value) return
            field = value
            if (state == State.INITIALIZING) invalidate()
        }

    /** Glyph colour while capture is running. */
    var activeColor: Int = Color.RED
        set(value) {
            if (field == value) return
            field = value
            if (state == State.RECORDING) invalidate()
        }

    fun setColors(init: Int, active: Int) {
        val changed = initColor != init || activeColor != active
        initColor = init
        activeColor = active
        if (changed) invalidate()
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val glyphColor = when (state) {
            State.INITIALIZING -> initColor
            State.RECORDING -> activeColor
        }
        fillPaint.color = glyphColor
        strokePaint.color = glyphColor
        strokePaint.strokeWidth = side * 0.055f

        // Capsule (the mic body).
        val capsuleWidth = side * 0.20f
        val capsuleHeight = side * 0.32f
        val capsuleTop = cy - side * 0.26f
        canvas.drawRoundRect(
            cx - capsuleWidth / 2f,
            capsuleTop,
            cx + capsuleWidth / 2f,
            capsuleTop + capsuleHeight,
            capsuleWidth / 2f,
            capsuleWidth / 2f,
            fillPaint
        )

        // Cradle arc under the capsule.
        val arcRadius = side * 0.20f
        val arcCenterY = cy + side * 0.02f
        canvas.drawArc(
            cx - arcRadius,
            arcCenterY - arcRadius,
            cx + arcRadius,
            arcCenterY + arcRadius,
            0f,
            180f,
            false,
            strokePaint
        )

        // Stem and base.
        val baseY = cy + side * 0.32f
        canvas.drawLine(cx, arcCenterY + arcRadius, cx, baseY, strokePaint)
        canvas.drawLine(cx - side * 0.13f, baseY, cx + side * 0.13f, baseY, strokePaint)
    }
}
