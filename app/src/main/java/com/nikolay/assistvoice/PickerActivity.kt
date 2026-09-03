package com.nikolay.assistvoice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Generic full-screen list picker for app/contact selection in
 * SlotEditActivity.
 *
 * Why this exists: a Spinner's dropdown opens in its own system window,
 * entirely outside this Activity's view hierarchy. RotaryInput.kt's
 * enableRotaryScroll() is wired only on views inside our own layouts, so it
 * can never reach that popup — a Spinner here would mean the rotary crown
 * does nothing while the list is open. A picker screen in our own hierarchy
 * uses the same enableRotaryScroll() + requestRotaryFocus() pattern as
 * everywhere else in the app instead.
 *
 * Deliberately generic: takes a title and a flat list of labels, shows one
 * clickable row per label, and returns the tapped index. Both app and
 * contact pickers reuse this same screen with different label lists — see
 * the two registerForActivityResult launchers in SlotEditActivity.
 *
 * Rows are shown in a RecyclerView (PickerRowAdapter below) rather than a
 * ScrollView full of manually inflated views, since the install-apps list can
 * run into the hundreds on a phone-derived app catalogue — RecyclerView only
 * inflates enough rows to fill the screen and reuses them while scrolling.
 *
 * The title is the RecyclerView's own first item (PickerRowAdapter.TYPE_HEADER)
 * rather than a separate TextView above it, and the empty-list hint is a
 * FrameLayout overlay rather than a sibling that takes up layout space.
 */
class PickerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_SELECTED_INDEX = "selected_index"
        const val EXTRA_RESULT_INDEX = "result_index"

        fun intent(
            context: Context,
            title: String,
            labels: List<String>,
            selectedIndex: Int = -1
        ): Intent = Intent(context, PickerActivity::class.java)
            .putExtra(EXTRA_TITLE, title)
            .putStringArrayListExtra(EXTRA_LABELS, ArrayList(labels))
            .putExtra(EXTRA_SELECTED_INDEX, selectedIndex)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.item_picker)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val labels = intent.getStringArrayListExtra(EXTRA_LABELS) ?: arrayListOf()
        val selectedIndex = intent.getIntExtra(EXTRA_SELECTED_INDEX, -1)

        val recycler = findViewById<RecyclerView>(R.id.pickerRecycler)
        val emptyHint = findViewById<TextView>(R.id.pickerEmptyHint)
        val scrollIndicator = findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = PickerRowAdapter(title, labels, selectedIndex) { index ->
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_INDEX, index))
            finish()
        }
        recycler.enableRotaryScroll()
        scrollIndicator.attachTo(recycler)
        // Same reasoning as SlotEditActivity's own scrollRoot: nothing else
        // in this layout claims focus, so without this the framework would
        // hand default focus to the first focusable row instead, and the
        // crown would never reach the RecyclerView.
        recycler.post { recycler.requestRotaryFocus() }

        // The title (the RecyclerView's header item) stays visible either
        // way — only the "list is empty" hint toggles, as an overlay on top
        // of the (otherwise empty-of-rows) RecyclerView.
        emptyHint.visibility = if (labels.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        OwnAppForegroundTracker.onActivityStarted()
    }

    override fun onStop() {
        OwnAppForegroundTracker.onActivityStopped()
        super.onStop()
    }
}

/**
 * The title as a non-clickable header (position 0), then one row per label.
 * [selectedIndex] gets the accent colour, every other row gets the default
 * row-title colour — explicitly set on *every* bind for both cases, not
 * just when a row is selected, since a recycled row can otherwise keep
 * showing the previous label's colour it was last bound with.
 */
private class PickerRowAdapter(
    private val title: String,
    private val labels: List<String>,
    private val selectedIndex: Int,
    private val onRowClicked: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
    }

    private var defaultColor: Int = 0
    private var hasDefaultColor = false

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val headerTitle: TextView = itemView.findViewById(R.id.headerTitle)
    }

    class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val rowLabel: TextView = itemView.findViewById(R.id.rowLabel)
    }

    override fun getItemViewType(position: Int): Int =
        if (position == 0) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_picker_header, parent, false))
        } else {
            RowViewHolder(inflater.inflate(R.layout.item_picker_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            holder.headerTitle.text = title
            return
        }
        if (holder !is RowViewHolder) return

        if (!hasDefaultColor) {
            // Read once, from an actual inflated row, rather than hardcoding
            // a colour resource here — keeps this in sync with whatever
            // TextAppearance.App.RowTitle already sets in XML.
            defaultColor = holder.rowLabel.currentTextColor
            hasDefaultColor = true
        }

        val labelIndex = position - 1
        val label = labels[labelIndex]
        holder.rowLabel.text = label
        holder.rowLabel.setTextColor(
            if (labelIndex == selectedIndex) {
                holder.itemView.context.getColor(R.color.accent)
            } else {
                defaultColor
            }
        )
        holder.rowLabel.setOnClickListener { onRowClicked(labelIndex) }
    }

    override fun getItemCount(): Int = labels.size + 1
}
