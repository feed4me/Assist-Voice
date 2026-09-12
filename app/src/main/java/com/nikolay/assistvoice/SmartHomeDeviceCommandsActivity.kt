package com.nikolay.assistvoice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Full-screen editor for one Yandex Smart Home device's (or group's) voice
 * commands — the smart-home equivalent of SlotEditActivity, minus the
 * app/contact pickers.
 *
 * A target always has exactly two commands — one on, one off (see
 * SmartHomeCommand's doc) — shown as two fixed rows in commandRowsContainer
 * that are never added or removed, only edited. A row's phrase starts
 * unconfigured ("— не задано —") until saved once; there is no delete.
 *
 * Tapping either row selects it (selectSlot()) and loads its phrase into
 * the form below. There is only one save button — which state it saves is
 * fixed by the row selected, never a separate choice — so it's structurally
 * impossible to save a second command for a state that already has one, or
 * to turn an existing on-command into an off-command by pressing the wrong
 * button (an earlier version had two buttons, both always enabled, and
 * tapping the wrong one while editing silently rewrote which state the
 * phrase belonged to). The button's colour and label switch to match
 * whichever row is selected, so it's still clear what it's about to save.
 *
 * Reached by tapping a row on SmartHomeDeviceListActivity (a device) or
 * SmartHomeGroupListActivity (a group) — [targetType] is which, and is
 * stored on every SmartHomeCommand created here so YandexIotClient knows
 * which API endpoint to call later.
 */
class SmartHomeDeviceCommandsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_DEVICE_NAME = "device_name"
        private const val EXTRA_TARGET_TYPE = "target_type"
        private const val MIN_PHRASE_LENGTH = 2

        fun intent(context: Context, deviceId: String, deviceName: String, targetType: String): Intent =
            Intent(context, SmartHomeDeviceCommandsActivity::class.java)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_DEVICE_NAME, deviceName)
                .putExtra(EXTRA_TARGET_TYPE, targetType)
    }

    private lateinit var deviceId: String
    private lateinit var deviceName: String
    private lateinit var targetType: String

    private lateinit var deviceNameText: TextView
    private lateinit var commandRowsContainer: LinearLayout
    private lateinit var formTitleText: TextView
    private lateinit var phraseInput: EditText
    private lateinit var phraseHint: TextView
    private lateinit var saveButton: Button

    /** Which of the two fixed rows (on/off) is currently loaded into the
     * form — null before either has ever been tapped. See selectSlot(). */
    private var selectedValue: Boolean? = null

    private var saveInFlight = false
    private val wordCheckExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_home_device_commands)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run {
            finish()
            return
        }
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: ""
        targetType = intent.getStringExtra(EXTRA_TARGET_TYPE) ?: SmartHomeCommand.TARGET_DEVICE

        bindViews()

        val scrollRoot = findViewById<ScrollView>(R.id.scrollRoot)
        scrollRoot.enableRotaryScroll()
        findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator).attachTo(scrollRoot)
        scrollRoot.post { scrollRoot.requestRotaryFocus() }

        deviceNameText.text = if (targetType == SmartHomeCommand.TARGET_GROUP) {
            "Группа: $deviceName"
        } else {
            deviceName
        }

        // Nothing selected yet — see selectSlot()'s doc for why the form
        // starts inert rather than defaulting to one row.
        phraseInput.isEnabled = false

        phraseInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(cs: CharSequence?, s: Int, c: Int, a: Int) {}
            override fun onTextChanged(cs: CharSequence?, s: Int, b: Int, c: Int) {}
            override fun afterTextChanged(editable: Editable?) = updatePhraseHint()
        })
        updatePhraseHint()

        saveButton.setOnClickListener { save() }

        refreshCommands()
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
        deviceNameText = findViewById(R.id.deviceNameText)
        commandRowsContainer = findViewById(R.id.commandRowsContainer)
        formTitleText = findViewById(R.id.formTitleText)
        phraseInput = findViewById(R.id.phraseInput)
        phraseHint = findViewById(R.id.phraseHint)
        saveButton = findViewById(R.id.saveButton)
    }

    /** Shows the full phrase, verb and start word included — same
     * reasoning as SlotEditActivity.updatePhraseHint(): neither is
     * optional, so the preview shouldn't pretend they are. */
    private fun updatePhraseHint() {
        val text = phraseInput.text.toString().trim()
        val value = selectedValue
        phraseHint.text = when {
            value == null -> "Сначала выбери ВКЛ или ВЫКЛ выше"
            text.isEmpty() ->
                "Команда: «${SmartHomePrefs.getStartWord(this)} ${VoicePhrases.smartHomeVerbFor(value)} …»"
            else -> "Команда: «${VoicePhrases.smartHomeYandexPhraseFor(this, value, text)}»"
        }
    }

    /** Rebuilds the two fixed rows from whatever's actually saved — always
     * exactly one for [value] true, one for false, regardless of whether
     * either has a phrase yet. */
    private fun refreshCommands() {
        val inflater = LayoutInflater.from(this)
        commandRowsContainer.removeAllViews()

        for (value in listOf(true, false)) {
            val command = SmartHomePrefs.commandFor(this, deviceId, value)
            val row = inflater.inflate(R.layout.item_smart_home_command_row, commandRowsContainer, false)
            val body: View = row.findViewById(R.id.rowCommandBody)
            val badge: TextView = row.findViewById(R.id.rowCommandBadge)
            val phraseText: TextView = row.findViewById(R.id.rowCommandPhrase)

            badge.text = if (value) "Вкл" else "Выкл"
            badge.setTextColor(
                ContextCompat.getColor(this, if (value) R.color.success else R.color.danger)
            )
            phraseText.text = if (command == null) {
                "— не задано —"
            } else {
                "«${VoicePhrases.smartHomeYandexPhraseFor(this, value, command.phrase)}»"
            }
            body.setOnClickListener { selectSlot(value) }

            commandRowsContainer.addView(row)
        }
    }

    /** Loads the row for [value] into the form — its existing phrase if it
     * has one, blank otherwise. There's no other button to disable any
     * more (see this class's doc) — the single save button just switches
     * to match [value]. */
    private fun selectSlot(value: Boolean) {
        selectedValue = value
        val existing = SmartHomePrefs.commandFor(this, deviceId, value)
        phraseInput.isEnabled = true
        phraseInput.setText(existing?.phrase ?: "")
        phraseInput.setSelection(phraseInput.text?.length ?: 0)
        formTitleText.text = if (value) "Включение" else "Выключение"
        updatePhraseHint()
        refreshSaveButton()
    }

    /** Recolours/relabels the single save button to match [selectedValue],
     * and disables it while nothing is selected yet or a save is already
     * in flight. */
    private fun refreshSaveButton() {
        val value = selectedValue
        saveButton.isEnabled = !saveInFlight && value != null
        if (value != null) {
            saveButton.text = if (value) "Сохранить: включение" else "Сохранить: выключение"
            saveButton.setBackgroundResource(
                if (value) R.drawable.bg_button_success else R.drawable.bg_button_danger
            )
            saveButton.setTextColor(
                ContextCompat.getColorStateList(
                    this,
                    if (value) R.color.btn_success_text else R.color.btn_danger_text
                )
            )
        }
    }

    /**
     * Same two-step validation as SlotEditActivity.saveCurrentValues(): the
     * whole phrase (start word, verb and free text) is checked against the
     * Vosk model's vocabulary in the background before it's written — an
     * unknown word would never be recognized, silently leaving the command
     * dead (see WakeWordDictionary's doc).
     */
    private fun save() {
        if (saveInFlight) return
        val value = selectedValue ?: return

        val text = phraseInput.text.toString().trim()
        if (text.length < MIN_PHRASE_LENGTH) {
            toast("Фраза: минимум $MIN_PHRASE_LENGTH символа")
            return
        }

        val fullPhrase = VoicePhrases.smartHomeYandexPhraseFor(this, value, text)
        saveInFlight = true
        refreshSaveButton()
        toast("Проверяю фразу…")

        wordCheckExecutor.execute {
            val result = WakeWordDictionary.checkPhrase(applicationContext, fullPhrase)
            mainHandler.post {
                saveInFlight = false
                when (result) {
                    is WakeWordDictionary.Result.Ok -> commit(text, value)
                    is WakeWordDictionary.Result.Missing -> {
                        refreshSaveButton()
                        toast("Модель не знает слово «${result.word}» — попробуй другую фразу")
                    }
                    is WakeWordDictionary.Result.Unavailable -> {
                        toast("Не удалось проверить фразу — сохранено без проверки")
                        commit(text, value)
                    }
                }
            }
        }
    }

    private fun commit(phrase: String, value: Boolean) {
        val existing = SmartHomePrefs.commandFor(this, deviceId, value)
        if (existing != null) {
            SmartHomePrefs.updateCommand(this, existing.copy(phrase = phrase, deviceName = deviceName))
        } else {
            SmartHomePrefs.addCommand(
                this,
                SmartHomeCommand(
                    id = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    deviceName = deviceName,
                    phrase = phrase,
                    value = value,
                    targetType = targetType
                )
            )
        }
        toast("Сохранено")
        refreshCommands()
        // Stay on the same row so the form reflects what was just saved,
        // rather than resetting back to "pick a row".
        selectSlot(value)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
