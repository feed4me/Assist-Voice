package com.nikolay.assistvoice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
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
 * SmartHomeCommand's doc) — shown as two fixed, read-only preview rows in
 * commandRowsContainer that are never added or removed, only ever changed
 * as a pair. There is exactly one text field and one save button: the body
 * of the phrase (e.g. "свет на кухне") is the same for both states of the
 * same target — only the verb differs, and that's already derived from
 * [SmartHomeCommand.value], never typed — so there is nothing left to pick
 * between two rows for. Saving writes that one text into both the on and
 * off command at once (creating whichever doesn't exist yet).
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
    private lateinit var phraseInput: EditText
    private lateinit var saveButton: Button

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

        // The body is shared between the on and off command (see this
        // class's doc) — load whichever one already has it, if either does.
        val existingText = SmartHomePrefs.commandFor(this, deviceId, true)?.phrase
            ?: SmartHomePrefs.commandFor(this, deviceId, false)?.phrase
            ?: ""
        phraseInput.setText(existingText)
        phraseInput.setSelection(phraseInput.text?.length ?: 0)

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
        phraseInput = findViewById(R.id.phraseInput)
        saveButton = findViewById(R.id.saveButton)
    }

    /** Rebuilds the two fixed preview rows from whatever's actually saved —
     * always exactly one for [value] true, one for false, regardless of
     * whether either has a phrase yet. Read-only — see this class's doc. */
    private fun refreshCommands() {
        val inflater = LayoutInflater.from(this)
        commandRowsContainer.removeAllViews()

        for (value in listOf(true, false)) {
            val command = SmartHomePrefs.commandFor(this, deviceId, value)
            val row = inflater.inflate(R.layout.item_smart_home_command_row, commandRowsContainer, false)
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

            commandRowsContainer.addView(row)
        }
    }

    /**
     * Same two-step validation as SlotEditActivity.saveCurrentValues(): the
     * whole phrase (start word, verb and free text) is checked against the
     * Vosk model's vocabulary in the background before it's written — an
     * unknown word would never be recognized, silently leaving the command
     * dead (see WakeWordDictionary's doc). Checking just the on-phrase is
     * enough: the off-phrase differs only by a fixed verb ("выключи") that's
     * an ordinary word already known to every Russian Vosk model.
     */
    private fun save() {
        if (saveInFlight) return

        val text = phraseInput.text.toString().trim()
        if (text.length < MIN_PHRASE_LENGTH) {
            toast("Фраза: минимум $MIN_PHRASE_LENGTH символа")
            return
        }

        val onPhrase = VoicePhrases.smartHomeYandexPhraseFor(this, true, text)
        saveInFlight = true
        saveButton.isEnabled = false
        toast("Проверяю фразу…")

        wordCheckExecutor.execute {
            val result = WakeWordDictionary.checkPhrase(applicationContext, onPhrase)
            mainHandler.post {
                saveInFlight = false
                saveButton.isEnabled = true
                when (result) {
                    is WakeWordDictionary.Result.Ok -> commit(text)
                    is WakeWordDictionary.Result.Missing -> {
                        toast("Модель не знает слово «${result.word}» — попробуй другую фразу")
                    }
                    is WakeWordDictionary.Result.Unavailable -> {
                        toast("Не удалось проверить фразу — сохранено без проверки")
                        commit(text)
                    }
                }
            }
        }
    }

    /** Writes [phrase] into both the on and off command at once — see this
     * class's doc for why one text always covers both. */
    private fun commit(phrase: String) {
        for (value in listOf(true, false)) {
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
        }
        toast("Сохранено")
        refreshCommands()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
