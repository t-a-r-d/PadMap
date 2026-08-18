package com.slickstax841.padmap.inject

import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Copies the sidecar jar to /data/local/tmp and starts it as shell (uid 2000).
 * The visible path is wireless ADB pairing. Magisk `su` is a hidden fallback.
 */
object SidecarHost {

    private const val PREFS = "padmap_sidecar"
    private const val KEY_PAIRED = "paired"
    private const val KEY_HOST = "last_host"
    private const val KEY_PORT = "last_port"
    private const val KEY_TOKEN = "auth_token"
    private const val REMOTE_JAR = "/data/local/tmp/padmap-sidecar.jar"
    private const val REMOTE_SH = "/data/local/tmp/padmap-start.sh"
    private const val REMOTE_PID = "/data/local/tmp/padmap-sidecar.pid"
    private const val REMOTE_LOG = "/data/local/tmp/padmap-sidecar.log"

    @Volatile
    var status: String = "Injector not started"
        private set

    @Volatile private var inProgress = false
    @Volatile private var lastAttemptMs = 0L
    @Volatile private var autoTriedThisProcess = false
    @Volatile private var lastLaunchNotes: String = ""

    fun isWirelessDebugOn(context: Context): Boolean {
        val cr = context.contentResolver
        if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) > 0) return true
        // Some ColorOS / Oppo builds leave the AOSP flag at 0 while the toggle is on.
        if (Settings.Global.getInt(cr, "wifi_adb_enabled", 0) > 0) return true
        return false
    }

    fun hasPaired(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, 0).getBoolean(KEY_PAIRED, false)
    }

    fun suPath(): String? {
        val paths = listOf(
            "/debug_ramdisk/su",
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/system/sbin/su",
            "/vendor/bin/su"
        )
        return paths.firstOrNull { p ->
            val f = File(p)
            f.exists() && f.canExecute()
        }
    }

    fun hasSu(): Boolean = suPath() != null

    fun bindClient(context: Context) {
        SidecarClient.authToken = installToken(context)
    }

    private fun installToken(context: Context): String {
        val p = prefs(context)
        var t = p.getString(KEY_TOKEN, null)
        if (t.isNullOrBlank() || t.any { !it.isLetterOrDigit() }) {
            t = UUID.randomUUID().toString().replace("-", "")
            p.edit().putString(KEY_TOKEN, t).apply()
        }
        return t
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    private fun markPaired(context: Context) {
        prefs(context).edit().putBoolean(KEY_PAIRED, true).apply()
    }

    private fun saveEndpoint(context: Context, ep: AdbEndpoint) {
        prefs(context).edit().putString(KEY_HOST, ep.host).putInt(KEY_PORT, ep.port).apply()
    }

    private fun lastEndpoint(context: Context): AdbEndpoint? {
        val port = prefs(context).getInt(KEY_PORT, 0)
        if (port <= 0) return null
        val host = prefs(context).getString(KEY_HOST, null)?.ifBlank { null } ?: "127.0.0.1"
        return AdbEndpoint(host, port)
    }

    /**
     * Reconnects wireless ADB after the first pairing and starts the sidecar.
     * Magisk `su` is only a hidden fallback if wireless start fails.
     * Does not require the AOSP adb_wifi_enabled flag — Oppo often leaves that at 0.
     */
    fun ensureRunning(context: Context, force: Boolean = false): Boolean {
        bindClient(context)
        if (SidecarClient.ping()) {
            status = "Injector running"
            dropAdb(context)
            return true
        }
        if (inProgress) return false
        if (!hasPaired(context)) {
            status = "First-time pair needed"
            return false
        }
        // ColorOS's "Wireless debugging connected" toast resumes Home, which used
        // to call this again and disconnect/reconnect forever.
        if (!force && autoTriedThisProcess) return false
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptMs < 20_000L) {
            return false
        }
        lastAttemptMs = now
        inProgress = true
        try {
            status = "Looking for wireless debugging…"
            val nsd = runCatching { NsdAdbFinder.findWithRetry(context, pairing = false) }.getOrNull()
            val last = lastEndpoint(context)
            val flagOn = isWirelessDebugOn(context)
            if (nsd == null && last == null && !flagOn) {
                status = "Turn on Wireless debugging, then return here"
                return tryHiddenRoot(context)
            }
            val tried = linkedSetOf<AdbEndpoint>()
            nsd?.let {
                tried.add(it)
                tried.add(AdbEndpoint("127.0.0.1", it.port))
            }
            last?.let {
                tried.add(it)
                tried.add(AdbEndpoint("127.0.0.1", it.port))
            }
            if (tried.isEmpty()) {
                status = "Wireless debugging looks on, but no connect port was found"
                return tryHiddenRoot(context)
            }
            autoTriedThisProcess = true
            var lastErr: Throwable? = null
            for (ep in tried) {
                try {
                    start(context, ep.host, ep.port)
                    saveEndpoint(context, ep)
                    dropAdb(context)
                    return SidecarClient.ping()
                } catch (t: Throwable) {
                    lastErr = t
                }
            }
            if (tryHiddenRoot(context)) return true
            status = lastErr?.message ?: "Could not start injector"
            throw lastErr ?: IllegalStateException(status)
        } finally {
            inProgress = false
            lastAttemptMs = System.currentTimeMillis()
        }
    }

    /** Magisk start with no UI. Keeps the wireless status if root is missing or denied. */
    private fun tryHiddenRoot(context: Context): Boolean {
        val su = suPath() ?: return false
        val saved = status
        val ok = runCatching { startViaRoot(context, su) }.getOrDefault(false)
        if (!ok) status = saved
        return ok
    }

    fun pair(context: Context, host: String, port: Int, code: String) {
        status = "Pairing…"
        val mgr = PadMapAdbManager.get(context)
        if (!mgr.pair(host, port, code)) {
            throw IllegalStateException("Pairing rejected. Check the code and wireless debugging port.")
        }
        markPaired(context)
        status = "Paired"
    }

    fun start(context: Context, connectHost: String, connectPort: Int) {
        bindClient(context)
        status = "Connecting ADB…"
        val mgr = PadMapAdbManager.get(context)
        mgr.setTimeout(8, TimeUnit.SECONDS)
        // pair() and a failed first connect can leave the manager "connected";
        // libadb then returns false immediately on the next connect().
        Thread.sleep(400)
        if (!connectAny(context, mgr, connectHost, connectPort)) {
            throw IllegalStateException("ADB connect failed on $connectHost:$connectPort")
        }
        saveEndpoint(context, AdbEndpoint(connectHost, connectPort))
        val remote = REMOTE_JAR
        status = "Installing injector…"
        // Do not cp from /storage/emulated/0 — ColorOS FUSE often never returns.
        val jar = readAssetJar(context)
        pushJar(mgr, jar, remote)
        val copied = remoteSize(mgr, remote)
        if (copied != jar.size) {
            throw IllegalStateException("Injector copy failed (phone has $copied bytes, expected ${jar.size})")
        }
        shell(mgr, "chmod 644 $remote")
        shell(mgr, "kill \$(cat $REMOTE_PID) 2>/dev/null; true")
        status = "Starting injector…"
        launchDetached(mgr)
        if (!waitForPing(4000)) {
            val detail = diagnose(mgr)
            status = detail
            throw IllegalStateException(detail)
        }
        status = "Injector running"
        dropAdb(context)
    }

    /**
     * Root only copies the jar and launches the existing sidecar as shell (uid 2000).
     * The sidecar process is not uid 0.
     */
    private fun startViaRoot(context: Context, su: String): Boolean {
        status = "Asking Magisk for root…"
        val id = execSu(su, null, "id", 20_000)
        if (id.first == -1 && !id.second.contains("uid=0")) {
            status = "No Magisk response. If it is installed, Allow PadMap, then return here"
            return false
        }
        if (id.first != 0 || !id.second.contains("uid=0")) {
            status = "Root denied. Allow PadMap in Magisk, then return here"
            return false
        }
        status = "Installing injector…"
        val jar = readAssetJar(context)
        val localJar = File(context.cacheDir, "padmap-sidecar.jar")
        val localSh = File(context.cacheDir, "padmap-start.sh")
        if (localJar.absolutePath.any { it == '\'' } || localSh.absolutePath.any { it == '\'' }) {
            throw IllegalStateException("Unexpected cache path")
        }
        localJar.writeBytes(jar)
        localSh.writeText(startScript(installToken(context)))
        val install = execSu(
            su,
            null,
            "cp '${localJar.absolutePath}' $REMOTE_JAR && " +
                "cp '${localSh.absolutePath}' $REMOTE_SH && " +
                "chmod 644 $REMOTE_JAR && chmod 755 $REMOTE_SH && " +
                "wc -c < $REMOTE_JAR",
            15_000
        )
        val copied = install.second.filter { it.isDigit() }.toIntOrNull()
        if (install.first != 0 || copied != jar.size) {
            throw IllegalStateException("Injector copy failed (phone has $copied bytes, expected ${jar.size})")
        }
        execSu(su, null, "kill \$(cat $REMOTE_PID) 2>/dev/null; true", 3_000)
        status = "Starting injector…"
        val launchCmds = listOf(
            "setsid -f $REMOTE_SH </dev/null >$REMOTE_LOG 2>&1",
            "nohup $REMOTE_SH </dev/null >$REMOTE_LOG 2>&1 &"
        )
        for (uid in listOf("2000", "shell")) {
            for (cmd in launchCmds) {
                execSu(su, uid, cmd, 2_000)
                if (waitForPing(2_500)) {
                    status = "Injector running"
                    return true
                }
            }
        }
        val detail = diagnoseRoot(su)
        status = detail
        throw IllegalStateException(detail)
    }

    private fun startScript(token: String): String {
        return """
            #!/system/bin/sh
            BIN=/system/bin/app_process64
            if [ ! -x ${'$'}BIN ]; then BIN=/system/bin/app_process; fi
            if [ ! -x ${'$'}BIN ]; then BIN=app_process64; fi
            export CLASSPATH=$REMOTE_JAR
            exec ${'$'}BIN --nice-name=padmap_sidecar /data/local/tmp com.slickstax841.padmap.sidecar.SidecarMain ${SidecarClient.PORT} $token
        """.trimIndent() + "\n"
    }

    private fun execSu(su: String, uid: String?, command: String, timeoutMs: Long): Pair<Int, String> {
        val args = if (uid != null) arrayOf(su, uid, "-c", command) else arrayOf(su, "-c", command)
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val acc = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val inp = p.inputStream
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val avail = runCatching { inp.available() }.getOrDefault(0)
            if (avail > 0) {
                val n = inp.read(buf)
                if (n < 0) break
                if (n > 0) acc.write(buf, 0, n)
            } else {
                try {
                    val code = p.exitValue()
                    while (true) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        acc.write(buf, 0, n)
                    }
                    return code to acc.toString(Charset.forName("UTF-8").name())
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(40)
                }
            }
        }
        p.destroy()
        runCatching { p.destroyForcibly() }
        return -1 to acc.toString(Charset.forName("UTF-8").name())
    }

    /** Sidecar stays up on localhost. Holding ADB open retriggers ColorOS toasts and resumes. */
    private fun dropAdb(context: Context) {
        runCatching { PadMapAdbManager.get(context).disconnect() }
    }

    /** New session so closing the ADB stream does not SIGHUP the injector. */
    private fun launchDetached(mgr: PadMapAdbManager) {
        lastLaunchNotes = ""
        pushText(mgr, startScript(SidecarClient.authToken), REMOTE_SH)
        shell(mgr, "chmod 755 $REMOTE_SH")
        val cmds = listOf(
            "nohup $REMOTE_SH </dev/null >$REMOTE_LOG 2>&1 &",
            "setsid -f $REMOTE_SH </dev/null >$REMOTE_LOG 2>&1",
            "sh -c '$REMOTE_SH </dev/null >$REMOTE_LOG 2>&1 &'"
        )
        val acc = StringBuilder()
        for (cmd in cmds) {
            val out = shell(mgr, cmd, timeoutMs = 1500).trim()
            acc.append(cmd).append(" -> ").append(out.ifBlank { "(no output)" }).append('\n')
            lastLaunchNotes = acc.toString()
            if (waitForPing(3000)) return
        }
    }

    private fun pushText(mgr: PadMapAdbManager, text: String, remote: String) {
        val b64 = Base64.encodeToString(text.toByteArray(Charset.forName("UTF-8")), Base64.NO_WRAP)
        shell(mgr, "printf '%s' '$b64' | base64 -d > $remote")
    }

    private fun refusedExplain(): String {
        val err = SidecarClient.lastError
        val refused = err.contains("ECONNREFUSED", ignoreCase = true) ||
            err.contains("refused", ignoreCase = true)
        return if (refused) {
            "Injector never listened on 127.0.0.1:${SidecarClient.PORT} (connection refused). " +
                "Nothing is running there. The other port in the socket error is PadMap's outgoing port, not ADB."
        } else if (err.isBlank()) {
            "Injector did not answer on 127.0.0.1:${SidecarClient.PORT}."
        } else {
            "Injector did not answer on 127.0.0.1:${SidecarClient.PORT}: $err"
        }
    }

    private fun diagnose(mgr: PadMapAdbManager): String {
        val dump = shell(
            mgr,
            "echo JAR:\$(wc -c < $REMOTE_JAR 2>/dev/null); " +
                "echo PIDFILE:\$(cat $REMOTE_PID 2>/dev/null); " +
                "echo PS:\$(ps -A 2>/dev/null | grep -i padmap | grep -v grep); " +
                "echo BIN64:\$(ls -l /system/bin/app_process64 2>&1); " +
                "echo BIN:\$(ls -l /system/bin/app_process 2>&1); " +
                "echo SCRIPT:\$(ls -l $REMOTE_SH 2>&1); " +
                "echo LOGFILE:\$(ls -l $REMOTE_LOG 2>&1); " +
                "echo LOG:; cat $REMOTE_LOG 2>/dev/null",
            timeoutMs = 8000
        ).trim()
        return buildString {
            append(refusedExplain())
            if (lastLaunchNotes.isNotBlank()) {
                append(" Launch: ").append(lastLaunchNotes.trim())
            }
            if (dump.isNotBlank()) {
                append(" Phone: ").append(dump.replace("\n", " | "))
            }
        }
    }

    private fun diagnoseRoot(su: String): String {
        val dump = execSu(
            su,
            null,
            "echo JAR:\$(wc -c < $REMOTE_JAR 2>/dev/null); " +
                "echo PIDFILE:\$(cat $REMOTE_PID 2>/dev/null); " +
                "echo PS:\$(ps -A 2>/dev/null | grep -i padmap | grep -v grep); " +
                "echo BIN64:\$(ls -l /system/bin/app_process64 2>&1); " +
                "echo LOG:; cat $REMOTE_LOG 2>/dev/null",
            8_000
        ).second.trim()
        return buildString {
            append(refusedExplain())
            if (dump.isNotBlank()) {
                append(" Phone: ").append(dump.replace("\n", " | "))
            }
        }
    }

    private fun waitForPing(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (SidecarClient.ping()) return true
            Thread.sleep(150)
        }
        return false
    }

    private fun connectAny(context: Context, mgr: PadMapAdbManager, host: String, port: Int): Boolean {
        val hosts = linkedSetOf(host, "127.0.0.1")
        runCatching {
            io.github.muntashirakon.adb.android.AndroidUtils.getHostIpAddress(context)
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { hosts.add(it) }
        localIpv4().forEach { hosts.add(it) }
        var last: Throwable? = null
        for (h in hosts) {
            runCatching { mgr.disconnect() }
            try {
                if (mgr.connect(h, port)) return true
            } catch (t: Throwable) {
                last = t
            }
        }
        runCatching { mgr.disconnect() }
        try {
            if (mgr.autoConnect(context, 8000L)) return true
        } catch (t: Throwable) {
            last = t
        }
        if (last != null) status = "ADB connect failed: ${last.message}"
        return false
    }

    private fun localIpv4(): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val en = java.net.NetworkInterface.getNetworkInterfaces() ?: return out
            while (en.hasMoreElements()) {
                val addrs = en.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                        a.hostAddress?.let { out.add(it) }
                    }
                }
            }
        }
        return out
    }

    private fun readAssetJar(context: Context): ByteArray {
        return context.assets.open("sidecar/padmap-sidecar.jar").use { it.readBytes() }
    }

    private fun remoteSize(mgr: PadMapAdbManager, remote: String): Int? {
        return shell(mgr, "wc -c < $remote").filter { it.isDigit() }.toIntOrNull()
    }

    private fun pushJar(mgr: PadMapAdbManager, bytes: ByteArray, remote: String) {
        // Default dd bs=512 drops the last partial block when the ADB stream
        // closes without a clean EOF (v55: 6656 of 6861).
        pushViaStream(mgr, "dd of=$remote bs=${bytes.size} count=1", bytes)
        if (remoteSize(mgr, remote) == bytes.size) return
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        shell(mgr, "printf '%s' '$b64' | base64 -d > $remote")
    }

    private fun pushViaStream(mgr: PadMapAdbManager, shellCmd: String, bytes: ByteArray) {
        val stream = mgr.openStream("shell:$shellCmd")
        try {
            stream.openOutputStream().use { out ->
                out.write(bytes)
                out.flush()
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun shell(mgr: PadMapAdbManager, command: String, timeoutMs: Long = 8000): String {
        val stream = mgr.openStream("shell:$command")
        return try {
            val inp = stream.openInputStream()
            val buf = ByteArray(4096)
            val acc = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val n = try {
                    val avail = runCatching { inp.available() }.getOrDefault(1)
                    if (avail <= 0) {
                        Thread.sleep(40)
                        continue
                    }
                    inp.read(buf)
                } catch (_: Throwable) { -1 }
                if (n < 0) break
                if (n > 0) acc.write(buf, 0, n)
            }
            acc.toString(Charset.forName("UTF-8").name())
        } finally {
            runCatching { stream.close() }
        }
    }
}
