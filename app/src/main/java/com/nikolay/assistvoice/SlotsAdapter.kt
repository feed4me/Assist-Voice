package com.nikolay.assistvoice

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Text plus a simple ok/not-ok flag for one status pill on the info page —
 * `ok` drives that pill's colour (green vs warm/amber), `text` is its
 * exact copy, both assembled by MainActivity.buildStatus().
 */
data class StatusItem(val text: String, val ok: Boolean)

/**
 * All the info page's status pills. Accessibility gets checked separately
 * from the four permissions because it can't be requested programmatically
 * (see MainActivity's class doc) — the permissions are all a runtime prompt
 * away, accessibility is a manual trip to system Settings, so calling that
 * one out distinctly is worth the extra field even though every pill renders
 * identically.
 */
data class ServiceStatus(
    val accessibility: StatusItem,
    val microphone: StatusItem,
    val contacts: StatusItem,
    val phone: StatusItem,
    val overlay: StatusItem
)

/**
 * Backs the ViewPager2 on MainActivity: a fixed sequence of 5 pages — info,
 * the slot list, microphone-gate (VAD) tuning, mic-icon appearance, and a
 * closing support/social page.
 *
 * The slot list itself is one page (see SlotListViewHolder) showing all
 * slots at a glance; tapping a row or "Добавить слот" opens SlotEditActivity,
 * a separate full-screen editor, outside the pager entirely.
 */
