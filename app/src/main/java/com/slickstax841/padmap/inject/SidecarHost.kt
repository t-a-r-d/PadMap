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

    @Volatile
    var status: String = "Injector not started"
        private set

    fun isWirelessDebugOn(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) > 0
    }

    fun hasPaired(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, 0).getBoolean(KEY_PAIRED, false)
    }

    private fun markPaired(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_PAIRED, true).apply()
    }

    /**
     * After the first pairing, just flip Wireless debugging on and this
     * reconnects and starts the sidecar. No 6-digit code.
     */
    fun ensureRunning(context: Context): Boolean {
        if (SidecarClient.ping()) {
            status = "Injector running"
            return true
        }
        if (!isWirelessDebugOn(context)) {
            status = if (hasPaired(context)) "Turn on Wireless debugging" else "First-time pair needed"
            return false
        }
        if (!hasPaired(context)) {
            status = "First-time pair needed"
            return false
        }
        val ep = NsdAdbFinder.find(context, pairing = false)
            ?: throw IllegalStateException("Wireless debugging is on but the port was not found. Open PadMap Settings.")
        start(context, ep.host, ep.port)
        return SidecarClient.ping()
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
        if (!mgr.connect(connectHost, connectPort)) {
            throw IllegalStateException("ADB connect failed on $connectHost:$connectPort")
        }
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
