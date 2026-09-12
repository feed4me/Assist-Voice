package com.nikolay.assistvoice

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat

/**
 * Appends a small colored ✓/✗ marking whether a target has any voice
 * command configured — used by DeviceRowAdapter/GroupRowAdapter's subtitle
 * instead of a raw command count, which said nothing useful (a slot either
 * works or it doesn't) and crowded out room/group info on a device that
 * belongs to several groups.
 */
fun SpannableStringBuilder.appendCommandStatus(context: Context, hasCommands: Boolean) {
    val color = ContextCompat.getColor(context, if (hasCommands) R.color.success else R.color.danger)
    val start = length
    append(if (hasCommands) "✓" else "✗")
    setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
