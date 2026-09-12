package com.nikolay.assistvoice

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Installs an already-downloaded update APK by talking to this watch's own
 * Android Debug Bridge over Wireless Debugging (Android 11+), entirely
 * on-device — no PC, no second device, nothing else installed. This exists
 * because the normal "tap the APK, system installer opens" path is blocked
 * at the firmware level on this Huawei watch even with
 * REQUEST_INSTALL_PACKAGES granted; `pm install` run over an ADB shell is a
 * different code path that isn't.
 *
 * Talks to adbd by running the real, bundled `adb` client binary (built from
 * AOSP — see app/src/main/jniLibs, per-ABI libadb.so, Apache-2.0) as a
 * subprocess, the same way `adb` on a PC does, rather than reimplementing the
 * ADB wire protocol in Kotlin (approach borrowed from tytydraco/LADB,
 * BSD-style license, see jniLibs/LICENSE for the bundled binary's
 * Apache-2.0 terms).
 *
 * Connecting is layered, cheapest first (see [connectOrDiscoverPairing]):
 * (1) on Huawei only, [waitForDevice] polls `adb devices`, hoping
 * `adb start-server`'s own background auto-connect already found and
 * connected the watch on its own — confirmed sufficient there, where this
 * alone has always been enough. Skipped entirely on every other
 * manufacturer: every attempt kills its own adb server when it's done (see
 * [runAttempt]), so a fresh server never has anything already connected to
 * find there, and nothing here makes one auto-connect either — spending up
 * to [DEVICE_WAIT_TIMEOUT_MS] finding that out would just be a guaranteed-
 * empty wait on every single attempt. (2) failing that (or skipped), an
 * explicit `adb connect <host>:<port>`
 * to whatever address the watch's own `_adb-tls-connect._tcp` mDNS
 * advertisement (an already-trusted-device service, distinct from the
 * pairing one below) reports — confirmed necessary on a Samsung Galaxy
 * Watch, where nothing connects on its own even though the service is
 * right there; (3) only if neither finds a connected device does this
 * fall back to looking for a `_adb-tls-pairing._tcp` "pair device with
 * code" screen. Both lookups go through Android's own NsdManager rather
 * than the bundled adb's `mdns services`, whose two discovery backends are
 * both unusable from inside an app sandbox — see [discoverViaNsd], which is
 * why pairing never got off the ground on anything but Huawei. The host
 * used in (2) and for pairing itself is always whatever that discovery
 * actually reports for the service, not an
 * assumed `localhost` — even though this client runs on the very watch
 * it's debugging, `adbd`'s wireless-debugging TLS listener isn't
 * guaranteed to be bound to loopback (it's designed for a separate PC on
 * the same Wi-Fi to reach it, which only loopback-only binding would
 * break). A third-party ADB client that connects fine on the same Samsung
 * watch does exactly this — connects to the resolved address, falling
 * back to `127.0.0.1` only if resolution gives nothing — which is the
 * strongest available evidence for what `localhost` was missing there.
 *
 * The bundled `adb` manages its own auth keypair under `$HOME/.android/`
 * (HOME pointed at this app's private files dir below) exactly like it would
 * on a PC — that persists across app runs, so pairing only has to happen once
 * per factory reset. The local adb *server* process it spawns does not: every
 * attempt ends with `adb kill-server` (see [runAttempt]), so nothing from
 * this keeps running — or using battery/memory — outside the window an
 * update install is actually in progress.
 *
 * A few steps only make sense on Huawei's own EMUI firmware, gated on
 * [isHuawei] (`Build.MANUFACTURER`, no ADB needed to check it): disabling
 * the system package installer around `pm install` (see [installOverAdb] —
 * its own normal "tap the APK" install path is blocked at the firmware
 * level there, unlike Samsung, where it just works once
 * REQUEST_INSTALL_PACKAGES is granted — see UpdateInstaller.promptInstall()
 * and MainActivity.installUpdate() for that non-Huawei-first attempt).
 *
 * Also grants (see [grantPermissions]/[pairAndGrantPermissions]) whatever
 * this app needs that the normal on-device UI flow for can be blocked on
 * some firmware — SYSTEM_ALERT_WINDOW's Settings screen reported
 * "Unavailable" on a Samsung Galaxy Watch even though the permission itself
 * isn't fundamentally unreachable (a third-party sideloading tool managed
 * to grant it there over ADB), and this app's own Accessibility settings
 * screen is unexported on this Huawei build (see MainActivity's class doc)
 * — same underlying trick as `pm install`, a different code path than the
 * blocked one. Reuses the exact same connect/pair machinery as the update
 * install above, so the same Wireless-debugging-must-be-on prerequisite and
 * NeedsPairing/NotAvailable fallback apply.
 */
object AdbUpdateInstaller {

    private const val TAG = "AdbUpdateInstaller"
    private const val REMOTE_APK_NAME = "assistvoice-update.apk"
    private const val REMOTE_LOG_NAME = "assistvoice-update.log"
    private const val PAIR_SERVICE_TYPE = "_adb-tls-pairing._tcp"
    // Advertised whenever Wireless debugging is on and at least one key is
    // already trusted — distinct from PAIR_SERVICE_TYPE, which only exists
    // while a pairing-code screen is open. See connectOrDiscoverPairing().
    private const val CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp"
    private const val POLL_INTERVAL_MS = 500L
    // Bounds how long installOverAdb waits for the detached install chain
    // (see its doc comment) to report a result before giving up on reading
    // it back — this app's own process is expected to die partway through
    // most of the time, which is fine: the chain it's polling for keeps
    // running on its own either way.
    private const val INSTALL_POLL_TIMEOUT_MS = 20_000L
    // ensurePackageInstallerEnabled only ever has anything to reconnect to
    // right after a previous install already established ADB trust, so
    // unlike DEVICE_WAIT_TIMEOUT_MS below this doesn't need to wait around
    // for a person to notice and tap anything — a closed/never-opened
    // Wireless debugging just means there's nothing to repair.
    private const val SELF_REPAIR_WAIT_TIMEOUT_MS = 5_000L
    private const val KEY_PENDING_REENABLE = "pending_packageinstaller_reenable"
    // Getting here at all can require the person to notice and tap a
    // system "Allow debugging" dialog, and adb's own background
    // auto-connect (see class doc) runs on its own timeline, not ours — a
    // short timeout just means giving up before they've had a chance to
    // respond.
    private const val DEVICE_WAIT_TIMEOUT_MS = 30_000L
    // Bounds re-checking adb devices right after this class itself just
    // triggered a connection (an explicit adb connect, or a fresh pair()) —
    // much shorter than DEVICE_WAIT_TIMEOUT_MS above since there's no
    // waiting on a person to notice anything here, just letting the
    // connection actually establish.
    private const val RECONNECT_WAIT_TIMEOUT_MS = 8_000L
    // How long to look for a _adb-tls-pairing._tcp "pair with code" screen.
    // Confirmed (via a third-party ADB client on a Samsung Galaxy Watch)
    // that this service is discoverable pretty much immediately once
    // Wireless debugging is on — this used to be a deliberately short 3s on
    // the theory that Huawei's firmware never advertises it at all, which
    // is still true, but a short timeout only helped Huawei (fails fast
    // regardless) while actively hurting anything that does need pairing.
    private const val PAIRING_DISCOVERY_TIMEOUT_MS = 15_000L
    // One NsdManager lookup of the already-trusted connect service. Short on
    // purpose: that service is either registered (Wireless debugging is on)
    // or it isn't — unlike the pairing screen, nobody has to walk over and
    // tap anything first for it to appear.
    private const val NSD_DISCOVERY_TIMEOUT_MS = 6_000L
    private const val NSD_RESOLVE_TIMEOUT_MS = 6_000L
    private val mainHandler = Handler(Looper.getMainLooper())

    sealed class Result {
        object Success : Result()
        object NotAvailable : Result()
        data class NeedsPairing(val host: String, val port: Int) : Result()
        data class Error(val message: String) : Result()
    }

    /** Outcome of [connectOrDiscoverPairing] — shared by every entry point
     * below so the three-stage connect logic lives in exactly one place. */
    private sealed class ConnectOutcome {
        data class Connected(val serial: String) : ConnectOutcome()
        data class NeedsPairing(val host: String, val port: Int) : ConnectOutcome()
        object NotAvailable : ConnectOutcome()
    }

    /** Outcome of [grantPermissions]/[pairAndGrantPermissions]. Mirrors
     * [Result]'s shape. Success carries nothing of its own — the status pills
     * on the info page are the single source of truth for what actually got
     * granted, refreshed after every attempt regardless of outcome. */
    sealed class GrantResult {
        object Success : GrantResult()
        object NotAvailable : GrantResult()
        data class NeedsPairing(val host: String, val port: Int) : GrantResult()
        data class Error(val message: String) : GrantResult()
    }

    /** A resolved `host:port` for one mDNS-advertised service — see
     * [queryMdnsAddress]. [host] is used exactly as `adb mdns services`
     * reports it (never assumed to be loopback) — see this file's class
     * doc for why that matters. */
    private data class MdnsAddress(val host: String, val port: Int)

    /**
     * Tries to install [apkFile] using whatever ADB trust this app already
     * has (from a previous successful pairing). Never throws — always
     * delivers exactly one [Result] to [callback] on the main thread.
     */
    fun tryInstall(context: Context, apkFile: File, callback: (Result) -> Unit) {
        runAttempt(context, callback) {
            startServer(context)
            when (val outcome = connectOrDiscoverPairing(context)) {
                is ConnectOutcome.Connected -> installOverAdb(context, outcome.serial, apkFile)
                is ConnectOutcome.NeedsPairing -> Result.NeedsPairing(outcome.host, outcome.port)
                ConnectOutcome.NotAvailable -> Result.NotAvailable
            }
        }
    }

    /**
     * One-time pairing using the 6-digit code shown on the watch's own
     * "Pair device with pairing code" screen (Settings → System → For
     * developers → Wireless debugging), then installs. The pairing itself
     * only needs to happen once per factory-reset — after that [tryInstall]
     * reconnects using the trust it already established.
     */
    fun pairAndInstall(
        context: Context,
        host: String,
        port: Int,
        pairingCode: String,
        apkFile: File,
        callback: (Result) -> Unit
    ) {
        runAttempt(context, callback) {
            val paired = pair(context, host, port, pairingCode)
            if (!paired) {
                Result.Error("Код не подошёл — проверь и введи заново")
            } else {
                val serial = connectAfterPairing(context)
                if (serial != null) {
                    installOverAdb(context, serial, apkFile)
                } else {
                    Result.Error("Спарились, но не удалось подключиться — нажми «Установить» ещё раз")
                }
            }
        }
    }

    /**
     * Tries to grant everything [runGrants] covers using whatever ADB trust
     * this app already has. Same shape as [tryInstall]: never throws, always
     * delivers exactly one [GrantResult] to [callback] on the main thread.
     */
    fun grantPermissions(context: Context, callback: (GrantResult) -> Unit) {
        runGrantAttempt(context, callback) {
            startServer(context)
            when (val outcome = connectOrDiscoverPairing(context)) {
                is ConnectOutcome.Connected -> {
                    runGrants(context, outcome.serial)
                    GrantResult.Success
                }
                is ConnectOutcome.NeedsPairing -> GrantResult.NeedsPairing(outcome.host, outcome.port)
                ConnectOutcome.NotAvailable -> GrantResult.NotAvailable
            }
        }
    }

    /** [grantPermissions]'s NeedsPairing follow-up — see [pairAndInstall]. */
    fun pairAndGrantPermissions(
        context: Context,
        host: String,
        port: Int,
        pairingCode: String,
        callback: (GrantResult) -> Unit
    ) {
        runGrantAttempt(context, callback) {
            val paired = pair(context, host, port, pairingCode)
            if (!paired) {
                GrantResult.Error("Код не подошёл — проверь и введи заново")
            } else {
                val serial = connectAfterPairing(context)
                if (serial != null) {
                    runGrants(context, serial)
                    GrantResult.Success
                } else {
                    GrantResult.Error("Спарились, но не удалось подключиться — нажми ещё раз")
                }
            }
        }
    }

    /**
     * Shared by [tryInstall]/[grantPermissions]: cheapest check first
     * (already-connected, per adb devices — Huawei only, see below), then an
     * explicit connect to the _adb-tls-connect._tcp port if that's not
     * enough, then a pairing-screen lookup only as the last resort. See this
     * file's class doc for why all three stages exist.
     *
     * The first stage only ever runs on Huawei. It exists purely to give
     * adb's own background auto-connect (and the person a moment to notice
     * and tap Huawei's "Allow debugging" dialog) a window to land in —
     * confirmed that alone is sufficient there. On every other
     * manufacturer this never once succeeds: every attempt kills its own
     * adb server when it's done (see [runAttempt]/[runGrantAttempt]), so
     * there's nothing already connected for a fresh server to find, and
     * nothing here makes it auto-connect either — that's the whole reason
     * the explicit-connect stage below exists. Skipping straight to it
     * saves a full [DEVICE_WAIT_TIMEOUT_MS] of guaranteed-empty waiting on
     * every non-Huawei attempt.
     */
    private fun connectOrDiscoverPairing(context: Context): ConnectOutcome {
        if (isHuawei()) {
            val already = waitForDevice(context, DEVICE_WAIT_TIMEOUT_MS)
            if (already != null) return ConnectOutcome.Connected(already)
        }

        val connectAddress = queryMdnsAddress(context, CONNECT_SERVICE_TYPE)
        if (connectAddress != null) {
            connect(context, connectAddress)
            val serial = waitForDevice(context, RECONNECT_WAIT_TIMEOUT_MS)
            if (serial != null) return ConnectOutcome.Connected(serial)
        }

        val pairingAddress = discoverPairingAddress(context, PAIRING_DISCOVERY_TIMEOUT_MS)
        return if (pairingAddress != null) {
            ConnectOutcome.NeedsPairing(pairingAddress.host, pairingAddress.port)
        } else {
            ConnectOutcome.NotAvailable
        }
    }

    /**
     * Shared by [pairAndInstall]/[pairAndGrantPermissions]: pair() only
     * establishes trust, it does not connect — same explicit-connect step as
     * the middle stage of [connectOrDiscoverPairing], tried right after a
     * successful pairing instead of from scratch. Same short
     * [RECONNECT_WAIT_TIMEOUT_MS] confirmation wait as that stage uses once
     * [connect] actually ran — there's no reason to wait as long as a
     * from-scratch attempt does for something that just fired. Only falls
     * back to the longer [DEVICE_WAIT_TIMEOUT_MS] in the one case nothing
     * active happened here at all (the connect service wasn't advertised
     * yet right after pairing) and this is purely hoping something else
     * (background auto-connect, on hardware where that's a thing) finds it.
     */
    private fun connectAfterPairing(context: Context): String? {
        // Longer discovery window than a from-scratch attempt uses: adbd
        // registers _adb-tls-connect._tcp as part of accepting the pairing,
        // so this can be looking for it in the moments before it exists.
        val connectAddress =
            queryMdnsAddress(context, CONNECT_SERVICE_TYPE, PAIRING_DISCOVERY_TIMEOUT_MS)
        if (connectAddress != null) {
            connect(context, connectAddress)
            return waitForDevice(context, RECONNECT_WAIT_TIMEOUT_MS)
        }
        return waitForDevice(context, DEVICE_WAIT_TIMEOUT_MS)
    }

    private fun runGrantAttempt(
        context: Context,
        callback: (GrantResult) -> Unit,
        attempt: () -> GrantResult
    ) {
        AdbInstallForegroundService.start(context)
        Thread {
            val result = try {
                withMulticastLock(context) { attempt() }
            } catch (e: Exception) {
                Log.e(TAG, "ADB grant attempt failed", e)
                GrantResult.Error(e.message ?: "Не удалось подключиться по ADB")
            } finally {
                killServer(context)
                AdbInstallForegroundService.stop(context)
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    /**
     * Runs every grant this button covers against an already-connected
     * [serial]. Each item is independent and best-effort — one failing (e.g.
     * POST_NOTIFICATIONS on a pre-Tiramisu build, where the permission
     * doesn't exist to grant) doesn't stop the rest. Nothing here reports
     * its own success — MainActivity just re-reads the real permission
     * state afterward (the same status pills a person could tap themselves),
     * so this only needs to try, not to prove it worked.
     */
    private fun runGrants(context: Context, serial: String) {
        val pkg = context.applicationContext.packageName
        shell(context, serial, "appops set $pkg SYSTEM_ALERT_WINDOW allow")
        // Lets UpdateInstaller.promptInstall() hand APKs straight to the
        // system installer without the manual "Неизвестные источники"
        // detour — same appops trick third-party sideloading tools (and
        // apps like Telegram) use to self-install without ever touching
        // Settings.
        shell(context, serial, "appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        shell(context, serial, "dumpsys deviceidle whitelist +$pkg")
        grantAccessibilityService(context, serial, pkg)
        grantRuntimePermissions(context, serial, pkg)
    }

    /**
     * Appends this app's own service to the enabled_accessibility_services
     * list rather than overwriting it — that setting is one shared,
     * colon-separated list for every accessibility service on the device,
     * and a plain `settings put` would silently turn off anything else the
     * person already had enabled.
     */
    private fun grantAccessibilityService(context: Context, serial: String, pkg: String) {
        val component = "$pkg/${VoiceAccessibilityService::class.java.name}"
        val current = shell(context, serial, "settings get secure enabled_accessibility_services").trim()
        if (!current.contains(component)) {
            val updated = if (current.isEmpty() || current == "null") {
                component
            } else {
                "$current:$component"
            }
            shell(context, serial, "settings put secure enabled_accessibility_services $updated")
            shell(context, serial, "settings put secure accessibility_enabled 1")
        }
    }

    // Every runtime permission MainActivity otherwise requests through
    // system dialogs (see its class doc) — granted here too in case one of
    // those dialogs is itself blocked on some firmware, the same way
    // SYSTEM_ALERT_WINDOW's Settings screen was.
    private val RUNTIME_PERMISSIONS = listOf(
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_CONTACTS",
        "android.permission.CALL_PHONE",
        "android.permission.READ_PHONE_STATE",
        "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.POST_NOTIFICATIONS"
    )

    /** `pm grant`'s own output is empty on success; any text back (a
     * SecurityException, an unknown-permission complaint) is just logged —
     * whichever pill it corresponds to simply stays showing afterward. */
    private fun grantRuntimePermissions(context: Context, serial: String, pkg: String) {
        for (permission in RUNTIME_PERMISSIONS) {
            val output = shell(context, serial, "pm grant $pkg $permission").trim()
            if (output.isNotEmpty()) {
                Log.i(TAG, "pm grant $permission output: $output")
            }
        }
    }

    /**
     * Runs [attempt] on a background thread, with the process held in the
     * foreground (AdbInstallForegroundService) for exactly its duration —
     * this firmware kills backgrounded processes aggressively, and the
     * system's own "Allow debugging" screen pushes this app to the
     * background while the person deals with it. `adb kill-server` always
     * runs afterward, success or failure, so the local adb server this
     * needs while working never lingers once it's done.
     */
    private fun runAttempt(context: Context, callback: (Result) -> Unit, attempt: () -> Result) {
        AdbInstallForegroundService.start(context)
        Thread {
            val result = try {
                withMulticastLock(context) { attempt() }
            } catch (e: Exception) {
                Log.e(TAG, "ADB install attempt failed", e)
                Result.Error(e.message ?: "Не удалось установить через ADB")
            } finally {
                killServer(context)
                AdbInstallForegroundService.stop(context)
            }
            deliver(callback, result)
        }.start()
    }

    private fun installOverAdb(context: Context, serial: String, apkFile: File): Result {
        // pm runs as the `shell` user over ADB and can't read this app's
        // private cache dir, so push the APK to /data/local/tmp first —
        // system_server's mmap of an APK under
        // /storage/emulated/0/Android/data/... is unreliable on Android's
        // FUSE layer, which is why pm install targets a real filesystem path
        // instead.
        val remotePath = "/data/local/tmp/$REMOTE_APK_NAME"
        val logPath = "/data/local/tmp/$REMOTE_LOG_NAME"
        if (!push(context, serial, apkFile, remotePath)) {
            return Result.Error("Не удалось скопировать APK на часы")
        }

        // Disabling the package installer around pm install is a Huawei-only
        // workaround (see this file's class doc) — Samsung and other
        // manufacturers install fine without it, and disabling a system
        // component nobody asked to have disabled is not something to do
        // "just in case" on hardware where it was never necessary.
        val huawei = isHuawei()
        if (huawei) {
            // Recorded so a future launch's repair check (see
            // ensurePackageInstallerEnabled and its callers) only ever fixes
            // a disable *this app* caused — never a person's own deliberate
            // "keep it off so I can sideload APKs pm-install can't otherwise
            // put on" choice made outside of an update.
            markReenablePending(context, true)
            val disableOutput = shell(context, serial, "pm disable-user --user 0 com.android.packageinstaller")
            Log.i(TAG, "pm disable-user output: ${disableOutput.trim()}")
        }

        // `pm install` kills every process under this app's UID as part of
        // applying the update — including this app's local adb client,
        // which is what's holding the adb shell session this command would
        // otherwise run in. That tears the session down (adbd hangs up its
        // remote shell child once the client disconnects), so a plain
        // `cmd1; cmd2; cmd3` chain launched in that same session can die
        // along with `pm install` before `pm enable`/`rm` ever get to run —
        // leaving the package installer disabled for good. `setsid` puts
        // the whole chain in its own session and `nohup` ignores the
        // hangup too, so once launched it keeps running fully detached
        // from this session no matter what happens to us. This call only
        // starts that detached chain and returns immediately — it does not
        // wait for install to finish, so the actual result is read back
        // separately via pollInstallResult().
        val remoteCommand = if (huawei) {
            "pm install -r $remotePath; pm enable --user 0 com.android.packageinstaller; rm -f $remotePath"
        } else {
            "pm install -r $remotePath; rm -f $remotePath"
        }
        shell(
            context,
            serial,
            "setsid nohup sh -c '$remoteCommand' >$logPath 2>&1 </dev/null &"
        )

        return pollInstallResult(context, serial, logPath)
    }

    /**
     * Best-effort readback of [logPath] for immediate UI feedback. Most of
     * the time this app's own process dies partway through (see
     * installOverAdb's doc comment) before it ever gets a real answer —
     * that's expected and harmless, since the detached chain it's polling
     * for keeps running on its own regardless. [ensurePackageInstallerEnabled]
     * is the actual backstop for the rare case where even that gets
     * interrupted (e.g. the watch reboots mid-update).
     */
    private fun pollInstallResult(context: Context, serial: String, logPath: String): Result {
        val deadline = System.currentTimeMillis() + INSTALL_POLL_TIMEOUT_MS
        var lastOutput = ""
        while (System.currentTimeMillis() < deadline) {
            val output = shell(context, serial, "cat $logPath 2>/dev/null")
            if (output.isNotBlank()) {
                lastOutput = output
                if (output.contains("Success", ignoreCase = true)) {
                    return Result.Success
                }
                if (output.contains("Failure", ignoreCase = true)) {
                    return Result.Error("Установка не удалась: ${output.trim().take(200)}")
                }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        // No definite answer within the window — by far the most likely
        // reason is that this process is dying (or already would have,
        // were this thread not about to go with it) as the update takes
        // effect, not a real hang.
        Log.i(TAG, "Install result unknown after ${INSTALL_POLL_TIMEOUT_MS}ms, last log: ${lastOutput.trim().take(500)}")
        return Result.Success
    }

    /**
     * Best-effort self-repair for the one failure mode installOverAdb's
     * detached chain can't cover on its own: the watch rebooting (or the
     * chain otherwise getting interrupted) before `pm enable` in it ran,
     * leaving the package installer disabled after an update. Only call
     * this when [isReenablePending] said this app itself is the one that
     * disabled it (see markReenablePending) — someone who deliberately
     * keeps it off to sideload APKs `pm install` can't otherwise put on the
     * watch would otherwise get overridden every time they open the app.
     * One attempt only, success or not: [clearReenablePending] always
     * fires afterward, so a person is free to turn it back off themselves
     * once this update's own business with it is done.
     */
    fun ensurePackageInstallerEnabled(context: Context) {
        Thread {
            try {
                withMulticastLock(context) {
                    startServer(context)
                    val serial = waitForDevice(context, SELF_REPAIR_WAIT_TIMEOUT_MS)
                    if (serial != null) {
                        val output = shell(context, serial, "pm enable --user 0 com.android.packageinstaller")
                        Log.i(TAG, "pm enable (self-repair) output: ${output.trim()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Package installer self-repair failed", e)
            } finally {
                killServer(context)
                clearReenablePending(context)
            }
        }.start()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("adb_update_installer", Context.MODE_PRIVATE)

    private fun markReenablePending(context: Context, pending: Boolean) {
        prefs(context).edit().putBoolean(KEY_PENDING_REENABLE, pending).apply()
    }

    /**
     * True only while this app has disabled the package installer for an
     * update of its own and hasn't yet confirmed (or given up trying) that
     * it put it back — see [installOverAdb] and [ensurePackageInstallerEnabled].
     * Never true just because the package installer happens to be off for
     * some unrelated reason.
     */
    fun isReenablePending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PENDING_REENABLE, false)

    fun clearReenablePending(context: Context) {
        markReenablePending(context, false)
    }

    private fun push(context: Context, serial: String, localFile: File, remotePath: String): Boolean {
        return try {
            val process = exec(context, listOf("-s", serial, "push", localFile.absolutePath, remotePath))
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            val output = process.inputStream.bufferedReader().readText()
            Log.i(TAG, "adb push output: ${output.trim()}")
            process.exitValue() == 0
        } catch (e: Exception) {
            Log.e(TAG, "push failed", e)
            false
        }
    }

    // --- Bundled adb binary -------------------------------------------

    private fun adbPath(context: Context): String =
        "${context.applicationContext.applicationInfo.nativeLibraryDir}/libadb.so"

    /**
     * Runs the bundled adb binary as a subprocess. HOME is pointed at this
     * app's private files dir so the auth keypair adb generates on first
     * run (`$HOME/.android/adbkey`) persists across app launches, the same
     * way it would in a real user's home directory on a PC.
     */
    private fun exec(context: Context, args: List<String>): Process {
        val appContext = context.applicationContext
        return ProcessBuilder(listOf(adbPath(appContext)) + args)
            .directory(appContext.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = appContext.filesDir.path
                environment()["TMPDIR"] = appContext.cacheDir.path
                // adb's mDNS discovery (`adb mdns services`, used to find
                // both the pairing and already-trusted-connect services —
                // see queryMdnsAddress) defaults to a backend that expects a
                // separately-running system mDNS/Bonjour daemon (e.g.
                // avahi-daemon on Linux). No such daemon exists on Android,
                // so without this every `mdns` query silently finds nothing
                // — not just on this watch, on any device — regardless of
                // whether Wireless debugging is actually on. The self-
                // contained Open Screen backend needs no external daemon but
                // is opt-in via this env var.
                environment()["ADB_MDNS_OPENSCREEN"] = "1"
            }
            .start()
    }

    private fun startServer(context: Context) {
        try {
            val process = exec(context, listOf("start-server"))
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            Log.i(TAG, "start-server output: ${process.inputStream.bufferedReader().readText().trim()}")
        } catch (e: Exception) {
            Log.e(TAG, "start-server failed", e)
        }
    }

    /** Always run once an attempt is done, success or not — see [runAttempt]. */
    private fun killServer(context: Context) {
        try {
            val process = exec(context, listOf("kill-server"))
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            Log.e(TAG, "kill-server failed", e)
        }
    }

    /**
     * `adb pair <host>:<port> <code>` — the code goes on the command line,
     * not into the subprocess's stdin. adb only prompts for it interactively
     * when it isn't passed as an argument, and that prompt reads a pipe that
     * never sees EOF here (nothing closes this process's stdin), so the
     * subprocess just sat there until the timeout below killed it — which
     * reported every code, right or wrong, as refused. stdin is closed right
     * away regardless, so nothing downstream can block on it either.
     */
    private fun pair(context: Context, host: String, port: Int, code: String): Boolean {
        return try {
            val process = exec(context, listOf("pair", "$host:$port", code))
            process.outputStream.close()
            // The TLS handshake and SPAKE2 exchange behind this are slow on
            // watch-class hardware — this is the one adb call worth giving
            // real time to, since failing it means asking for the code again.
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            val output = process.inputStream.bufferedReader().readText()
            Log.i(TAG, "adb pair output: ${output.trim()}")
            process.exitValue() == 0
        } catch (e: Exception) {
            Log.e(TAG, "pair failed", e)
            false
        }
    }

    /**
     * Explicit `adb connect <host>:<port>` — the step this class used to
     * skip entirely on the theory that adb's own background auto-connect
     * always finds the device on its own. Confirmed true on a Huawei watch,
     * confirmed false on a Samsung Galaxy Watch, where nothing connects
     * without this even though the _adb-tls-connect._tcp service is right
     * there (a third-party on-watch ADB client found and used it instantly
     * — by connecting to this same resolved address, not to localhost; see
     * this file's class doc). Doesn't report success itself — the caller
     * always re-checks via [waitForDevice] afterward, since a successful
     * `connect` command doesn't guarantee the connection is fully usable
     * yet.
     */
    private fun connect(context: Context, address: MdnsAddress) {
        try {
            val process = exec(context, listOf("connect", "${address.host}:${address.port}"))
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return
            }
            Log.i(TAG, "adb connect output: ${process.inputStream.bufferedReader().readText().trim()}")
        } catch (e: Exception) {
            Log.e(TAG, "adb connect failed", e)
        }
    }

    private fun shell(context: Context, serial: String, command: String): String {
        val process = exec(context, listOf("-s", serial, "shell", command))
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        return process.inputStream.bufferedReader().readText()
    }

    // --- Device discovery -------------------------------------------------

    /**
     * Polls `adb devices` until it lists a device in "device" state
     * (connected and authorized — not "unauthorized", not "offline") or
     * [timeoutMs] elapses. Does *not* manage the multicast lock itself —
     * see [withMulticastLock], which every caller of this whole discovery
     * family (this, [queryMdnsAddress] and everything built on it) needs
     * held for its entire span, not just around this one call.
     */
    private fun waitForDevice(context: Context, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastOutput = ""
        while (System.currentTimeMillis() < deadline) {
            val output = queryDevices(context)
            if (output != null) {
                lastOutput = output
                val serial = parseConnectedSerial(output)
                if (serial != null) {
                    Log.i(TAG, "adb devices found: $serial")
                    return serial
                }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        Log.i(TAG, "adb devices: no connected device after ${timeoutMs}ms, last output: ${lastOutput.trim()}")
        return null
    }

    /**
     * Holds a Wi-Fi multicast lock for the duration of [block] — required
     * for *any* mDNS traffic, not just [waitForDevice]'s own polling:
     * mDNS replies (and adb's own background auto-connect, which listens
     * for the same replies) are UDP multicast, dropped by Wi-Fi drivers by
     * default to save battery, a device-wide/driver-level filter that
     * applies regardless of manufacturer. This used to be acquired only
     * inside [waitForDevice] itself — meaning every direct mDNS query
     * ([queryMdnsAddress], and so [discoverPairingAddress] and the
     * explicit-connect stage of [connectOrDiscoverPairing]/
     * [connectAfterPairing]) ran with nothing held at all, silently
     * dropping the very replies they were waiting for on any hardware,
     * not just one manufacturer's. Every [runAttempt]/[runGrantAttempt]/
     * [ensurePackageInstallerEnabled] wraps its *entire* body in this now,
     * so the lock is held continuously across every discovery call an
     * attempt makes, not re-acquired piecemeal around individual ones.
     */
    private fun <T> withMulticastLock(context: Context, block: () -> T): T {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("assistvoice-adb-discovery")?.apply {
            setReferenceCounted(true)
            try { acquire() } catch (e: Exception) { Log.e(TAG, "multicast lock acquire failed", e) }
        }
        try {
            return block()
        } finally {
            try { multicastLock?.release() } catch (e: Exception) { /* not held */ }
        }
    }

    private fun queryDevices(context: Context): String? {
        return try {
            val process = exec(context, listOf("devices"))
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            process.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            Log.e(TAG, "adb devices failed", e)
            null
        }
    }

    /**
     * `adb devices` output is a header line followed by
     * "<serial>\t<state>" lines, e.g. "localhost:34815\tdevice" once
     * connected, or "...\tunauthorized" while the system's "Allow
     * debugging" dialog is still pending — only "device" means usable.
     */
    private fun parseConnectedSerial(output: String): String? {
        for (line in output.lineSequence()) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size >= 2 && fields[1] == "device") {
                return fields[0]
            }
        }
        return null
    }

    /**
     * Resolves the address of the on-device pairing service that only
     * exists while a "Pair device with pairing code" screen is open — this
     * is what lets [tryInstall] tell "Wireless debugging is entirely off"
     * (NotAvailable) apart from "it's on and mid-pairing" (NeedsPairing,
     * ask for the code).
     */
    private fun discoverPairingAddress(context: Context, timeoutMs: Long): MdnsAddress? {
        // NsdManager discovery is event-driven: this one call waits out the
        // whole window and returns the moment the pairing screen shows up,
        // so unlike the adb snapshot below it needs no polling of its own.
        discoverViaNsd(context, PAIR_SERVICE_TYPE, timeoutMs)?.let { return it }
        return queryMdnsAddressViaAdb(context, PAIR_SERVICE_TYPE)
    }

    /**
     * Looks up the host:port advertised for [serviceType]. Shared by both
     * PAIR_SERVICE_TYPE (a code-entry screen) and CONNECT_SERVICE_TYPE (an
     * already-trusted device ready to `adb connect` to). The host is
     * returned exactly as discovery reports it — never replaced with
     * `localhost` — since `adbd`'s wireless-debugging TLS listener isn't
     * guaranteed to be bound to loopback; see this file's class doc.
     */
    private fun queryMdnsAddress(
        context: Context,
        serviceType: String,
        timeoutMs: Long = NSD_DISCOVERY_TIMEOUT_MS
    ): MdnsAddress? =
        discoverViaNsd(context, serviceType, timeoutMs)
            ?: queryMdnsAddressViaAdb(context, serviceType)

    /**
     * Discovers [serviceType] through Android's own NsdManager.
     *
     * This is the only mDNS path that actually works from inside an app,
     * and everything that needs pairing depends on it. The bundled adb
     * binary has two discovery backends and the app sandbox blocks both:
     * its default one (MdnsResponder) talks to a system Bonjour daemon
     * socket no app is allowed to open — that's its "mdns daemon
     * unavailable" — and its Open Screen one (ADB_MDNS_OPENSCREEN, set in
     * [exec]) enumerates network interfaces over a netlink socket, which
     * Android has denied to apps since 11, so it finds no interfaces to
     * query on and turns up nothing without ever reporting an error. Either
     * way `adb mdns services` comes back empty however thoroughly Wireless
     * debugging is switched on, which is exactly what made every
     * pairing-based (i.e. every non-Huawei) connect give up with
     * NotAvailable and tell the person to go enable a setting that was
     * already enabled. NsdManager proxies to the system's own mDNS service,
     * which holds the privileges neither backend can get and already knows
     * about services registered on this very device.
     */
    private fun discoverViaNsd(context: Context, serviceType: String, timeoutMs: Long): MdnsAddress? {
        val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return null
        val found = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.offer(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.e(TAG, "NSD discovery failed to start for $type: $errorCode")
            }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
        }
        var started = false
        return try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            started = true
            val deadline = System.currentTimeMillis() + timeoutMs
            var address: MdnsAddress? = null
            while (address == null && System.currentTimeMillis() < deadline) {
                val service = found.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS) ?: continue
                address = resolveViaNsd(nsdManager, service)
            }
            Log.i(TAG, "NSD lookup of $serviceType: ${address?.let { "${it.host}:${it.port}" } ?: "nothing found"}")
            address
        } catch (e: Exception) {
            Log.e(TAG, "NSD discovery failed for $serviceType", e)
            null
        } finally {
            if (started) {
                try { nsdManager.stopServiceDiscovery(listener) } catch (e: Exception) { /* already stopped */ }
            }
        }
    }

    /**
     * One [NsdManager.resolveService] round trip, waited out synchronously —
     * a found service only carries a name, the host and port it actually
     * lives on take this second step.
     */
    @Suppress("DEPRECATION")
    private fun resolveViaNsd(nsdManager: NsdManager, service: NsdServiceInfo): MdnsAddress? {
        val resolved = ArrayBlockingQueue<MdnsAddress>(1)
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.port <= 0) return
                // Loopback only as a last resort, when the service resolved
                // but carried no usable address — the port alone is still
                // worth an attempt, and adbd is on this very device. Never
                // as an up-front assumption: see this file's class doc.
                val host = serviceInfo.host?.hostAddress ?: "127.0.0.1"
                resolved.offer(MdnsAddress(host, serviceInfo.port))
            }
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.i(TAG, "NSD resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }
        }
        // Caught here rather than in the caller so one service that won't
        // resolve doesn't end the whole discovery pass — the next one found
        // inside the window still gets its turn.
        return try {
            nsdManager.resolveService(service, listener)
            resolved.poll(NSD_RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "NSD resolve failed for ${service.serviceName}", e)
            null
        }
    }

    /**
     * The bundled adb server's own `mdns services`, kept only as a fallback
     * behind [discoverViaNsd] — see there for why it reports nothing in an
     * app sandbox. Has no built-in wait: it prints a snapshot and exits.
     */
    private fun queryMdnsAddressViaAdb(context: Context, serviceType: String): MdnsAddress? {
        return try {
            val process = exec(context, listOf("mdns", "services"))
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            val output = process.inputStream.bufferedReader().readText()
            for (line in output.lineSequence()) {
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size >= 3 && fields[1] == serviceType) {
                    val address = fields[2]
                    val port = address.substringAfterLast(':').toIntOrNull() ?: continue
                    val host = address.substringBeforeLast(':').trim('[', ']')
                    if (host.isBlank()) continue
                    return MdnsAddress(host, port)
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "adb mdns services failed", e)
            null
        }
    }

    /** No ADB needed — a plain system property, always available. Gates
     * the Huawei-only steps in [installOverAdb], and read by MainActivity
     * to decide whether an update install should even attempt ADB at all
     * (non-Huawei installs normally via UpdateInstaller.promptInstall()). */
    fun isHuawei(): Boolean = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

    private fun deliver(callback: (Result) -> Unit, result: Result) {
        mainHandler.post { callback(result) }
    }
}
