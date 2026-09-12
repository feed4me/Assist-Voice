package com.nikolay.assistvoice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
 * info (one status pill + repo QR code + update/permission buttons), the
 * slot list (add button plus one row per voice command, tap a row to edit
 * it in SlotEditActivity), microphone-gate (VAD) tuning, mic-icon appearance,
 * then a closing support page (donation and social QR codes).
 *
 * Nothing is requested automatically on open, and nothing on the info page
 * is individually tappable — a single non-interactive status pill just
 * reads "Не все разрешения предоставлены" (warm) or "Все разрешения
 * предоставлены" (green), recomputed fresh on every bind (buildStatus()).
 * "Выдать разрешения" below it is the one and only way to grant anything:
 * connects over ADB (AdbUpdateInstaller.grantPermissions()) and requests
 * every permission this app uses at once, including ones whose normal
 * on-screen flow can be blocked on some firmware — SYSTEM_ALERT_WINDOW's
 * Settings screen reported "Unavailable" on a Samsung Galaxy Watch, and
 * this app's own Accessibility/Battery screens aren't exported on this
 * Huawei build. It has no separate readout of its own: the pill above just
 * refreshes afterward and turns green once everything actually stuck.
 *
 * The actual listening logic lives in VoiceAccessibilityService, which
 * Android does not allow an app to enable programmatically — the person
 * must turn it on manually in system Settings → Accessibility, or via
 * "Выдать разрешения" above.
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

    /** True while a "Выдать разрешения" ADB attempt is in flight — see
     * onGrantPermissionsButtonClicked()/handleGrantResult() and
     * SlotsAdapter.InfoViewHolder.bindGrantPermissionsSection(). Only ever
     * changes the button's own enabled state/text; success or failure is
     * reflected by the status pill refreshing, not by a separate readout. */
    private var isGrantingPermissions: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

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
            onUpdateButtonClicked = { onUpdateButtonClicked() },
            isGrantingPermissions = { isGrantingPermissions },
            onGrantPermissionsButtonClicked = { onGrantPermissionsButtonClicked() },
            onOpenYandexSmartHome = { openYandexSmartHome() }
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
        adapter.refreshIntegrationsPage()
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
     * the watch rebooting mid-update) and it's left disabled.
     *
     * Gated on isReenablePending(): that's only true while an update this
     * app itself triggered disabled the package installer and hasn't yet
     * been confirmed to have put it back. Without that gate this would
     * fight anyone who deliberately keeps it disabled themselves — on this
     * firmware that's the only way to sideload APKs at all, since the
     * normal system-installer path is blocked (see AdbUpdateInstaller's
     * class doc) — by silently re-enabling it every time they open the app.
     */
    private fun repairPackageInstallerIfNeeded() {
        if (!AdbUpdateInstaller.isReenablePending(this)) return
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
        } else {
            AdbUpdateInstaller.clearReenablePending(this)
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
     *
     * Every other manufacturer installs the normal way instead
     * (UpdateInstaller.promptInstall() — hands the APK straight to the
     * system installer via FileProvider) — confirmed on Samsung that this
     * just works, no ADB involved at all. promptInstall() has no
     * success/failure callback of its own (Android gives none for "the
     * person accepted the install dialog"), so the status just stays
     * ReadyToInstall either way and the person can tap "Установить" again
     * if it didn't actually go through.
     */
    private fun installUpdate(info: UpdateInfo, apkFile: File) {
        if (!AdbUpdateInstaller.isHuawei()) {
            UpdateInstaller.promptInstall(this, apkFile)
            updateStatus = UpdateStatus.ReadyToInstall(info, apkFile)
            adapter.refreshInfoPage()
            return
        }
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
                showAdbPairingDialog(
                    onCode = { code ->
                        updateStatus = UpdateStatus.Installing(info, apkFile, "Привязываю и подключаюсь…")
                        adapter.refreshInfoPage()
                        AdbUpdateInstaller.pairAndInstall(this, result.host, result.port, code, apkFile) { r ->
                            if (isFinishing || isDestroyed) return@pairAndInstall
                            handleAdbResult(r, info, apkFile)
                        }
                    },
                    onCancelled = {
                        updateStatus = UpdateStatus.ReadyToInstall(info, apkFile)
                        adapter.refreshInfoPage()
                    }
                )
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

    /**
     * Shared by the update-install and "Выдать разрешения" ADB flows (see
     * handleAdbResult and handleGrantResult) — kept deliberately generic
     * (no mention of which button to press) so neither caller's wording
     * goes stale for the other.
     */
    private fun showEnableWirelessDebuggingDialog() {
        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Нужна отладка по Wi-Fi")
            .setMessage(
                "Для разработчиков → «Отладка по Wi-Fi» — включи её и попробуй ещё раз."
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    /**
     * One-time pairing screen for when AdbUpdateInstaller found a "Pair
     * device with pairing code" service running (i.e. Wireless debugging is
     * on and the person has that screen open on the watch, showing a
     * 6-digit code). Plain AlertDialog + EditText rather than a separate
     * Activity — this only ever happens once per factory reset. Shared
     * between the update-install and the permission-grant ADB flows (see
     * handleAdbResult and handleGrantResult) — the only difference between
     * them is what happens once a code is entered or the dialog is
     * cancelled, left entirely to the caller.
     */
    private fun showAdbPairingDialog(onCode: (String) -> Unit, onCancelled: () -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6-значный код"
        }
        AlertDialog.Builder(this, R.style.AppDialogTheme)
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
                onCode(code)
            }
            .setNegativeButton("Отмена") { _, _ -> onCancelled() }
            .show()
    }

    /**
     * "Выдать разрешения" on the info page — connects over ADB
     * the same way installUpdate does and grants everything AdbUpdateInstaller
     * .grantPermissions() covers (SYSTEM_ALERT_WINDOW, REQUEST_INSTALL_PACKAGES,
     * battery whitelist, the accessibility service, the ordinary runtime
     * permissions), for firmware where the corresponding on-screen flow is
     * blocked. Same NeedsPairing/NotAvailable fallback as an update install.
     * Never shows its own success/failure readout — the status pill above
     * just refreshes afterward and reflects the truth on its own.
     */
    private fun onGrantPermissionsButtonClicked() {
        isGrantingPermissions = true
        adapter.refreshInfoPage()
        AdbUpdateInstaller.grantPermissions(this) { result ->
            if (isFinishing || isDestroyed) return@grantPermissions
            handleGrantResult(result)
        }
    }

    private fun handleGrantResult(result: AdbUpdateInstaller.GrantResult) {
        when (result) {
            is AdbUpdateInstaller.GrantResult.Success -> {
                isGrantingPermissions = false
                adapter.refreshInfoPage()
                loadPickerDataAsync()
            }
            is AdbUpdateInstaller.GrantResult.NeedsPairing -> {
                isGrantingPermissions = false
                adapter.refreshInfoPage()
                showAdbPairingDialog(
                    onCode = { code ->
                        isGrantingPermissions = true
                        adapter.refreshInfoPage()
                        AdbUpdateInstaller.pairAndGrantPermissions(this, result.host, result.port, code) { r ->
                            if (isFinishing || isDestroyed) return@pairAndGrantPermissions
                            handleGrantResult(r)
                        }
                    },
                    onCancelled = {
                        isGrantingPermissions = false
                        adapter.refreshInfoPage()
                    }
                )
            }
            is AdbUpdateInstaller.GrantResult.NotAvailable -> {
                isGrantingPermissions = false
                adapter.refreshInfoPage()
                showEnableWirelessDebuggingDialog()
            }
            is AdbUpdateInstaller.GrantResult.Error -> {
                isGrantingPermissions = false
                adapter.refreshInfoPage()
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Reloads the slot list from storage and re-binds the slot-list page. */
    private fun refreshSlots() {
        adapter.submitSlots(TargetAppPrefs.getSlots(this))
    }

    /**
     * "Умный дом Яндекса" tile on the Integrations page — first tap starts
     * the Device Flow QR/code screen, every tap after a successful
     * authorization goes straight to the hub (devices/groups/disconnect)
     * instead. onResume() re-checks this every time the person comes back
     * from either screen, so signing out or a token failure there is
     * reflected here without any extra plumbing.
     */
    private fun openYandexSmartHome() {
        val intent = if (SmartHomePrefs.isAuthorized(this)) {
            SmartHomeHubActivity.intent(this)
        } else {
            SmartHomeAuthActivity.intent(this)
        }
        startActivity(intent)
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

    private fun hasBatteryPermission(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: false

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
     * True only once every permission "Выдать разрешения" also covers is
     * actually granted — recomputed fresh on every bind of the info page's
     * single status pill (see SlotsAdapter.InfoViewHolder), so the two stay
     * in sync automatically instead of by hand.
     */
    private fun buildStatus(): Boolean {
        val accessibilityOn = isAccessibilityServiceEnabled()
        val micOk = hasMicPermission()
        val contactsOk = hasContactsPermission()
        // All three matter for phone-related features: CALL_PHONE places a
        // CALL slot's call, READ_PHONE_STATE is what lets the call-state
        // listener force a mic restart the instant a call ends (see
        // registerCallStateListener) and detect an incoming ring in the
        // first place, and ANSWER_PHONE_CALLS is what lets "прими звонок"/
        // "отклони звонок" actually do anything.
        val phoneOk = hasPermission(Manifest.permission.CALL_PHONE) &&
            hasPermission(Manifest.permission.READ_PHONE_STATE) &&
            hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)
        // Below API 33 this permission doesn't exist as a concept, and
        // checkSelfPermission() documents itself as returning granted for a
        // permission the running platform doesn't know about — so this
        // reads true there on its own, no version check needed.
        val notificationsOk = hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        val overlayOk = hasOverlayPermission()
        val batteryOk = hasBatteryPermission()

        return accessibilityOn && micOk && contactsOk && phoneOk &&
            notificationsOk && overlayOk && batteryOk
    }
}
