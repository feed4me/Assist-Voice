package com.nikolay.assistvoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.viewpager2.widget.ViewPager2

/**
 * A row of small dots at the bottom of the screen showing how many
 * ViewPager2 pages there are and which one is current — the only paging cue
 * this app had before was finding out by swiping. No extra library:
 * the whole thing is a couple of drawCircle calls, which is simpler and far
 * lighter than pulling in Material's TabLayout for a watch app that has
 * otherwise stayed dependency-light on purpose (see README/build.gradle.kts).
 *
 * Purely decorative — clicks pass through to whatever is behind it (see its
 * use in activity_main.xml, where it's overlaid on the pager without
 * intercepting touches).
 */
class PageDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var pageCount = 0
    private var position = 0
    private var positionOffset = 0f

    private val density = context.resources.displayMetrics.density
    private val dotRadius = 2.6f * density
    private val activeDotRadius = 3.4f * density
    private val spacing = 10f * density

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        inactivePaint.color = context.getColor(R.color.divider_strong)
        activePaint.color = context.getColor(R.color.accent)
    }

    fun setPageCount(count: Int) {
        if (pageCount == count) return
        pageCount = count
        invalidate()
    }

    private fun setProgress(position: Int, positionOffset: Float) {
        this.position = position
        this.positionOffset = positionOffset
        invalidate()
    }

    /** Attaches to a ViewPager2, keeping the dots in sync with swipes. */
    fun attachTo(pager: ViewPager2) {
        setPageCount(pager.adapter?.itemCount ?: 0)
        setProgress(pager.currentItem, 0f)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                setProgress(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                setProgress(position, 0f)
            }
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (activeDotRadius * 2).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        if (pageCount <= 1) return

        val totalWidth = (pageCount - 1) * spacing
        val startX = width / 2f - totalWidth / 2f
        val cy = height / 2f

        for (i in 0 until pageCount) {
            val cx = startX + i * spacing
            // How "active" this dot is right now, 0..1 — smoothly hands off
            // from one dot to the next while a swipe is in progress instead
            // of jumping only once the page fully settles.
            val activeness = (1f - Math.abs(i - (position + positionOffset))).coerceIn(0f, 1f)
            val radius = dotRadius + (activeDotRadius - dotRadius) * activeness
            if (activeness > 0f) {
                activePaint.alpha = (255 * activeness).toInt()
                canvas.drawCircle(cx, cy, radius, activePaint)
            }
            if (activeness < 1f) {
                inactivePaint.alpha = (255 * (1f - activeness)).toInt()
                canvas.drawCircle(cx, cy, dotRadius, inactivePaint)
            }
        }
    }
}