class SlotsAdapter(
    private val getStatusText: () -> ServiceStatus,
    private val getInstalledApps: () -> List<InstalledApp>,
    private val getContacts: () -> List<Contact>,
    /** Called after a mutation made directly from the list (currently just
     * the trash icon on a row) — anything that happens inside
     * SlotEditActivity instead reaches MainActivity via onResume(). */
    private val onSlotsChanged: () -> Unit,
    /** Called by the "Синхронизировать приложения и контакты" button — see
     * MainActivity.syncPickerData(). */
    private val onSyncPickerData: () -> Unit,
    /** Current state of the update check/download/install cycle — owned by
     * MainActivity (see its updateStatus field), read fresh on every bind. */
    private val getUpdateStatus: () -> UpdateStatus,
    /** Called by the info page's single update button. MainActivity decides
     * what that tap means from the current UpdateStatus — check, download,
     * or re-prompt install — since it's the one holding that state. */
    private val onUpdateButtonClicked: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_INFO = 0
        private const val TYPE_SLOT_LIST = 1
        private const val TYPE_VAD = 2
        private const val TYPE_ICON = 3
        private const val TYPE_SUPPORT = 4

        private const val PAGE_COUNT = 5

        /** How often the VAD page re-reads the live level. */
        private const val VAD_TICK_MS = 150L

        private const val REPO_URL = "https://github.com/feed4me/Assist-Voice"
        private const val DONATE_URL = "https://pay.cloudtips.ru/p/16bc29c7"
        private const val TELEGRAM_URL = "https://t.me/bynikolay"
        private const val MAX_URL = "https://max.ru/channel_bynikolay"

        private val mainHandler = Handler(Looper.getMainLooper())
    }

    private var slots: List<VoiceSlot> = emptyList()

    private var appList: List<InstalledApp> = emptyList()
    private var contactList: List<Contact> = emptyList()

    /** Called when the installed-app / contact lists have finished loading —
     * the slot list needs appList to turn a LAUNCH_APP slot's package name
     * into a readable app label. */
    fun onPickerDataChanged(apps: List<InstalledApp>, contacts: List<Contact>) {
        appList = apps
        contactList = contacts
        notifyItemChanged(TYPE_SLOT_LIST)
    }

    fun submitSlots(newSlots: List<VoiceSlot>) {
        slots = newSlots
        notifyItemChanged(TYPE_SLOT_LIST)
    }

    /** Call when only the status text (permissions, etc.) may have changed. */
    fun refreshInfoPage() {
        notifyItemChanged(TYPE_INFO)
    }

    override fun getItemCount(): Int = PAGE_COUNT

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_INFO -> InfoViewHolder(inflater.inflate(R.layout.item_info, parent, false))
            TYPE_SLOT_LIST -> SlotListViewHolder(inflater.inflate(R.layout.item_slot_list, parent, false))
            TYPE_VAD -> VadViewHolder(inflater.inflate(R.layout.item_vad, parent, false))
            TYPE_ICON -> IconViewHolder(inflater.inflate(R.layout.item_icon, parent, false))
            else -> SupportViewHolder(inflater.inflate(R.layout.item_support, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is InfoViewHolder -> holder.bind(getStatusText(), getUpdateStatus())
            is SlotListViewHolder -> holder.bind(slots)
            is VadViewHolder -> holder.bind()
            is IconViewHolder -> holder.bind()
            is SupportViewHolder -> Unit // Static content, nothing to bind.
        }
    }

    /**
     * The VAD page polls a live microphone level, which must not keep running
     * once the person has swiped away from it. RecyclerView holders have no
     * lifecycle of their own, so attach/detach is the hook: ViewPager2 keeps
     * neighbouring pages attached, which is fine — the ticker is a text update
     * every VAD_TICK_MS, not the thing measuring anything.
     */
    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is VadViewHolder) holder.startTicking()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (holder is VadViewHolder) holder.stopTicking()
    }

    inner class InfoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // itemView is the FrameLayout wrapper (see item_info.xml) —
        // scrollRoot is the actual ScrollView, a child of it.
        private val scrollRoot: ScrollView = itemView.findViewById(R.id.scrollRoot)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)
        private val micStatusText: TextView = itemView.findViewById(R.id.micStatusText)
        private val contactsStatusText: TextView = itemView.findViewById(R.id.contactsStatusText)
        private val phoneStatusText: TextView = itemView.findViewById(R.id.phoneStatusText)
        private val overlayStatusText: TextView = itemView.findViewById(R.id.overlayStatusText)
        private val allGrantedText: TextView = itemView.findViewById(R.id.allGrantedText)
        private val repoQrImage: ImageView = itemView.findViewById(R.id.repoQrImage)
        private val appVersionText: TextView = itemView.findViewById(R.id.appVersionText)
        private val checkUpdatesButton: Button = itemView.findViewById(R.id.checkUpdatesButton)
        private val updateStatusText: TextView = itemView.findViewById(R.id.updateStatusText)

        init {
            scrollRoot.enableRotaryScroll()
            itemView.findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator).attachTo(scrollRoot)
            appVersionText.text = "Версия ${BuildConfig.VERSION_NAME}"
            checkUpdatesButton.setOnClickListener { onUpdateButtonClicked() }

            val sizePx = (120 * itemView.resources.displayMetrics.density).toInt()
            repoQrImage.setImageBitmap(QrCode.render(REPO_URL, sizePx))
        }

        /**
         * Each pill is only shown when its own check is actually failing —
         * a pill for something already fine was just reassuring clutter on
         * a screen this small. Once nothing is missing, all of them are
         * hidden and allGrantedText takes their place instead of the page
         * going empty.
         */
        fun bind(status: ServiceStatus, updateStatus: UpdateStatus) {
            val items = listOf(
                statusText to status.accessibility,
                micStatusText to status.microphone,
                contactsStatusText to status.contacts,
                phoneStatusText to status.phone,
                overlayStatusText to status.overlay
            )
            val allOk = items.all { it.second.ok }

            for ((view, item) in items) {
                if (allOk || item.ok) {
                    view.visibility = View.GONE
                } else {
                    paint(view, item)
                    view.visibility = View.VISIBLE
                }
            }
            allGrantedText.visibility = if (allOk) View.VISIBLE else View.GONE
            bindUpdateSection(updateStatus)
        }

        private fun paint(view: TextView, item: StatusItem) {
            view.text = item.text
            val context = itemView.context
            if (item.ok) {
                view.setBackgroundResource(R.drawable.bg_status_ok)
                view.setTextColor(context.getColor(R.color.status_ok_text))
            } else {
                view.setBackgroundResource(R.drawable.bg_status_warn)
                view.setTextColor(context.getColor(R.color.status_warn_text))
            }
        }

        private fun bindUpdateSection(status: UpdateStatus) {
            when (status) {
                is UpdateStatus.Idle -> {
                    checkUpdatesButton.isEnabled = true
                    checkUpdatesButton.text = "Проверить обновления"
                    updateStatusText.visibility = View.GONE
                }
                is UpdateStatus.Checking -> {
                    checkUpdatesButton.isEnabled = false
                    checkUpdatesButton.text = "Проверяю…"
                    updateStatusText.visibility = View.GONE
                }
                is UpdateStatus.UpToDate -> {
                    checkUpdatesButton.isEnabled = true
                    checkUpdatesButton.text = "Проверить снова"
                    updateStatusText.text = "Установлена последняя версия"
                    updateStatusText.visibility = View.VISIBLE
                }
                is UpdateStatus.Available -> {
                    checkUpdatesButton.isEnabled = true
                    checkUpdatesButton.text = "Скачать v${status.info.versionName}"
                    updateStatusText.text = "Доступно обновление: v${status.info.versionName}"
                    updateStatusText.visibility = View.VISIBLE
                }
                is UpdateStatus.Downloading -> {
                    checkUpdatesButton.isEnabled = false
                    checkUpdatesButton.text = "Скачиваю…"
                    updateStatusText.text = if (status.percent >= 0) {
                        "Скачивание: ${status.percent}%"
                    } else {
                        "Скачивание…"
                    }
                    updateStatusText.visibility = View.VISIBLE
                }
                is UpdateStatus.ReadyToInstall -> {
                    checkUpdatesButton.isEnabled = true
                    checkUpdatesButton.text = "Установить v${status.info.versionName}"
                    updateStatusText.text = "Обновление скачано — нажми «Установить»"
                    updateStatusText.visibility = View.VISIBLE
                }
                is UpdateStatus.Installing -> {
                    checkUpdatesButton.isEnabled = false
                    checkUpdatesButton.text = "Устанавливаю…"
                    updateStatusText.text = status.message
                    updateStatusText.visibility = View.VISIBLE
                }
                is UpdateStatus.Error -> {
                    checkUpdatesButton.isEnabled = true
                    checkUpdatesButton.text = "Повторить проверку"
                    updateStatusText.text = status.message
                    updateStatusText.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * The slot list: an "Добавить слот" button, then one row per saved slot
     * — type, target (app label or contact name), the phrase that triggers
     * it, and a trash icon. Rows are plain inflated views appended to a
     * LinearLayout rather than a nested RecyclerView: slot counts on a watch
     * are small (a handful at most), so the extra recycling machinery isn't
     * worth the complexity of a RecyclerView-inside-a-RecyclerView-page.
     */
    inner class SlotListViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // itemView is the FrameLayout wrapper (see item_slot_list.xml) —
        // scrollRoot is the actual ScrollView, a child of it.
        private val scrollRoot: ScrollView = itemView.findViewById(R.id.scrollRoot)
        private val addButton: Button = itemView.findViewById(R.id.addSlotButton)
        private val syncButton: Button = itemView.findViewById(R.id.syncPickerDataButton)
        private val rowsContainer: LinearLayout = itemView.findViewById(R.id.slotRowsContainer)
        private val emptyHint: View = itemView.findViewById(R.id.emptyHint)

        init {
            scrollRoot.enableRotaryScroll()
            itemView.findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator).attachTo(scrollRoot)

            addButton.setOnClickListener {
                val context = itemView.context
                val updated = TargetAppPrefs.addEmptySlot(context)
                onSlotsChanged()
                val newSlotId = updated.lastOrNull()?.id
                if (newSlotId != null) {
                    context.startActivity(SlotEditActivity.intent(context, newSlotId))
                }
            }

            syncButton.setOnClickListener { onSyncPickerData() }
        }

        fun bind(slots: List<VoiceSlot>) {
            val context = itemView.context
            val inflater = LayoutInflater.from(context)

            rowsContainer.removeAllViews()
            emptyHint.visibility = if (slots.isEmpty()) View.VISIBLE else View.GONE

            for (slot in slots) {
                val row = inflater.inflate(R.layout.item_slot_row, rowsContainer, false)

                val rowBody: View = row.findViewById(R.id.rowBody)
                val rowBadge: TextView = row.findViewById(R.id.rowBadge)
                val rowTarget: TextView = row.findViewById(R.id.rowTarget)
                val rowPhrase: TextView = row.findViewById(R.id.rowPhrase)
                val rowDelete: ImageView = row.findViewById(R.id.rowDelete)

                rowBadge.text = when (slot.actionType) {
                    SlotActionType.LAUNCH_APP -> "Прил."
                    SlotActionType.CALL -> "Звонок"
                }
                rowTarget.text = targetLabel(slot)
                rowPhrase.text = if (slot.wakeWord.isBlank()) {
                    "слово не задано"
                } else {
                    "«${VoicePhrases.phraseFor(slot)}»"
                }
                row.alpha = if (slot.enabled) 1f else 0.45f

                rowBody.setOnClickListener {
                    context.startActivity(SlotEditActivity.intent(context, slot.id))
                }
                rowDelete.setOnClickListener {
                    TargetAppPrefs.deleteSlot(context, slot.id)
                    onSlotsChanged()
                }

                rowsContainer.addView(row)
            }
        }

        private fun targetLabel(slot: VoiceSlot): String = when (slot.actionType) {
            SlotActionType.LAUNCH_APP -> {
                val label = appList.firstOrNull { it.packageName == slot.packageName }?.label
                label ?: slot.packageName.ifBlank { "приложение не выбрано" }
            }
            SlotActionType.CALL -> {
                slot.contactName.ifBlank { slot.phoneNumber.ifBlank { "контакт не выбран" } }
            }
        }
    }

    /**
     * Microphone gate tuning.
     *
     * The live level readout is the point of this page. The gate uses an
     * absolute threshold rather than one that adapts to the room, because the
     * whole premise is that a command is spoken with the watch at the mouth and
     * is therefore far louder than anything ambient — but "far louder" is a
     * number that depends on this specific watch's microphone, so it has to be
     * measured rather than assumed. Speak a command, read the peak, then look
     * at the background level, and put the threshold between the two.
     */
    inner class VadViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // itemView is the FrameLayout wrapper (see item_vad.xml) —
        // scrollRoot is the actual ScrollView, a child of it.
        private val scrollRoot: ScrollView = itemView.findViewById(R.id.scrollRoot)
        private val levelText: TextView = itemView.findViewById(R.id.vadLevelText)
        private val peakText: TextView = itemView.findViewById(R.id.vadPeakText)
        private val stateText: TextView = itemView.findViewById(R.id.vadStateText)
        private val resetPeakButton: Button = itemView.findViewById(R.id.vadResetPeakButton)

        private val thresholdSeek: SeekBar = itemView.findViewById(R.id.vadThresholdSeek)
        private val hangoverSeek: SeekBar = itemView.findViewById(R.id.vadHangoverSeek)
        private val prerollSeek: SeekBar = itemView.findViewById(R.id.vadPrerollSeek)

        private val thresholdLabel: TextView = itemView.findViewById(R.id.vadThresholdLabel)
        private val hangoverLabel: TextView = itemView.findViewById(R.id.vadHangoverLabel)
        private val prerollLabel: TextView = itemView.findViewById(R.id.vadPrerollLabel)

        private val screenHoldInput: EditText = itemView.findViewById(R.id.screenHoldInput)

        /** Guards the TextWatcher below from re-saving bind()'s own setText(). */
        private var suppressScreenHoldCallback = false

        // SeekBar.min is API 26+ and this app's minSdk allows it, but the
        // ranges here don't start at zero anyway, so progress is kept as a
        // plain step count and converted in one place instead.
        private val thresholdStep = 100
        private val hangoverStep = 100
        private val prerollStep = 50

        private var ticking = false

        private val tick = object : Runnable {
            override fun run() {
                if (!ticking) return
                render()
                mainHandler.postDelayed(this, VAD_TICK_MS)
            }
        }

        init {
            scrollRoot.enableRotaryScroll()
            itemView.findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator).attachTo(scrollRoot)

            thresholdSeek.max =
                (VadSettings.MAX_THRESHOLD_RMS - VadSettings.MIN_THRESHOLD_RMS) / thresholdStep
            hangoverSeek.max =
                (VadSettings.MAX_HANGOVER_MS - VadSettings.MIN_HANGOVER_MS) / hangoverStep
            prerollSeek.max =
                (VadSettings.MAX_PREROLL_MS - VadSettings.MIN_PREROLL_MS) / prerollStep

            resetPeakButton.setOnClickListener { VadMonitor.resetPeak() }

            thresholdSeek.setOnSeekBarChangeListener(
                onProgress { progress, fromUser ->
                    val value = VadSettings.MIN_THRESHOLD_RMS + progress * thresholdStep
                    thresholdLabel.text = "Порог: $value"
                    // Only persist real drags. Writing on the programmatic
                    // setProgress in bind() would fire the service's prefs
                    // listener on every page bind for no reason.
                    if (fromUser) VadSettings.saveThreshold(itemView.context, value)
                }
            )
            hangoverSeek.setOnSeekBarChangeListener(
                onProgress { progress, fromUser ->
                    val value = VadSettings.MIN_HANGOVER_MS + progress * hangoverStep
                    hangoverLabel.text = "Хвост: $value мс"
                    if (fromUser) VadSettings.saveHangover(itemView.context, value)
                }
            )
            prerollSeek.setOnSeekBarChangeListener(
                onProgress { progress, fromUser ->
                    val value = VadSettings.MIN_PREROLL_MS + progress * prerollStep
                    prerollLabel.text = "Преролл: $value мс"
                    if (fromUser) VadSettings.savePreroll(itemView.context, value)
                }
            )

            screenHoldInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(cs: CharSequence?, s: Int, c: Int, a: Int) {}
                override fun onTextChanged(cs: CharSequence?, s: Int, b: Int, c: Int) {}
                override fun afterTextChanged(editable: Editable?) {
                    if (suppressScreenHoldCallback) return
                    // Left blank mid-edit (e.g. selecting all before typing a
                    // new value) intentionally saves nothing rather than
                    // coercing an empty field to 0.
                    val value = editable?.toString()?.toIntOrNull() ?: return
                    VadSettings.saveScreenHoldSeconds(itemView.context, value)
                }
            })
        }

        private fun onProgress(block: (Int, Boolean) -> Unit) =
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) =
                    block(progress, fromUser)

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }

        fun bind() {
            val params = VadSettings.load(itemView.context)
            thresholdSeek.progress =
                (params.thresholdRms - VadSettings.MIN_THRESHOLD_RMS) / thresholdStep
            hangoverSeek.progress =
                (params.hangoverMs - VadSettings.MIN_HANGOVER_MS) / hangoverStep
            prerollSeek.progress =
                (params.prerollMs - VadSettings.MIN_PREROLL_MS) / prerollStep

            // setProgress doesn't fire the listener when the value is unchanged
            // (0 on a fresh holder is a common case), so the labels are set
            // explicitly rather than relying on the callback.
            thresholdLabel.text = "Порог: ${params.thresholdRms}"
            hangoverLabel.text = "Хвост: ${params.hangoverMs} мс"
            prerollLabel.text = "Преролл: ${params.prerollMs} мс"

            suppressScreenHoldCallback = true
            screenHoldInput.setText(params.screenHoldSeconds.toString())
            suppressScreenHoldCallback = false

            render()
        }

        fun startTicking() {
            if (ticking) return
            ticking = true
            mainHandler.post(tick)
        }

        fun stopTicking() {
            ticking = false
            mainHandler.removeCallbacks(tick)
        }

        private fun render() {
            if (!VadMonitor.capturing) {
                levelText.text = "—"
                peakText.text = "пик —"
                // Distinguishing "not listening" from "listening and silent" is
                // the difference between a threshold that's too high and a
                // service that isn't running at all — easy to confuse otherwise.
                stateText.text = "Служба не слушает (экран/спец. возможности)"
                return
            }
            levelText.text = VadMonitor.currentRms.toString()
            peakText.text = "пик ${VadMonitor.peakRms}"
            stateText.text = if (VadMonitor.gateOpen) "Распознаёт" else "Ждёт"
        }
    }

    /**
     * Appearance and placement of the on-screen microphone indicator.
     *
     * Everything writes straight to preferences; the accessibility service
     * watches the same file and re-shows the overlay, so changes land on the
     * watch face while this page is open rather than at the next listen cycle.
     */
    inner class IconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // itemView is the FrameLayout wrapper (see item_icon.xml) —
        // scrollRoot is the actual ScrollView, a child of it.
        private val scrollRoot: ScrollView = itemView.findViewById(R.id.scrollRoot)
        private val enabledCheck: CheckBox = itemView.findViewById(R.id.iconEnabledCheck)
        private val previewHolder: FrameLayout = itemView.findViewById(R.id.iconPreviewHolder)
        private val previewActiveHolder: FrameLayout =
            itemView.findViewById(R.id.iconPreviewActiveHolder)
        private val colorInitRow: LinearLayout = itemView.findViewById(R.id.iconColorInitRow)
        private val colorActiveRow: LinearLayout = itemView.findViewById(R.id.iconColorActiveRow)
        private val sizeSeek: SeekBar = itemView.findViewById(R.id.iconSizeSeek)
        private val offsetXSeek: SeekBar = itemView.findViewById(R.id.iconOffsetXSeek)
        private val offsetYSeek: SeekBar = itemView.findViewById(R.id.iconOffsetYSeek)
        private val sizeLabel: TextView = itemView.findViewById(R.id.iconSizeLabel)
        private val offsetXLabel: TextView = itemView.findViewById(R.id.iconOffsetXLabel)
        private val offsetYLabel: TextView = itemView.findViewById(R.id.iconOffsetYLabel)
        private val resetButton: Button = itemView.findViewById(R.id.iconResetButton)

        /** Offsets step in whole dp; 4 keeps the slider usable on a small screen. */
        private val offsetStep = 4

        private val preview = MicIndicatorView(itemView.context).apply {
            state = MicIndicatorView.State.INITIALIZING
        }
        private val previewActive = MicIndicatorView(itemView.context).apply {
            state = MicIndicatorView.State.RECORDING
        }

        /** Swatch views, in palette order, one row per state. */
        private val initSwatches = mutableListOf<View>()
        private val activeSwatches = mutableListOf<View>()

        private var colorInitIndex = OverlaySettings.DEFAULT_COLOR_INIT_INDEX
        private var colorActiveIndex = OverlaySettings.DEFAULT_COLOR_ACTIVE_INDEX

        init {
            scrollRoot.enableRotaryScroll()
            itemView.findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator).attachTo(scrollRoot)

            previewHolder.addView(preview)
            previewActiveHolder.addView(previewActive)
            buildSwatches()

            sizeSeek.max = OverlaySettings.MAX_SIZE_DP - OverlaySettings.MIN_SIZE_DP
            offsetXSeek.max =
                (OverlaySettings.MAX_OFFSET_DP - OverlaySettings.MIN_OFFSET_DP) / offsetStep
            offsetYSeek.max = offsetXSeek.max

            enabledCheck.setOnCheckedChangeListener { _, checked ->
                OverlaySettings.saveEnabled(itemView.context, checked)
                setControlsEnabled(checked)
            }

            sizeSeek.setOnSeekBarChangeListener(
                onProgress { progress, fromUser ->
                    val value = OverlaySettings.MIN_SIZE_DP + progress
                    sizeLabel.text = "Размер: $value"
                    applyPreviewSize(value)
                    if (fromUser) OverlaySettings.saveSize(itemView.context, value)
                }
            )
            offsetXSeek.setOnSeekBarChangeListener(
                onProgress { progress, fromUser ->
                    val value = OverlaySettings.MIN_OFFSET_DP + progress * offsetStep
                    offsetXLabel.text = "X: $value"
                    if (fromUser) OverlaySettings.saveOffsetX(itemView.context, value)
                }
            )
            offsetYSeek.setOnSeekBarChangeListener(
                onProgress { progress, fromUser ->
                    val value = OverlaySettings.MIN_OFFSET_DP + progress * offsetStep
                    offsetYLabel.text = "Y: $value"
                    if (fromUser) OverlaySettings.saveOffsetY(itemView.context, value)
                }
            )

            resetButton.setOnClickListener {
                OverlaySettings.saveOffsetX(itemView.context, 0)
                OverlaySettings.saveOffsetY(itemView.context, 0)
                offsetXSeek.progress = -OverlaySettings.MIN_OFFSET_DP / offsetStep
                offsetYSeek.progress = -OverlaySettings.MIN_OFFSET_DP / offsetStep
            }
        }

        fun bind() {
            val params = OverlaySettings.load(itemView.context)

            enabledCheck.isChecked = params.enabled
            setControlsEnabled(params.enabled)

            sizeSeek.progress = params.sizeDp - OverlaySettings.MIN_SIZE_DP
            offsetXSeek.progress =
                (params.offsetXDp - OverlaySettings.MIN_OFFSET_DP) / offsetStep
            offsetYSeek.progress =
                (params.offsetYDp - OverlaySettings.MIN_OFFSET_DP) / offsetStep

            // setProgress fires no callback when the value is unchanged, so the
            // labels and preview are set here rather than left to the listener.
            sizeLabel.text = "Размер: ${params.sizeDp}"
            offsetXLabel.text = "X: ${params.offsetXDp}"
            offsetYLabel.text = "Y: ${params.offsetYDp}"
            applyPreviewSize(params.sizeDp)

            colorInitIndex = params.colorInitIndex
            colorActiveIndex = params.colorActiveIndex
            applyColors()
        }

        private fun applyPreviewSize(sizeDp: Int) {
            val px = (sizeDp * itemView.resources.displayMetrics.density).toInt()
            for (view in listOf(preview, previewActive)) {
                view.layoutParams = FrameLayout.LayoutParams(px, px, Gravity.CENTER)
                view.requestLayout()
            }
        }

        /**
         * Builds the two palette rows once. Swatches are created in code rather
         * than declared in XML because ten near-identical views with ten ids
         * would have to be kept in step with the palette by hand.
         */
        private fun buildSwatches() {
            val density = itemView.resources.displayMetrics.density
            val sizePx = (22 * density).toInt()
            val marginPx = (3 * density).toInt()

            for (index in OverlaySettings.PALETTE.indices) {
                for ((row, swatches) in listOf(
                    colorInitRow to initSwatches,
                    colorActiveRow to activeSwatches
                )) {
                    val isInitRow = swatches === initSwatches
                    val swatch = View(itemView.context)
                    swatch.layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                        marginStart = marginPx
                        marginEnd = marginPx
                    }
                    swatch.contentDescription = OverlaySettings.PALETTE_NAMES[index]
                    swatch.setOnClickListener {
                        if (isInitRow) {
                            colorInitIndex = index
                            OverlaySettings.saveColorInit(itemView.context, index)
                        } else {
                            colorActiveIndex = index
                            OverlaySettings.saveColorActive(itemView.context, index)
                        }
                        applyColors()
                    }
                    row.addView(swatch)
                    swatches.add(swatch)
                }
            }
        }

        private fun applyColors() {
            val initColor = OverlaySettings.colorAt(colorInitIndex)
            val activeColor = OverlaySettings.colorAt(colorActiveIndex)
            preview.setColors(initColor, activeColor)
            previewActive.setColors(initColor, activeColor)

            paintSwatches(initSwatches, colorInitIndex)
            paintSwatches(activeSwatches, colorActiveIndex)
        }

        private fun paintSwatches(swatches: List<View>, selectedIndex: Int) {
            val density = itemView.resources.displayMetrics.density
            for (index in swatches.indices) {
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(OverlaySettings.colorAt(index))
                    // Every swatch is outlined so that white and black both read
                    // against the background; the selected one just gets a
                    // thicker, brighter ring.
                    val selected = index == selectedIndex
                    setStroke(
                        ((if (selected) 3 else 1) * density).toInt(),
                        if (selected) Color.parseColor("#9B6BFF") else Color.parseColor("#3A3646")
                    )
                }
                swatches[index].background = drawable
            }
        }

        private fun setControlsEnabled(enabled: Boolean) {
            sizeSeek.isEnabled = enabled
            offsetXSeek.isEnabled = enabled
            offsetYSeek.isEnabled = enabled
            resetButton.isEnabled = enabled
            colorInitRow.isEnabled = enabled
            colorActiveRow.isEnabled = enabled
            for (swatch in initSwatches + activeSwatches) swatch.isEnabled = enabled
            val alpha = if (enabled) 1f else 0.3f
            preview.alpha = alpha
            previewActive.alpha = alpha
            colorInitRow.alpha = alpha
            colorActiveRow.alpha = alpha
        }

        private fun onProgress(block: (Int, Boolean) -> Unit) =
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) =
                    block(progress, fromUser)

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
    }

    /**
     * Closing page: QR codes for the donation link and the author's own
     * channels. Static content — bound once here in init, not on every
     * bind() like the other pages, since none of it can change at runtime.
     */
    inner class SupportViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // itemView is the FrameLayout wrapper (see item_support.xml) —
        // scrollRoot is the actual ScrollView, a child of it.
        private val scrollRoot: ScrollView = itemView.findViewById(R.id.scrollRoot)
        private val donateQrImage: ImageView = itemView.findViewById(R.id.donateQrImage)
        private val telegramQrImage: ImageView = itemView.findViewById(R.id.telegramQrImage)
        private val maxQrImage: ImageView = itemView.findViewById(R.id.maxQrImage)

        init {
            scrollRoot.enableRotaryScroll()
            itemView.findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator).attachTo(scrollRoot)

            val sizePx = (120 * itemView.resources.displayMetrics.density).toInt()
            donateQrImage.setImageBitmap(QrCode.render(DONATE_URL, sizePx))
            telegramQrImage.setImageBitmap(QrCode.render(TELEGRAM_URL, sizePx))
            maxQrImage.setImageBitmap(QrCode.render(MAX_URL, sizePx))
        }
    }
}
