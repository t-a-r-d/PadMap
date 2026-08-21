package com.slickstax841.padmap.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import com.slickstax841.padmap.data.DataStore
import com.slickstax841.padmap.inject.SidecarClient
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

object PlaybackDebug {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private var lastMotionLogMs = 0L
    @Volatile private var lastStickContext = "none"
    private var file: File? = null
    private var appVer = "?"
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "padmap-debug").apply { isDaemon = true }
    }

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        file = File(app.filesDir, "playback-debug.log")
        appVer = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        synchronized(lock) {
            lines.clear()
            file?.takeIf { it.exists() }?.readLines()?.takeLast(MAX)?.forEach { lines.addLast(it) }
        }
        installCrashHook()
        log("boot ver=$appVer pid=${android.os.Process.myPid()}")
    }

    fun log(msg: String) {
        val t = System.currentTimeMillis() % 100_000
        val line = "$t $msg"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        val f = file
        io.execute {
            runCatching {
                f?.appendText(line + "\n")
                if (f != null && f.length() > 80_000L) rewriteDisk()
            }
        }
    }

    fun logMotion(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastMotionLogMs < 250L) return
        lastMotionLogMs = now
        log(msg)
    }

    /** Lightweight state retained for a later fault; it does not write per frame. */
    fun noteStickContext(context: String) {
        lastStickContext = context
    }

    /** Persist a protected-path failure before allowing gameplay to continue. */
    fun recordFault(tag: String, error: Throwable) {
        val line = "${System.currentTimeMillis() % 100_000} FAULT $tag " +
            "${error.javaClass.simpleName}: ${error.message} sticks=$lastStickContext"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        writeCrash(line + "\n" + error.stackTraceToString() + "\n")
    }

    fun snapshot(): String {
        val layout = DataStore.activeLayout
        val data = DataStore.data.value
        val svc = PadMapAccessibilityService.instance
        val info = svc?.serviceInfo
        val keyOn = info != null &&
            (info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        return buildString {
            appendLine("PadMap playback debug v$appVer")
            appendLine("pid=${android.os.Process.myPid()} api=${Build.VERSION.SDK_INT}")
            appendLine(
                "injector available=${SidecarClient.isAvailable} ping=${SidecarClient.ping()} err=${SidecarClient.lastError}"
            )
            appendLine(
                "uiVisible=${svc?.padMapUiVisible} fg=${svc?.foregroundPackage} " +
                    "play=${svc?.playingPackageDebug} lastGame=${svc?.lastGamePackage} " +
                    "holds=${svc?.activeHoldCount ?: 0} sticks=${svc?.activeStickCount ?: 0} " +
                    "layer=${svc?.activeLayer ?: 1}"
            )
            appendLine(svc?.debugExtras ?: "a11y=null")
            appendLine("lastStick=$lastStickContext")
            appendLine(
                "overlay=${OverlayManager.instance?.state} pending=${OverlayManager.instance?.pendingZoneId}"
            )
            appendLine(
                "layout=${layout?.name} maps=${layout?.mappings?.size ?: 0} " +
                    "named=${layout?.mappings?.count { it.inputName.isNotBlank() } ?: 0} pkg=${layout?.packageName}"
            )
            appendLine(
                "names=${layout?.mappings?.joinToString(",") {
                    val kind = when (it.action) {
                        is com.slickstax841.padmap.data.TouchAction.Drag -> if (it.action.lookMode) "look" else "move"
                        else -> if (it.turbo) "turbo" else "btn"
                    }
                    "${it.inputName.ifBlank { "?" }}:$kind:L${it.layer}"
                }}"
            )
            appendLine(
                "preset=${data.controllerPresets.find { it.id == data.activePresetId }?.name} keyFilter=$keyOn"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && info != null) {
                appendLine("motionSources=${info.motionEventSources}")
            } else {
                appendLine("motionSources=n/a (need API 34 for a11y sticks)")
            }
            appendLine("events (oldest→newest, kept across crash):")
            synchronized(lock) {
                if (lines.isEmpty()) appendLine("(none — press a button or move a stick)")
                else lines.forEach { appendLine(it) }
            }
        }
    }

    private fun rewriteDisk() {
        val f = file ?: return
        val body = synchronized(lock) { lines.joinToString("\n") + "\n" }
        f.writeText(body)
    }

    private fun writeCrash(text: String) {
        val f = file ?: return
        runCatching {
            FileOutputStream(f, true).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
        }
    }

    private fun installCrashHook() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val stack = e.stackTraceToString()
            val line = "${System.currentTimeMillis() % 100_000} FATAL ${t.name} ${e.javaClass.simpleName}: ${e.message}"
            synchronized(lock) {
                lines.addLast(line)
                while (lines.size > MAX) lines.removeFirst()
            }
            writeCrash(line + " sticks=$lastStickContext\n" + stack + "\n")
            prev?.uncaughtException(t, e)
        }
    }
}
