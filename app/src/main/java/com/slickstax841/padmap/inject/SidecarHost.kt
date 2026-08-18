package com.slickstax841.padmap.inject

import android.content.Context
import android.provider.Settings
import java.io.File
import java.nio.charset.Charset

/**
 * Pairs wireless ADB, copies the sidecar jar to /data/local/tmp, and starts it as shell.
 */
object SidecarHost {

    private const val PREFS = "padmap_sidecar"
    private const val KEY_PAIRED = "paired"
    private const val KEY_HOST = "last_host"
    private const val KEY_PORT = "last_port"

    @Volatile
    var status: String = "Injector not started"
        private set

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
     * After the first pairing, reconnects and starts the sidecar.
     * Does not require the AOSP adb_wifi_enabled flag — Oppo often leaves that at 0.
     */
    fun ensureRunning(context: Context): Boolean {
        if (SidecarClient.ping()) {
            status = "Injector running"
            return true
        }
        if (!hasPaired(context)) {
            status = "First-time pair needed"
            return false
        }
        status = "Looking for wireless debugging…"
        val nsd = runCatching { NsdAdbFinder.findWithRetry(context, pairing = false) }.getOrNull()
        val last = lastEndpoint(context)
        val flagOn = isWirelessDebugOn(context)
        if (nsd == null && last == null && !flagOn) {
            status = "Turn on Wireless debugging, then return here"
            return false
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
            return false
        }
        var lastErr: Throwable? = null
        for (ep in tried) {
            try {
                start(context, ep.host, ep.port)
                saveEndpoint(context, ep)
                return SidecarClient.ping()
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        status = lastErr?.message ?: "Could not start injector"
        throw lastErr ?: IllegalStateException(status)
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
        status = "Connecting ADB…"
        val mgr = PadMapAdbManager.get(context)
        if (!connectAny(mgr, connectHost, connectPort)) {
            throw IllegalStateException("ADB connect failed on $connectHost:$connectPort")
        }
        saveEndpoint(context, AdbEndpoint(connectHost, connectPort))
        val jar = extractJar(context)
        val remote = "/data/local/tmp/padmap-sidecar.jar"
        status = "Installing injector…"
        shell(mgr, "cp '${jar.absolutePath}' $remote")
        shell(mgr, "chmod 644 $remote")
        shell(mgr, "kill \$(cat /data/local/tmp/padmap-sidecar.pid) 2>/dev/null || true")
        status = "Starting injector…"
        val start = "CLASSPATH=$remote app_process64 /data/local/tmp " +
            "com.slickstax841.padmap.sidecar.SidecarMain ${SidecarClient.PORT} ${SidecarClient.TOKEN}"
        val fallback = start.replace("app_process64", "app_process")
        shell(mgr, "sh -c '$start </dev/null >/data/local/tmp/padmap-sidecar.log 2>&1 &'")
        Thread.sleep(400)
        if (!SidecarClient.ping()) {
            shell(mgr, "sh -c '$fallback </dev/null >/data/local/tmp/padmap-sidecar.log 2>&1 &'")
            Thread.sleep(500)
        }
        if (!SidecarClient.ping()) {
            val log = runCatching { shell(mgr, "cat /data/local/tmp/padmap-sidecar.log") }.getOrDefault("")
            throw IllegalStateException("Sidecar did not start. ${SidecarClient.lastError} $log".trim())
        }
        status = "Injector running"
    }

    private fun connectAny(mgr: PadMapAdbManager, host: String, port: Int): Boolean {
        if (mgr.connect(host, port)) return true
        if (host != "127.0.0.1" && mgr.connect("127.0.0.1", port)) return true
        return false
    }

    private fun extractJar(context: Context): File {
        val out = File(context.getExternalFilesDir(null), "padmap-sidecar.jar")
        context.assets.open("sidecar/padmap-sidecar.jar").use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    private fun shell(mgr: PadMapAdbManager, command: String): String {
        val stream = mgr.openStream("shell:$command")
        stream.openInputStream().use { input ->
            return input.readBytes().toString(Charset.forName("UTF-8"))
        }
    }
}
