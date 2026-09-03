package com.nikolay.assistvoice

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.PrintStream
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
 * Does not call `adb connect` itself: `adb start-server`'s own background
 * auto-connect (the same mechanism behind `adb devices` listing
 * wireless-debugging devices automatically) finds and connects to the watch
 * on its own. So instead of orchestrating discovery + connect, [waitForDevice]
 * just polls `adb devices` until that mechanism reports one, then targets it
 * directly with `-s`.
 *
 * The bundled `adb` manages its own auth keypair under `$HOME/.android/`
 * (HOME pointed at this app's private files dir below) exactly like it would
 * on a PC — that persists across app runs, so pairing only has to happen once
 * per factory reset. The local adb *server* process it spawns does not: every
 * attempt ends with `adb kill-server` (see [runAttempt]), so nothing from
 * this keeps running — or using battery/memory — outside the window an
 * update install is actually in progress.
 */
object AdbUpdateInstaller {

    private const val TAG = "AdbUpdateInstaller"
    private const val REMOTE_APK_NAME = "assistvoice-update.apk"
    private const val PAIR_SERVICE_TYPE = "_adb-tls-pairing._tcp"
    private const val POLL_INTERVAL_MS = 500L
    // Getting here at all can require the person to notice and tap a
    // system "Allow debugging" dialog, and adb's own background
    // auto-connect (see class doc) runs on its own timeline, not ours — a
    // short timeout just means giving up before they've had a chance to
    // respond.
    private const val DEVICE_WAIT_TIMEOUT_MS = 30_000L
    // Nothing on this watch's firmware has ever been observed to advertise
    // adb-tls-pairing (there's no "pair with code" screen in its Settings),
    // so NeedsPairing is a fallback for hardware where that screen exists,
    // not the expected path here — kept short since it's very unlikely to
    // ever resolve to anything.
    private const val PAIRING_DISCOVERY_TIMEOUT_MS = 3_000L
    private val mainHandler = Handler(Looper.getMainLooper())

    sealed class Result {
        object Success : Result()
        object NotAvailable : Result()
        data class NeedsPairing(val port: Int) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Tries to install [apkFile] using whatever ADB trust this app already
     * has (from a previous successful pairing). Never throws — always
     * delivers exactly one [Result] to [callback] on the main thread.
     */
    fun tryInstall(context: Context, apkFile: File, callback: (Result) -> Unit) {
        runAttempt(context, callback) {
            startServer(context)
            val serial = waitForDevice(context, DEVICE_WAIT_TIMEOUT_MS)
            if (serial != null) {
                installOverAdb(context, serial, apkFile)
            } else {
                val pairingPort = discoverPairingPort(context, PAIRING_DISCOVERY_TIMEOUT_MS)
                if (pairingPort != null) {
                    Result.NeedsPairing(pairingPort)
                } else {
                    Result.NotAvailable
                }
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
        port: Int,
        pairingCode: String,
        apkFile: File,
        callback: (Result) -> Unit
    ) {
        runAttempt(context, callback) {
            val paired = pair(context, port, pairingCode)
            if (!paired) {
                Result.Error("Код не подошёл — проверь и введи заново")
            } else {
                val serial = waitForDevice(context, DEVICE_WAIT_TIMEOUT_MS)
                if (serial != null) {
                    installOverAdb(context, serial, apkFile)
                } else {
                    Result.Error("Спарились, но не удалось подключиться — нажми «Установить» ещё раз")
                }
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
                attempt()
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
        if (!push(context, serial, apkFile, remotePath)) {
            return Result.Error("Не удалось скопировать APK на часы")
        }

        val disableOutput = shell(context, serial, "pm disable-user --user 0 com.android.packageinstaller")
        Log.i(TAG, "pm disable-user output: ${disableOutput.trim()}")

        // Install, re-enable and cleanup are one shell command (`;`, not
        // `&&`, so later steps still run if an earlier one fails) rather
        // than separate adb invocations: installing an update over this
        // app's own running process makes Android force-stop every process
        // under this app's UID as part of applying it — including this
        // app's local adb client/server processes — which can happen
        // before this call even returns. Chained into a single remote
        // shell command, the actual execution happens under the watch's
        // `shell` UID, not this app's, so it keeps going regardless of
        // what happens to us.
        val output = shell(
            context,
            serial,
            "pm install -r $remotePath; pm enable --user 0 com.android.packageinstaller; rm -f $remotePath"
        )
        Log.i(TAG, "pm install output: ${output.trim().take(500)}")
        return if (output.contains("Success", ignoreCase = true)) {
            Result.Success
        } else {
            Result.Error("Установка не удалась: ${output.trim().take(200)}")
        }
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

    private fun pair(context: Context, port: Int, code: String): Boolean {
        return try {
            val process = exec(context, listOf("pair", "localhost:$port"))
            // Give the pair subcommand a moment to reach the point where it
            // reads the code from stdin before writing it.
            Thread.sleep(1_000)
            PrintStream(process.outputStream).apply {
                println(code)
                flush()
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
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
     * [timeoutMs] elapses. Requires a multicast lock held for the whole
     * poll window: adb's own background auto-connect relies on mDNS
     * replies, which are UDP multicast and dropped by Wi-Fi drivers by
     * default to save battery — a device-wide/driver-level filter, not
     * tied to which process or API is listening, so the lock has to be
     * held continuously rather than just around one command.
     */
    private fun waitForDevice(context: Context, timeoutMs: Long): String? {
        val appContext = context.applicationContext
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("assistvoice-adb-discovery")?.apply {
            setReferenceCounted(true)
            try { acquire() } catch (e: Exception) { Log.e(TAG, "multicast lock acquire failed", e) }
        }
        try {
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
     * Resolves the port of the on-device pairing service that only exists
     * while a "Pair device with pairing code" screen is open — this is
     * what lets [tryInstall] tell "Wireless debugging is entirely off"
     * (NotAvailable) apart from "it's on and mid-pairing" (NeedsPairing,
     * ask for the code). Polls the bundled adb server's own `mdns
     * services`, which has no built-in wait — it reports a snapshot — so
     * this polls every [POLL_INTERVAL_MS] instead.
     */
    private fun discoverPairingPort(context: Context, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val port = queryMdnsPairingPort(context)
            if (port != null) return port
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun queryMdnsPairingPort(context: Context): Int? {
        return try {
            val process = exec(context, listOf("mdns", "services"))
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            val output = process.inputStream.bufferedReader().readText()
            for (line in output.lineSequence()) {
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size >= 3 && fields[1] == PAIR_SERVICE_TYPE) {
                    return fields[2].substringAfterLast(':').toIntOrNull()
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "adb mdns services failed", e)
            null
        }
    }

    private fun deliver(callback: (Result) -> Unit, result: Result) {
        mainHandler.post { callback(result) }
    }
}
