package com.slickstax841.padmap.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import com.slickstax841.padmap.data.DataStore
import com.slickstax841.padmap.inject.SidecarClient

object PlaybackDebug {
    private const val MAX = 50
    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private var lastMotionLogMs = 0L

    fun log(msg: String) {
        val t = System.currentTimeMillis() % 100_000
        synchronized(lock) {
            lines.addLast("$t $msg")
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    fun logMotion(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastMotionLogMs < 400L) return
        lastMotionLogMs = now
        log(msg)
    }

    fun snapshot(): String {
        val layout = DataStore.activeLayout
        val data = DataStore.data.value
        val svc = PadMapAccessibilityService.instance
        val info = svc?.serviceInfo
        val keyOn = info != null &&
            (info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        return buildString {
            appendLine("PadMap playback debug")
            appendLine(
                "injector available=${SidecarClient.isAvailable} ping=${SidecarClient.ping()} err=${SidecarClient.lastError}"
            )
            appendLine("uiVisible=${svc?.padMapUiVisible} fg=${svc?.foregroundPackage}")
            appendLine(
                "layout=${layout?.name} maps=${layout?.mappings?.size ?: 0} " +
                    "named=${layout?.mappings?.count { it.inputName.isNotBlank() } ?: 0} pkg=${layout?.packageName}"
            )
            appendLine(
                "names=${layout?.mappings?.joinToString(",") { it.inputName.ifBlank { "?" } }}"
            )
            appendLine(
                "preset=${data.controllerPresets.find { it.id == data.activePresetId }?.name} keyFilter=$keyOn"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && info != null) {
                appendLine("motionSources=${info.motionEventSources}")
            } else {
                appendLine("motionSources=n/a (need API 34 for a11y sticks)")
            }
            appendLine("events:")
            synchronized(lock) {
                if (lines.isEmpty()) appendLine("(none — press a button or move a stick)")
                else lines.forEach { appendLine(it) }
            }
        }
    }
}
