package com.nikolay.assistvoice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * OAuth 2.0 Device Flow screen for "Умный дом Яндекса" — shows the code and
 * verification URL from YandexOAuthDeviceFlow.requestDeviceCode(), then
 * polls for the token every interval seconds until the person confirms it
 * on oauth.yandex.ru/device (from any device with a browser), the code
 * expires, or they back out.
 *
 * Reached only from MainActivity.openYandexSmartHome() when not already
 * authorized — a second tap on the tile after success goes straight to
 * SmartHomeDeviceListActivity instead, never back through here.
 */
class SmartHomeAuthActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, SmartHomeAuthActivity::class.java)
    }

    private lateinit var userCodeText: TextView
    private lateinit var verificationUrlText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var copyButton: Button
    private lateinit var retryButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = Runnable { poll() }

    private var deviceCode: String? = null
    private var currentIntervalMs: Long = 5_000L
    private var expiresAtElapsedMs: Long = 0L
    private var userCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_home_auth)

        val scrollRoot = findViewById<ScrollView>(R.id.scrollRoot)
        scrollRoot.enableRotaryScroll()
        findViewById<CurvedScrollIndicatorView>(R.id.scrollIndicator).attachTo(scrollRoot)
        scrollRoot.post { scrollRoot.requestRotaryFocus() }

        userCodeText = findViewById(R.id.userCodeText)
        verificationUrlText = findViewById(R.id.verificationUrlText)
        qrImage = findViewById(R.id.qrImage)
        statusText = findViewById(R.id.statusText)
        copyButton = findViewById(R.id.copyCodeButton)
        retryButton = findViewById(R.id.retryButton)

        copyButton.setOnClickListener { copyCodeToClipboard() }
        retryButton.setOnClickListener { startDeviceCodeRequest() }

        startDeviceCodeRequest()
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
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startDeviceCodeRequest() {
        mainHandler.removeCallbacks(pollRunnable)
        copyButton.isEnabled = false
        retryButton.visibility = View.GONE
        userCodeText.text = "…"
        verificationUrlText.text = ""
        qrImage.setImageBitmap(null)
        statusText.text = "Получаю код…"

        YandexOAuthDeviceFlow.requestDeviceCode { result ->
            if (isFinishing || isDestroyed) return@requestDeviceCode
            when (result) {
                is DeviceCodeResult.Success -> onDeviceCodeReady(result.info)
                is DeviceCodeResult.Error -> {
                    statusText.text = result.message
                    retryButton.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun onDeviceCodeReady(info: DeviceCodeInfo) {
        deviceCode = info.deviceCode
        userCode = info.userCode
        currentIntervalMs = info.intervalSeconds * 1000L
        expiresAtElapsedMs = SystemClock.elapsedRealtime() + info.expiresInSeconds * 1000L

        userCodeText.text = info.userCode
        verificationUrlText.text = info.verificationUrl
        copyButton.isEnabled = true
        statusText.text = "Открой ${info.verificationUrl} на любом устройстве и введи код"

        val sizePx = (140 * resources.displayMetrics.density).toInt()
        qrImage.setImageBitmap(QrCode.render(info.verificationUrl, sizePx))

        mainHandler.postDelayed(pollRunnable, currentIntervalMs)
    }

    private fun poll() {
        val code = deviceCode ?: return
        if (SystemClock.elapsedRealtime() >= expiresAtElapsedMs) {
            statusText.text = "Код истёк — нажми «Обновить код»"
            retryButton.visibility = View.VISIBLE
            return
        }

        YandexOAuthDeviceFlow.pollToken(code) { result ->
            if (isFinishing || isDestroyed) return@pollToken
            when (result) {
                is TokenPollResult.Success -> onAuthorized(result)
                is TokenPollResult.Pending -> mainHandler.postDelayed(pollRunnable, currentIntervalMs)
                is TokenPollResult.SlowDown -> {
                    currentIntervalMs += 5_000L
                    mainHandler.postDelayed(pollRunnable, currentIntervalMs)
                }
                is TokenPollResult.Denied -> {
                    statusText.text = result.message
                    retryButton.visibility = View.VISIBLE
                }
                is TokenPollResult.Error -> {
                    // Transient network hiccup — keep polling rather than
                    // abandoning a code the person may already have entered.
                    statusText.text = "${result.message} — жду подтверждения…"
                    mainHandler.postDelayed(pollRunnable, currentIntervalMs)
                }
            }
        }
    }

    private fun onAuthorized(result: TokenPollResult.Success) {
        SmartHomePrefs.saveTokens(this, result.accessToken, result.refreshToken, result.expiresInSeconds)
        Toast.makeText(this, "Умный дом Яндекса подключён", Toast.LENGTH_LONG).show()
        startActivity(SmartHomeHubActivity.intent(this))
        finish()
    }

    private fun copyCodeToClipboard() {
        if (userCode.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Код авторизации", userCode))
        Toast.makeText(this, "Код скопирован", Toast.LENGTH_SHORT).show()
    }
}
