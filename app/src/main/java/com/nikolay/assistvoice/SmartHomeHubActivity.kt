package com.nikolay.assistvoice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * "Умный дом Яндекса" hub — reached once already authorized, either
 * straight from the Integrations tile (MainActivity.openYandexSmartHome())
 * or automatically after SmartHomeAuthActivity's Device Flow succeeds.
 *
 * Entry points, matching how Yandex itself models a smart home: individual
 * devices, Yandex-side groups of devices (see SmartHomeGroup's doc), the
 * integration's start word, and disconnecting entirely. Kept as a separate
 * menu screen rather than folding groups into the device list — devices and
 * groups need different detail screens (a group has no room/type of its
 * own) and this keeps the "Устройства" list from having to explain why a
 * few rows are structurally different from the rest.
 */
class SmartHomeHubActivity : AppCompatActivity() {

    companion object {
        private const val MIN_START_WORD_LENGTH = 2

        fun intent(context: Context): Intent = Intent(context, SmartHomeHubActivity::class.java)
    }

    private lateinit var startWordInput: EditText
    private lateinit var startWordSaveButton: Button
    private val wordCheckExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var checkInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_home_hub)

        val scrollRoot = findViewById<ScrollView>(R.id.scrollRoot)
        scrollRoot.enableRotaryScroll()
        findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator).attachTo(scrollRoot)
        scrollRoot.post { scrollRoot.requestRotaryFocus() }

        startWordInput = findViewById(R.id.startWordInput)
        startWordSaveButton = findViewById(R.id.startWordSaveButton)
        startWordInput.setText(SmartHomePrefs.getStartWord(this))

        findViewById<Button>(R.id.devicesButton).setOnClickListener {
            startActivity(SmartHomeDeviceListActivity.intent(this))
        }
        findViewById<Button>(R.id.groupsButton).setOnClickListener {
            startActivity(SmartHomeGroupListActivity.intent(this))
        }
        startWordSaveButton.setOnClickListener { saveStartWord() }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { confirmDisconnect() }
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

    /**
     * Every smart-home command is stored as free text and combined with this
     * word at match time (see VoicePhrases.smartHomeYandexPhraseFor), so
     * changing it here takes effect for every existing command immediately —
     * nothing else needs to be re-saved. Checked the same way a command's
     * own phrase is (WakeWordDictionary.checkPhrase): an unknown word would
     * never be recognized, silently leaving every command dead (see
     * WakeWordDictionary's doc).
     */
    private fun saveStartWord() {
        if (checkInFlight) return

        val word = startWordInput.text.toString().trim().lowercase()
        if (word.length < MIN_START_WORD_LENGTH || word.contains(' ')) {
            toast("Стартовое слово: одно слово, минимум $MIN_START_WORD_LENGTH символа")
            return
        }

        checkInFlight = true
        startWordSaveButton.isEnabled = false
        toast("Проверяю слово…")

        wordCheckExecutor.execute {
            val result = WakeWordDictionary.checkPhrase(applicationContext, word)
            mainHandler.post {
                checkInFlight = false
                startWordSaveButton.isEnabled = true
                when (result) {
                    is WakeWordDictionary.Result.Ok -> {
                        SmartHomePrefs.saveStartWord(this, word)
                        toast("Стартовое слово сохранено")
                    }
                    is WakeWordDictionary.Result.Missing -> toast(
                        "Модель не знает слово «${result.word}» — попробуй другое"
                    )
                    is WakeWordDictionary.Result.Unavailable -> {
                        SmartHomePrefs.saveStartWord(this, word)
                        toast("Не удалось проверить слово — сохранено без проверки")
                    }
                }
            }
        }
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setTitle("Отключить умный дом Яндекса?")
            .setMessage("Голосовые команды останутся сохранёнными, но перестанут выполняться, пока не подключишь заново.")
            .setPositiveButton("Отключить") { _, _ ->
                SmartHomePrefs.clearTokens(this)
                Toast.makeText(this, "Отключено", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
