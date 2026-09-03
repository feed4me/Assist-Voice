package com.nikolay.assistvoice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Full-screen editor for one voice slot.
 *
 * Reached by tapping a row on the slot list (MainActivity's
 * SlotListViewHolder) or its "Добавить слот" button, which creates an empty
 * slot via TargetAppPrefs first, then opens straight into this screen for it.
 * Exactly one instance of this screen exists at a time, which keeps its view
 * state simple to reason about.
 */
class SlotEditActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SLOT_ID = "slot_id"

        private val ACTION_TYPES = listOf(SlotActionType.LAUNCH_APP, SlotActionType.CALL)
        private val ACTION_TYPE_LABELS = listOf("Запуск приложения", "Звонок")
        private const val MIN_WAKE_WORD_LENGTH = 2

        /** Dotted identifier, e.g. android.intent.action.MAIN — no spaces. */
        private val INTENT_ACTION_PATTERN =
            Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        fun intent(context: Context, slotId: String): Intent =
            Intent(context, SlotEditActivity::class.java).putExtra(EXTRA_SLOT_ID, slotId)
    }

    private lateinit var enabledCheckbox: CheckBox
    private lateinit var actionTypeSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button

    private lateinit var launchAppGroup: View
    private lateinit var callGroup: View

    private lateinit var appPickerField: TextView
    private lateinit var packageInput: EditText
    private lateinit var activityInput: EditText
    private lateinit var intentActionInput: EditText

    private lateinit var contactPickerField: TextView
    private lateinit var wakeWordInput: EditText
    private lateinit var phraseHint: TextView

    private lateinit var actionTypeAdapter: ArrayAdapter<String>

    @Volatile private var appList: List<InstalledApp> = emptyList()
    @Volatile private var contactList: List<Contact> = emptyList()

    // Contact selection happens in a separate full-screen PickerActivity (see
    // its class doc), which returns only an index — the current choice is
    // tracked here, set at populateFields() time and again whenever the
    // person picks a different contact.
    private var selectedContact: Contact? = null

    private var boundSlot: VoiceSlot? = null

    // Guards programmatic setSelection() calls on actionTypeSpinner from
    // re-entering the listener below as if the person had picked something.
    private var suppressCallbacks = false

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val index = result.data?.getIntExtra(PickerActivity.EXTRA_RESULT_INDEX, -1) ?: -1
        val app = appList.getOrNull(index) ?: return@registerForActivityResult
        appPickerField.text = app.label
        packageInput.setText(app.packageName)
        AppPickerHelper.getLauncherActivityClassName(this, app.packageName)
            ?.let { activityInput.setText(it) }
        if (intentActionInput.text.toString().isBlank()) {
            intentActionInput.setText(TargetAppPrefs.DEFAULT_INTENT_ACTION)
        }
    }

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val index = result.data?.getIntExtra(PickerActivity.EXTRA_RESULT_INDEX, -1) ?: -1
        val contact = contactList.getOrNull(index) ?: return@registerForActivityResult
        selectedContact = contact
        updateContactField()
    }

    // Guards against a second Save tap firing while the wake-word check from
    // the first one is still running in the background.
    private var saveInFlight = false

    private val wordCheckExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.item_slot)

        val slotId = intent.getStringExtra(EXTRA_SLOT_ID)
        val slot = slotId?.let { id -> TargetAppPrefs.getSlots(this).find { it.id == id } }
        if (slot == null) {
            Toast.makeText(this, "Слот больше не существует", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        boundSlot = slot

        bindViews()
        val scrollRoot = findViewById<ScrollView>(R.id.scrollRoot)
        scrollRoot.enableRotaryScroll()
        findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator).attachTo(scrollRoot)
        // enableRotaryScroll() only wires the listener — the rotary crown's
        // AXIS_SCROLL events still go to whichever view currently has focus,
        // same as every pager page (see MainActivity.focusCurrentPage()).
        // Unlike those pages, nothing else claims focus here: this is a
        // plain Activity, not a ViewPager2 page MainActivity focuses on
        // page-select, so without this call the framework's default focus
        // (the first focusable widget it finds — a Spinner, checkbox or
        // button) wins instead, and the crown never reaches the ScrollView
        // at all. Posted so it runs after the first layout pass, same
        // reasoning as MainActivity's own use of requestRotaryFocus().
        scrollRoot.post { scrollRoot.requestRotaryFocus() }

        actionTypeAdapter = buildAdapter(ACTION_TYPE_LABELS)
        actionTypeSpinner.adapter = actionTypeAdapter

        wireListeners()
        populateFields(slot)

        // Installed apps / contacts are loaded off the main thread — see
        // MainActivity.loadPickerDataAsync, same reasoning applies here.
        loadPickerDataAsync()
    }

    override fun onStart() {
        super.onStart()
        OwnAppForegroundTracker.onActivityStarted()
    }

    override fun onStop() {
        OwnAppForegroundTracker.onActivityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        wordCheckExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bindViews() {
        enabledCheckbox = findViewById(R.id.enabledCheckbox)
        actionTypeSpinner = findViewById(R.id.actionTypeSpinner)
        statusText = findViewById(R.id.statusText)
        saveButton = findViewById(R.id.saveSlotButton)
        deleteButton = findViewById(R.id.deleteSlotButton)

        launchAppGroup = findViewById(R.id.launchAppGroup)
        callGroup = findViewById(R.id.callGroup)

        appPickerField = findViewById(R.id.appPickerField)
        packageInput = findViewById(R.id.packageNameInput)
        activityInput = findViewById(R.id.activityNameInput)
        intentActionInput = findViewById(R.id.intentActionInput)

        contactPickerField = findViewById(R.id.contactPickerField)
        wakeWordInput = findViewById(R.id.wakeWordInput)
        phraseHint = findViewById(R.id.phraseHint)
    }

    private fun buildAdapter(labels: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    /**
     * Delegates to PickerDataCache (see its class doc) rather than querying
     * PackageManager/Contacts directly — those queries are slow enough on this
     * watch's CPU to be a noticeable delay if re-run on every open.
     */
    private fun loadPickerDataAsync() {
        val needContacts = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        PickerDataCache.ensureLoaded(this, needContacts) {
            if (isFinishing || isDestroyed) return@ensureLoaded
            appList = PickerDataCache.apps
            contactList = PickerDataCache.contacts
            // The real contact list just arrived (or was already cached) —
            // re-run the preselection now that there's something to match
            // against.
            boundSlot?.let { preselectContact(it) }
        }
    }

    private fun preselectContact(slot: VoiceSlot) {
        selectedContact = contactList.firstOrNull {
            it.phoneNumber == slot.phoneNumber && it.name == slot.contactName
        }
        updateContactField()
    }

    private fun updateContactField() {
        val contact = selectedContact
        contactPickerField.text = if (contact != null) {
            "${contact.name} (${contact.phoneNumber})"
        } else {
            "— выбрать контакт —"
        }
    }

    private fun wireListeners() {
        // No reason to let the framework restore these across a config
        // change — populateFields() already sets them from boundSlot.
        packageInput.isSaveEnabled = false
        activityInput.isSaveEnabled = false
        intentActionInput.isSaveEnabled = false
        wakeWordInput.isSaveEnabled = false

        wakeWordInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(cs: CharSequence?, s: Int, c: Int, a: Int) {}
            override fun onTextChanged(cs: CharSequence?, s: Int, b: Int, c: Int) {}
            override fun afterTextChanged(editable: Editable?) {
                updatePhraseHint()
            }
        })

        actionTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (suppressCallbacks) return
                showGroupForActionType(ACTION_TYPES.getOrElse(position) { SlotActionType.LAUNCH_APP })
                // The prefix is derived from the action type, so the hint is
                // stale the moment this changes.
                updatePhraseHint()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Both fields open PickerActivity instead of a Spinner popup — see
        // its class doc for why. appPickerField never shows a preselected
        // app (see populateFields()); contactPickerField passes its current
        // selectedContact, if any, so PickerActivity can highlight it.
        appPickerField.setOnClickListener {
            appPickerLauncher.launch(
                PickerActivity.intent(this, "Приложение", appList.map { it.label })
            )
        }

        contactPickerField.setOnClickListener {
            val labels = contactList.map { "${it.name} (${it.phoneNumber})" }
            val currentIndex = selectedContact?.let { current ->
                contactList.indexOfFirst {
                    it.phoneNumber == current.phoneNumber && it.name == current.name
                }
            } ?: -1
            contactPickerLauncher.launch(
                PickerActivity.intent(this, "Контакт", labels, currentIndex)
            )
        }

        enabledCheckbox.setOnCheckedChangeListener { _, isChecked ->
            boundSlot?.let { current -> updateSlot(current) { it.copy(enabled = isChecked) } }
        }

        saveButton.setOnClickListener {
            boundSlot?.let { saveCurrentValues(it) }
        }

        deleteButton.setOnClickListener {
            boundSlot?.let {
                TargetAppPrefs.deleteSlot(this, it.id)
                finish()
            }
        }
    }

    private fun populateFields(slot: VoiceSlot) {
        enabledCheckbox.isChecked = slot.enabled

        suppressCallbacks = true
        actionTypeSpinner.setSelection(ACTION_TYPES.indexOf(slot.actionType).coerceAtLeast(0), false)
        suppressCallbacks = false

        // Never preselects the current app — this field is only for picking
        // a *new* one; the persisted value is shown in packageInput /
        // activityInput below.
        appPickerField.text = "— выбрать приложение —"

        packageInput.setText(slot.packageName)
        activityInput.setText(slot.activityName)
        intentActionInput.setText(slot.intentAction)
        wakeWordInput.setText(slot.wakeWord)

        showGroupForActionType(slot.actionType)
        updateStatusText(slot)
        updatePhraseHint()
        preselectContact(slot)
    }

    private fun showGroupForActionType(type: SlotActionType) {
        launchAppGroup.visibility = if (type == SlotActionType.LAUNCH_APP) View.VISIBLE else View.GONE
        callGroup.visibility = if (type == SlotActionType.CALL) View.VISIBLE else View.GONE
    }

    /**
     * Shows the full phrase, not just the word. The prefix is not optional
     * and not something the person chose, so a card that only echoed the
     * word would be showing something nobody can actually say to trigger
     * this slot.
     */
    private fun updateStatusText(slot: VoiceSlot) {
        statusText.text = if (slot.wakeWord.isBlank()) {
            "Слово ещё не задано"
        } else {
            "Скажи: «${VoicePhrases.phraseFor(slot)}»"
        }
    }

    /** Live hint under the word field, updated as the person types. */
    private fun updatePhraseHint() {
        val word = wakeWordInput.text.toString().trim().lowercase()
        val prefix = VoicePhrases.prefixFor(currentActionType())
        phraseHint.text = if (word.isEmpty()) {
            "Команда: «$prefix …»"
        } else {
            "Команда: «$prefix $word»"
        }
    }

    private fun currentActionType(): SlotActionType =
        ACTION_TYPES.getOrElse(actionTypeSpinner.selectedItemPosition) { SlotActionType.LAUNCH_APP }

    /**
     * Validates the synchronous stuff first (field presence, format), then
     * checks the wake word against the Vosk model's vocabulary in the
     * background before actually writing to TargetAppPrefs.
     */
    private fun saveCurrentValues(originalSlot: VoiceSlot) {
        if (saveInFlight) return

        val actionType = currentActionType()
        val wakeWord = wakeWordInput.text.toString().trim()

        if (wakeWord.length < MIN_WAKE_WORD_LENGTH) {
            toast("Слово: минимум $MIN_WAKE_WORD_LENGTH символа")
            return
        }

        when (actionType) {
            SlotActionType.LAUNCH_APP -> {
                val packageName = packageInput.text.toString().trim()
                val activityName = activityInput.text.toString().trim()
                val intentAction = intentActionInput.text.toString().trim()
                if (packageName.isBlank() || activityName.isBlank()) {
                    toast("Заполни пакет и класс Activity")
                    return
                }
                if (!INTENT_ACTION_PATTERN.matches(intentAction)) {
                    toast("Неверный Intent Action")
                    return
                }
            }
            SlotActionType.CALL -> {
                // A previously saved number counts, so a CALL slot stays
                // re-saveable even if the contact list is currently empty
                // (permission revoked) or the contact was renamed.
                if (selectedContact == null && originalSlot.phoneNumber.isBlank()) {
                    toast("Выбери контакт")
                    return
                }
            }
        }

        // An unchanged word AND an unchanged action type need no
        // re-checking — the same phrase was already validated when this
        // slot was last saved.
        if (wakeWord.equals(originalSlot.wakeWord, ignoreCase = true) &&
            actionType == originalSlot.actionType
        ) {
            commitSave(originalSlot, actionType, wakeWord, selectedContact)
            return
        }

        val phrase = VoicePhrases.phraseFor(actionType, wakeWord)

        saveInFlight = true
        saveButton.isEnabled = false
        toast("Проверяю слово…")

        wordCheckExecutor.execute {
            val result = WakeWordDictionary.checkPhrase(applicationContext, phrase)
            mainHandler.post {
                saveInFlight = false
                saveButton.isEnabled = true
                when (result) {
                    is WakeWordDictionary.Result.Ok ->
                        commitSave(originalSlot, actionType, wakeWord, selectedContact)

                    is WakeWordDictionary.Result.Missing -> toast(
                        "Модель не знает слово «${result.word}» — попробуй другое"
                    )

                    is WakeWordDictionary.Result.Unavailable -> {
                        // Model failed to load, or the lookup itself threw —
                        // an infrastructure problem, not a bad word.
                        toast("Не удалось проверить слово — сохранено без проверки")
                        commitSave(originalSlot, actionType, wakeWord, selectedContact)
                    }
                }
            }
        }
    }

    private fun commitSave(
        originalSlot: VoiceSlot,
        actionType: SlotActionType,
        wakeWord: String,
        selectedContact: Contact?
    ) {
        val updated = originalSlot.copy(
            actionType = actionType,
            wakeWord = wakeWord,
            packageName = packageInput.text.toString().trim(),
            activityName = activityInput.text.toString().trim(),
            intentAction = intentActionInput.text.toString().trim()
                .ifBlank { TargetAppPrefs.DEFAULT_INTENT_ACTION },
            contactName = selectedContact?.name ?: originalSlot.contactName,
            phoneNumber = selectedContact?.phoneNumber ?: originalSlot.phoneNumber
        )

        val current = TargetAppPrefs.getSlots(this).toMutableList()
        val index = current.indexOfFirst { it.id == originalSlot.id }
        if (index < 0) {
            // The slot was deleted elsewhere in the meantime.
            toast("Слот больше не существует")
            finish()
            return
        }
        current[index] = updated
        TargetAppPrefs.saveSlots(this, current)
        toast("Сохранено")
        finish()
    }

    private fun updateSlot(slot: VoiceSlot, transform: (VoiceSlot) -> VoiceSlot) {
        val current = TargetAppPrefs.getSlots(this).toMutableList()
        val index = current.indexOfFirst { it.id == slot.id }
        if (index < 0) return
        current[index] = transform(slot)
        TargetAppPrefs.saveSlots(this, current)
        boundSlot = current[index]
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
