package com.slickstax841.padmap.inject

import android.content.Context
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
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
        mgr.setTimeout(8, TimeUnit.SECONDS)
        // pair() and a failed first connect can leave the manager "connected";
        // libadb then returns false immediately on the next connect().
        Thread.sleep(400)
        if (!connectAny(context, mgr, connectHost, connectPort)) {
            throw IllegalStateException("ADB connect failed on $connectHost:$connectPort")
        }
        saveEndpoint(context, AdbEndpoint(connectHost, connectPort))
        val remote = "/data/local/tmp/padmap-sidecar.jar"
        status = "Installing injector…"
        // Do not cp from /storage/emulated/0 — ColorOS FUSE often never returns.
        pushJar(mgr, readAssetJar(context), remote)
        shell(mgr, "chmod 644 $remote")
        shell(mgr, "kill \$(cat /data/local/tmp/padmap-sidecar.pid) 2>/dev/null; true")
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

    private fun pushJar(mgr: PadMapAdbManager, bytes: ByteArray, remote: String) {
        val stream = mgr.openStream("shell:dd of=$remote")
        try {
            stream.openOutputStream().use { out ->
                out.write(bytes)
                out.flush()
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun shell(mgr: PadMapAdbManager, command: String): String {
        val stream = mgr.openStream("shell:$command")
        return try {
            val inp = stream.openInputStream()
            val buf = ByteArray(4096)
            val acc = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + 8000
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
