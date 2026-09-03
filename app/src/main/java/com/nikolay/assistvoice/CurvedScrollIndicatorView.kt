package com.nikolay.assistvoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min

/**
 * Draws its own curved scroll-position indicator along the right edge of a
 * round screen, instead of relying on android.view.View's built-in "round
 * scrollbar" mechanism (ScrollView/RecyclerView's default vertical scrollbar,
 * which on a round Wear-type device is supposed to render as a curved arc via
 * a private framework class). That mechanism does not draw reliably on this
 * Huawei/EMUI build, so this view owns the drawing outright instead.
 *
 * It reads scroll range/extent/offset from whatever it's attached to —
 * ScrollView and RecyclerView both implement the same three View methods
 * (computeVerticalScrollRange/Extent/Offset), so one implementation covers
 * every scrolling screen in the app — and paints a simple track+thumb arc on
 * the right edge, refreshed on every scroll and layout event.
 *
 * Usage: place as the LAST child of a FrameLayout that also contains the
 * scrolling view, both match_parent, so this draws on top; give the
 * scrolling view android:scrollbars="none" so its own (unreliable)
 * scrollbar never competes for the same space; then call attachTo() once
 * the scrolling view exists (see SlotsAdapter's ViewHolders, PickerActivity,
 * SlotEditActivity). A plain, non-clickable View doesn't consume touch
 * events, so touches pass straight through to the scrolling view beneath
 * it — no special handling needed for that.
 */
class CurvedScrollIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        // Loosely matches the platform's own RoundScrollbarRenderer constants
        // (frameworks/base/core/java/android/view/RoundScrollbarRenderer.java)
        // just so the look is familiar — none of these need to match exactly,
        // since this view no longer defers to that class at all.
        private const val ANGLE_RANGE_DEG = 28.8f
        private const val MIN_SWEEP_DEG = 3.1f
        private const val MAX_SWEEP_DEG = 26.3f

        /**
         * computeVerticalScrollRange/Extent/Offset() are protected on View —
         * part of the SDK, meant to be overridden, just not meant to be
         * called from outside the class — so reflection is needed to read
         * them from here, same reasoning as the awakenScrollBars() reflection
         * used elsewhere in this app. These are ordinary (if protected) SDK
         * methods, not hidden/non-SDK internals, so they aren't subject to
         * the platform's hidden-API restrictions the way a private field
         * like RoundScrollbarRenderer's would be.
         */
        private fun scrollMethod(name: String) = try {
            View::class.java.getDeclaredMethod(name).apply { isAccessible = true }
        } catch (e: Exception) {
            null
        }

        private val rangeMethod = scrollMethod("computeVerticalScrollRange")
        private val extentMethod = scrollMethod("computeVerticalScrollExtent")
        private val offsetMethod = scrollMethod("computeVerticalScrollOffset")

        private fun invokeInt(method: java.lang.reflect.Method?, target: View): Int = try {
            (method?.invoke(target) as? Int) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private val density = resources.displayMetrics.density
    private val strokeWidthPx = 3f * density
    private val outerPaddingPx = 4f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokeWidthPx
        color = Color.argb(70, 255, 255, 255)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokeWidthPx
        color = Color.WHITE
    }

    private val circleBounds = RectF()
    private var target: View? = null

    /** Call once, after the view being tracked exists (e.g. right after findViewById). */
    fun attachTo(scrollingView: View) {
        target = scrollingView

        if (scrollingView is RecyclerView) {
            scrollingView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = invalidate()
            })
        } else {
            // Covers ScrollView (and any other plain View that scrolls via
            // View.scrollTo/scrollBy, which is what fires this listener) —
            // it's registered on the tracked view's own ViewTreeObserver, not
            // this indicator's, since that's what actually changes scrollY.
            scrollingView.viewTreeObserver.addOnScrollChangedListener { invalidate() }
        }
        // Catches everything else that can change the scrollable range
        // without a scroll event of its own — content loading in asynchronously
        // (e.g. the picker's data arriving), a rotation, the first layout pass.
        scrollingView.viewTreeObserver.addOnGlobalLayoutListener { invalidate() }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = min(w, h) / 2f - outerPaddingPx
        val cx = w / 2f
        val cy = h / 2f
        circleBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val view = target ?: return

        val range = invokeInt(rangeMethod, view).toFloat()
        val extent = invokeInt(extentMethod, view).toFloat()
        val offset = invokeInt(offsetMethod, view).toFloat()

        // Nothing to scroll — draw nothing, same as the platform would.
        if (range <= 0f || extent <= 0f || range <= extent) return

        canvas.drawArc(circleBounds, -ANGLE_RANGE_DEG / 2f, ANGLE_RANGE_DEG, false, trackPaint)

        val sweep = (extent / range * ANGLE_RANGE_DEG).coerceIn(MIN_SWEEP_DEG, MAX_SWEEP_DEG)
        val maxScroll = (range - extent).coerceAtLeast(1f)
        val start = -ANGLE_RANGE_DEG / 2f +
            (offset / maxScroll).coerceIn(0f, 1f) * (ANGLE_RANGE_DEG - sweep)

        canvas.drawArc(circleBounds, start, sweep, false, thumbPaint)
    }
}
