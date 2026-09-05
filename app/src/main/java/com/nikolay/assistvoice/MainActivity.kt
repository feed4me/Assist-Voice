package com.nikolay.assistvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File

/**
 * Settings screen: a horizontally swipeable ViewPager2 with 5 fixed pages —
 * info (service status + repo QR code), the slot list (add button plus one
 * row per voice command, tap a row to edit it in SlotEditActivity),
 * microphone-gate (VAD) tuning, mic-icon appearance, then a closing support
 * page (donation and social QR codes).
 *
 * Requests RECORD_AUDIO, then POST_NOTIFICATIONS + READ_CONTACTS + CALL_PHONE +
 * READ_PHONE_STATE, then SYSTEM_ALERT_WINDOW automatically on first open.
 * POST_NOTIFICATIONS matters more than it looks: on API 33+ without it the
 * status notification never appears *and* the full-screen-intent fallback used
 * to launch apps when overlay permission is missing is silently dropped.
 *
 * The actual listening logic lives in VoiceAccessibilityService, which Android
 * does not allow an app to enable programmatically — the person must turn it on
 * manually in system Settings → Accessibility. There are deliberately no
 * deep-link buttons to the Accessibility/Battery settings screens: on this
 * Huawei/EMUI build those screens are not exported.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var adapter: SlotsAdapter
    private lateinit var pageDots: PageDotsView

    /**
     * Installed apps and contacts are loaded off the main thread.
     *
     * listLaunchableApps() calls loadLabel() for every launchable package,
     * which opens and reads each APK's resources; the contacts query hits the
     * content provider. Doing both inline in onCreate froze the watch UI for
     * as long as it took, on every open. The pickers simply populate a moment
     * later instead.
     */
    @Volatile
    private var cachedInstalledApps: List<InstalledApp> = emptyList()

    @Volatile
    private var cachedContacts: List<Contact> = emptyList()

    /** Drives the info page's update section — see UpdateChecker /
     * UpdateInstaller and SlotsAdapter.InfoViewHolder.bindUpdateSection(). */
    private var updateStatus: UpdateStatus = UpdateStatus.Idle

    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestMicPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        adapter.refreshInfoPage()
        requestSecondaryPermissionsIfNeeded()
    }

    private val requestSecondaryPermissionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        adapter.refreshInfoPage()
        loadPickerDataAsync()
        requestOverlayPermissionIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pager = findViewById(R.id.slotsPager)
        pageDots = findViewById(R.id.pageDots)
        adapter = SlotsAdapter(
            getStatusText = ::buildStatus,
            getInstalledApps = { cachedInstalledApps },
            getContacts = { cachedContacts },
            onSlotsChanged = { refreshSlots() },
            onSyncPickerData = { syncPickerData() },
            getUpdateStatus = { updateStatus },
            onUpdateButtonClicked = { onUpdateButtonClicked() }
        )
        pager.adapter = adapter
        // Default RecyclerView change-animation (a cross-fade/translate on
        // notifyItemChanged) is what made the info page visibly jump during
        // an update download: refreshInfoPage() fires on every progress
        // percent tick, and each one replayed that animation from scratch.
        (pager.getChildAt(0) as? RecyclerView)?.itemAnimator = null
        pageDots.attachTo(pager)

        // Rotary crown/bezel input only reaches whichever view currently has
        // focus (see RotaryInput.kt), so the visible page needs to claim it
        // every time a swipe lands on a new one.
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                focusCurrentPage(position)
            }
        })

        refreshSlots()
        loadPickerDataAsync()
        requestPermissionsIfNeeded()
        focusCurrentPage(pager.currentItem)
        repairPackageInstallerIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        OwnAppForegroundTracker.onActivityStarted()
    }

    override fun onStop() {
        OwnAppForegroundTracker.onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshSlots()
        adapter.refreshInfoPage()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Delegates to PickerDataCache (see its class doc) instead of querying
     * PackageManager/Contacts directly, which would make every fresh open of
     * this screen (and every open of SlotEditActivity's pickers) noticeably
     * slow. The cache does the real work at most once per process run.
     */
    private fun loadPickerDataAsync() {
        val needContacts = hasContactsPermission()
        PickerDataCache.ensureLoaded(this, needContacts) {
            if (isFinishing || isDestroyed) return@ensureLoaded
            cachedInstalledApps = PickerDataCache.apps
            cachedContacts = PickerDataCache.contacts
            // Lets the slot list resolve a LAUNCH_APP slot's package name
            // to a readable app label.
            adapter.onPickerDataChanged(cachedInstalledApps, cachedContacts)
        }
    }

    /**
     * Wired to the "Синхронизировать приложения и контакты" button on the
     * slot-list page — the only way PickerDataCache ever refreshes after its
     * first automatic load (see that class's doc for why this replaced an
     * earlier automatic-invalidation attempt).
     */
    private fun syncPickerData() {
        Toast.makeText(this, "Обновляю списки приложений и контактов…", Toast.LENGTH_SHORT).show()
        PickerDataCache.invalidate()
        loadPickerDataAsync()
    }

    /**
     * installOverAdb's own detached install chain (see AdbUpdateInstaller)
     * re-enables the package installer after every update on its own; this
     * is just the backstop for the rare case that gets interrupted (e.g.
     * the watch rebooting mid-update) and it's left disabled. The check
     * itself is a plain local PackageManager query — no ADB involved
     * unless it actually finds a problem to fix.
     */
    private fun repairPackageInstallerIfNeeded() {
        val disabled = try {
            when (packageManager.getApplicationEnabledSetting("com.android.packageinstaller")) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> true
                else -> false
            }
        } catch (e: IllegalArgumentException) {
            false
        }
        if (disabled) {
            AdbUpdateInstaller.ensurePackageInstallerEnabled(this)
        }
    }

    // ---- Updates ----

    /**
     * The info page's single update button means something different
     * depending on updateStatus, since it's the same button throughout the
     * whole check → download → install cycle (see SlotsAdapter's
     * bindUpdateSection for what text it shows in each state).
     */
    private fun onUpdateButtonClicked() {
        when (val status = updateStatus) {
            is UpdateStatus.Idle, is UpdateStatus.UpToDate, is UpdateStatus.Error ->
                checkForUpdates()
            is UpdateStatus.Available -> downloadAndInstallUpdate(status.info)
            is UpdateStatus.ReadyToInstall -> installUpdate(status.info, status.apkFile)
            // Button is disabled in these states — nothing to do.
            is UpdateStatus.Checking, is UpdateStatus.Downloading, is UpdateStatus.Installing -> Unit
        }
    }

    private fun checkForUpdates() {
        updateStatus = UpdateStatus.Checking
        adapter.refreshInfoPage()
        UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME) { result ->
            if (isFinishing || isDestroyed) return@checkForUpdate
            updateStatus = when (result) {
                is UpdateCheckResult.UpdateAvailable -> UpdateStatus.Available(result.info)
                is UpdateCheckResult.UpToDate -> UpdateStatus.UpToDate
                is UpdateCheckResult.Error -> UpdateStatus.Error(result.message)
            }
            adapter.refreshInfoPage()
        }
    }

    private fun downloadAndInstallUpdate(info: UpdateInfo) {
        updateStatus = UpdateStatus.Downloading(info)
        adapter.refreshInfoPage()
        UpdateInstaller.downloadApk(
            context = this,
            info = info,
            onProgress = { percent ->
                if (isFinishing || isDestroyed) return@downloadApk
                updateStatus = UpdateStatus.Downloading(info, percent)
                adapter.refreshInfoPage()
            },
            onComplete = { file, error ->
                if (isFinishing || isDestroyed) return@downloadApk
                if (file == null) {
                    updateStatus = UpdateStatus.Error(error ?: "Не удалось скачать обновление")
                    adapter.refreshInfoPage()
                    return@downloadApk
                }
                // Despite this function's name, install only happens once the
                // person taps "Установить" — see onUpdateButtonClicked.
                updateStatus = UpdateStatus.ReadyToInstall(info, file)
                adapter.refreshInfoPage()
            }
        )
    }

    /**
     * "Установить" was tapped on a downloaded update. Connects over ADB in
     * the background (AdbUpdateInstaller) and installs via `pm install`,
     * which isn't blocked the way the normal system-installer screen is on
     * this firmware. If nothing is reachable (Wireless debugging is off),
     * tells the person what to enable; if a pairing screen is open on the
     * watch, asks for its code once.
     */
    private fun installUpdate(info: UpdateInfo, apkFile: File) {
        updateStatus = UpdateStatus.Installing(info, apkFile, "Подключаюсь по ADB…")
        adapter.refreshInfoPage()
        AdbUpdateInstaller.tryInstall(this, apkFile) { result ->
            if (isFinishing || isDestroyed) return@tryInstall
            handleAdbResult(result, info, apkFile)
        }
    }

    private fun handleAdbResult(result: AdbUpdateInstaller.Result, info: UpdateInfo, apkFile: File) {
        when (result) {
            is AdbUpdateInstaller.Result.Success -> {
                updateStatus = UpdateStatus.Idle
                adapter.refreshInfoPage()
                Toast.makeText(this, "Обновление установлено", Toast.LENGTH_LONG).show()
            }
            is AdbUpdateInstaller.Result.NeedsPairing -> {
                updateStatus = UpdateStatus.ReadyToInstall(info, apkFile)
                adapter.refreshInfoPage()
                showPairingDialog(result.port, info, apkFile)
            }
            is AdbUpdateInstaller.Result.NotAvailable -> {
                updateStatus = UpdateStatus.ReadyToInstall(info, apkFile)
                adapter.refreshInfoPage()
                showEnableWirelessDebuggingDialog()
            }
            is AdbUpdateInstaller.Result.Error -> {
                updateStatus = UpdateStatus.ReadyToInstall(info, apkFile)
                adapter.refreshInfoPage()
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showEnableWirelessDebuggingDialog() {
        AlertDialog.Builder(this)
            .setTitle("Нужна отладка по Wi-Fi")
            .setMessage(
                "Обычная установка на этой прошивке заблокирована, поэтому " +
                    "обновление ставится через отладку по Wi-Fi.\n\n" +
                    "Настройки → Система → Для разработчиков → " +
                    "«Отладка по Wi-Fi» — включи её, затем нажми «Установить» ещё раз.\n\n" +
                    "Если пункта «Для разработчиков» нет, сначала открой " +
                    "О часах и несколько раз нажми на номер сборки, пока не " +
                    "появится сообщение, что режим разработчика включён."
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    /**
     * One-time pairing screen for when AdbUpdateInstaller found a "Pair
     * device with pairing code" service running (i.e. Wireless debugging is
     * on and the person has that screen open on the watch, showing a
     * 6-digit code). Plain AlertDialog + EditText rather than a separate
     * Activity — this only ever happens once per factory reset.
     */
    private fun showPairingDialog(port: Int, info: UpdateInfo, apkFile: File) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6-значный код"
        }
        AlertDialog.Builder(this)
            .setTitle("Привязка по коду")
            .setMessage(
                "На часах открыт экран «Подключить устройство по коду» — " +
                    "введи показанный там код."
            )
            .setView(input)
            .setPositiveButton("Привязать") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isEmpty()) {
                    Toast.makeText(this, "Код не введён", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                updateStatus = UpdateStatus.Installing(info, apkFile, "Привязываю и подключаюсь…")
                adapter.refreshInfoPage()
                AdbUpdateInstaller.pairAndInstall(this, port, code, apkFile) { result ->
                    if (isFinishing || isDestroyed) return@pairAndInstall
                    handleAdbResult(result, info, apkFile)
                }
            }
            .setNegativeButton("Отмена") { _, _ ->
                updateStatus = UpdateStatus.ReadyToInstall(info, apkFile)
                adapter.refreshInfoPage()
            }
            .show()
    }

    /** Reloads the slot list from storage and re-binds the slot-list page. */
    private fun refreshSlots() {
        adapter.submitSlots(TargetAppPrefs.getSlots(this))
    }

    /**
     * Gives input focus to the ViewPager2's currently visible page so the
     * rotary crown/bezel scrolls it (see RotaryInput.kt) instead of whatever
     * page last had focus. ViewPager2 wraps a plain RecyclerView as its only
     * direct child — not official API, hence the defensive cast/try-catch —
     * which is how the currently bound page's View is found.
     */
    private fun focusCurrentPage(position: Int) {
        pager.post {
            try {
                val recycler = pager.getChildAt(0) as? RecyclerView
                recycler?.findViewHolderForAdapterPosition(position)?.itemView?.requestRotaryFocus()
            } catch (e: Exception) {
                // Falls back to whatever last had focus; touch scrolling is
                // unaffected either way.
            }
        }
    }

    // ---- Permissions ----

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(): Boolean = hasPermission(Manifest.permission.RECORD_AUDIO)

    private fun hasContactsPermission(): Boolean = hasPermission(Manifest.permission.READ_CONTACTS)

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun requestPermissionsIfNeeded() {
        if (hasMicPermission()) {
            requestSecondaryPermissionsIfNeeded()
        } else {
            requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestSecondaryPermissionsIfNeeded() {
        val missing = mutableListOf<String>()
        if (!hasContactsPermission()) missing.add(Manifest.permission.READ_CONTACTS)
        if (!hasPermission(Manifest.permission.CALL_PHONE)) missing.add(Manifest.permission.CALL_PHONE)
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            missing.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isEmpty()) {
            loadPickerDataAsync()
            requestOverlayPermissionIfNeeded()
        } else {
            requestSecondaryPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (hasOverlayPermission()) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            // No overlay-permission settings screen on this ROM — the app still
            // works, just falls back to the notification path.
        }
    }

    // ---- Accessibility service status ----

    /**
     * Android provides no direct API to check whether a specific
     * AccessibilityService is enabled — the documented approach is to read the
     * colon-separated list of enabled services from Settings.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${VoiceAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * One status pill per thing that can silently break the app. Accessibility
     * can't be requested programmatically (see class doc), so it's checked
     * separately from the four runtime permissions below, which
     * requestPermissionsIfNeeded() does prompt for automatically.
     */
    private fun buildStatus(): ServiceStatus {
        val accessibilityOn = isAccessibilityServiceEnabled()
        val micOk = hasMicPermission()
        val contactsOk = hasContactsPermission()
        // Both matter for a CALL slot: CALL_PHONE places the call,
        // READ_PHONE_STATE is what lets the call-state listener force a mic
        // restart the instant a call ends (see registerCallStateListener) —
        // without it the app falls back to a fixed delay only. A "Телефон"
        // pill that only checked one of the two would read green while that
        // fallback was silently in effect.
        val phoneOk = hasPermission(Manifest.permission.CALL_PHONE) &&
            hasPermission(Manifest.permission.READ_PHONE_STATE)
        val overlayOk = hasOverlayPermission()

        return ServiceStatus(
            accessibility = StatusItem(
                if (accessibilityOn) "Спец. возможности: включены" else "Спец. возможности: выключены",
                accessibilityOn
            ),
            microphone = StatusItem(
                if (micOk) "Микрофон: разрешён" else "Микрофон: нет разрешения",
                micOk
            ),
            contacts = StatusItem(
                if (contactsOk) "Контакты: разрешены" else "Контакты: нет разрешения",
                contactsOk
            ),
            phone = StatusItem(
                if (phoneOk) "Телефон: разрешён" else "Телефон: нет разрешения",
                phoneOk
            ),
            overlay = StatusItem(
                if (overlayOk) "Поверх других приложений: разрешено" else "Поверх других приложений: нет разрешения",
                overlayOk
            )
        )
    }
}
