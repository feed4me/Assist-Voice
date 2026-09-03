package com.nikolay.assistvoice

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView

/**
 * Lets the watch's side dial/bezel scroll a vertical ScrollView, in addition
 * to the usual touch drag.
 *
 * A rotary crown/bezel reports itself as [InputDevice.SOURCE_ROTARY_ENCODER]
 * and sends [MotionEvent.AXIS_SCROLL] values through [MotionEvent.ACTION_SCROLL]
 * — this axis exists specifically for that (added in API 26, which happens to
 * be this app's minSdk, so no version check is needed). Only the currently
 * *focused* view receives these events, which is why callers must also give
 * the view focus when its page becomes the visible one — see
 * MainActivity's ViewPager2 page-change callback.
 */
fun ScrollView.enableRotaryScroll() {
    isFocusable = true
    isFocusableInTouchMode = true
    setOnGenericMotionListener { view, event ->
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL) *
                ViewConfiguration.get(view.context).scaledVerticalScrollFactor
            (view as ScrollView).scrollBy(0, Math.round(delta))
            true
        } else {
            false
        }
    }
}

/**
 * Same idea as [ScrollView.enableRotaryScroll] above, for a RecyclerView —
 * used by PickerActivity's app/contact list. scrollBy on a RecyclerView moves
 * it exactly the same way it does a ScrollView, so the rotary handling itself
 * doesn't need to change, just which view type it's attached to.
 */
fun RecyclerView.enableRotaryScroll() {
    isFocusable = true
    isFocusableInTouchMode = true
    setOnGenericMotionListener { view, event ->
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL) *
                ViewConfiguration.get(view.context).scaledVerticalScrollFactor
            (view as RecyclerView).scrollBy(0, Math.round(delta))
            true
        } else {
            false
        }
    }
}

/**
 * Best-effort request for input focus, swallowing the (rare, non-fatal)
 * cases where a view can't take it — e.g. it isn't attached yet.
 */
fun View.requestRotaryFocus() {
    try {
        requestFocus()
    } catch (e: Exception) {
        // Not fatal: touch scrolling still works without focus, only the
        // rotary dial wouldn't reach this view until something else focuses it.
    }
}
