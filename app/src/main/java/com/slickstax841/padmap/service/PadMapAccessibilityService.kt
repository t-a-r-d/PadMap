package com.slickstax841.padmap.service

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.slickstax841.padmap.ControllerEventBus
import com.slickstax841.padmap.data.ButtonMode
import com.slickstax841.padmap.data.ButtonTuningStore
import com.slickstax841.padmap.data.DataStore
import com.slickstax841.padmap.data.GameLayout
import com.slickstax841.padmap.data.MappingEntry
import com.slickstax841.padmap.data.TouchAction
import com.slickstax841.padmap.inject.SidecarClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class PadMapAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PadMapAccessibilityService? = null
        private const val DEAD_ZONE = 0.15f
        private const val TURBO_INTERVAL_MS = 100L
        private const val STICK_TICK_MS = 16L
    }

    private data class StickState(
        val drag: TouchAction.Drag,
        var currentX: Float,
        var currentY: Float,
        val lookMode: Boolean,
        val pointerId: Int
    )

    private data class HoldState(
        val entries: List<MappingEntry>,
        val pointerIds: List<Int>
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeSticks = mutableMapOf<String, StickState>()
    private val activeHolds = mutableMapOf<String, HoldState>()
    private val turboJobs = mutableMapOf<String, Job>()
    private val tapRepeatJobs = mutableMapOf<String, Job>()
    private val axisValues = mutableMapOf<String, Pair<Float, Float>>()
    private val hatState = mutableMapOf<String, Boolean>()
    private val freePointerIds = ArrayDeque<Int>().apply { addAll(0..9) }

    private var stickLoopRunning = false
    private var warnedNoSidecar = false

    var foregroundPackage: String = ""
        private set
    var lastGamePackage: String = ""
        private set
    var padMapUiVisible: Boolean = false

    private val stickRunnable = object : Runnable {
        override fun run() {
            tickSticks()
            if (activeSticks.isNotEmpty()) mainHandler.postDelayed(this, STICK_TICK_MS)
            else stickLoopRunning = false
        }
    }

    private fun allocPointer(): Int = freePointerIds.removeFirstOrNull() ?: 0
    private fun freePointer(id: Int) {
        if (id >= 0 && !freePointerIds.contains(id)) freePointerIds.addFirst(id)
    }

    private fun ensureStickLoop() {
        if (!stickLoopRunning) {
            stickLoopRunning = true
            mainHandler.post(stickRunnable)
        }
    }

    private fun sidecarReady(): Boolean {
        if (SidecarClient.isAvailable || SidecarClient.ping()) return true
        if (!warnedNoSidecar) {
            warnedNoSidecar = true
            OverlayManager.instance?.showToast("Injector not running — pair wireless debugging in Settings")
        }
        return false
    }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val device = InputDevice.getDevice(deviceId) ?: return
            val match = DataStore.data.value.controllerPresets.find { preset ->
                preset.deviceName.isNotBlank() &&
                    device.name.contains(preset.deviceName, ignoreCase = true)
            }
            if (match != null) {
                DataStore.update { it.copy(activePresetId = match.id) }
                OverlayManager.instance?.showToast("Controller matched: ${match.name}")
            }
        }
        override fun onInputDeviceRemoved(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DataStore.init(applicationContext)
        getSystemService(InputManager::class.java)
            .registerInputDeviceListener(deviceListener, null)
        updateInputInterception()
        if (OverlayManager.instance == null) OverlayManager.instance = OverlayManager(this)
        OverlayManager.instance?.startService()
        SidecarClient.ping()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        mainHandler.removeCallbacks(stickRunnable)
        stickLoopRunning = false
        SidecarClient.releaseAll()
        activeSticks.clear()
        activeHolds.clear()
        freePointerIds.clear()
        freePointerIds.addAll(0..9)
        getSystemService(InputManager::class.java)
            .unregisterInputDeviceListener(deviceListener)
        OverlayManager.instance?.detach()
        OverlayManager.instance = null
    }

    fun restoreGamePackage(pkg: String) {
        if (pkg.isNotBlank()) foregroundPackage = pkg
    }

    fun disableAndStop() {
        OverlayManager.instance?.detach()
        OverlayManager.instance = null
        disableSelf()
    }

    override fun onInterrupt() {}

    fun handlePlaybackDown(keyCode: Int) {
        onButtonDown(labelFor(keyCode) ?: return)
    }

    fun handlePlaybackUp(keyCode: Int) {
        onButtonUp(labelFor(keyCode) ?: return)
    }

    fun handlePlaybackMotion(event: MotionEvent) {
        val layout = DataStore.activeLayout ?: return
        val preset = DataStore.data.value.controllerPresets.find { it.id == layout.controllerPresetId }

        processHatAxis(event, layout, MotionEvent.AXIS_HAT_X, "D-Left", "D-Right")
        processHatAxis(event, layout, MotionEvent.AXIS_HAT_Y, "D-Up", "D-Down")

        for ((axisCode, axisLabelStr) in (preset?.axes ?: emptyMap())) {
            if (axisCode == MotionEvent.AXIS_HAT_X || axisCode == MotionEvent.AXIS_HAT_Y) continue
            val stickLabel = axisLabelStr.removeSuffix(" X").removeSuffix(" Y")
            if (layout.mappings.none { it.inputName == stickLabel }) continue
            val value = event.getAxisValue(axisCode)
            val isX = axisLabelStr.endsWith(" X")
            val cur = axisValues[stickLabel] ?: (0f to 0f)
            axisValues[stickLabel] = if (isX) cur.copy(first = value) else cur.copy(second = value)
        }

        for ((stickLabel, pair) in axisValues) {
            val (rawX, rawY) = pair
            val entries = layout.mappings.filter { it.inputName == stickLabel }
            val deadZone = (entries.firstOrNull()?.action as? TouchAction.Drag)?.deadZone ?: DEAD_ZONE
            val (sx, sy) = radialDeadZone(rawX, rawY, deadZone)
            val mag = sqrt(sx * sx + sy * sy)
            if (mag < 0.01f) {
                // release handled in tick
            } else if (activeSticks[stickLabel] == null) {
                entries.forEach { entry ->
                    val drag = entry.action as? TouchAction.Drag ?: return@forEach
                    startStick(stickLabel, drag)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg in OverlayManager.BLOCKED_PACKAGES) return
                if (pkg == packageName) return
                foregroundPackage = pkg
                lastGamePackage = pkg
                OverlayManager.instance?.repositionForGame(pkg)
                val layoutMatch = DataStore.data.value.gameLayouts.find { it.packageName == pkg }
                if (layoutMatch != null) {
                    if (DataStore.data.value.activeLayoutId != layoutMatch.id) {
                        DataStore.update { it.copy(activeLayoutId = layoutMatch.id) }
                        updateInputInterception()
                    }
                } else if (isGameOrUnknownApp(pkg)) {
                    val appName = try {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Exception) { pkg }
                    val id = java.util.UUID.randomUUID().toString()
                    DataStore.update { it.copy(
                        gameLayouts = it.gameLayouts + GameLayout(
                            id = id, name = appName, packageName = pkg,
                            controllerPresetId = it.activePresetId
                        ),
                        activeLayoutId = id
                    )}
                    updateInputInterception()
                    OverlayManager.instance?.showToast("Layout created: $appName")
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (OverlayManager.instance?.state == OverlayManager.State.CONFIG) return
                val hasActiveSystemWindow = windows.any {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM && it.isFocused
                }
                if (hasActiveSystemWindow) {
                    OverlayManager.instance?.repositionForGame("com.android.systemui")
                }
            }
        }
    }

    @SuppressLint("NewApi")
    internal fun updateInputInterception() {
        val info = serviceInfo ?: return
        val inConfig = OverlayManager.instance?.state == OverlayManager.State.CONFIG
        val layout = DataStore.activeLayout
        val needsKeyFilter = inConfig ||
            layout?.mappings?.any { it.action is TouchAction.Tap } == true
        val flagKey = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val newFlags = if (needsKeyFilter) info.flags or flagKey else info.flags and flagKey.inv()
        var changed = info.flags != newFlags
        info.flags = newFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val needsJoystick = inConfig ||
                layout?.mappings?.any { it.action is TouchAction.Drag } == true
            val newSources = if (needsJoystick) InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD else 0
            if (info.motionEventSources != newSources) {
                info.motionEventSources = newSources
                changed = true
            }
        }
        if (changed) serviceInfo = info
    }

    private fun isGameOrUnknownApp(pkg: String): Boolean {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            if (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) return false
            if (info.category == android.content.pm.ApplicationInfo.CATEGORY_GAME) return true
            @Suppress("DEPRECATION")
            if (info.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0) return true
            info.category == android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED
        } catch (_: Exception) { false }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val overlay = OverlayManager.instance
        if (event.action == KeyEvent.ACTION_DOWN &&
            overlay?.pendingZoneId != null &&
            overlay.state == OverlayManager.State.CONFIG
        ) {
            overlay.assignPendingZone(labelFor(event.keyCode) ?: keyCodeLabel(event.keyCode), false)
            return true
        }
        if (overlay?.state == OverlayManager.State.CONFIG) return true
        if (padMapUiVisible) {
            ControllerEventBus.emitKey(event)
            return false
        }
        val label = labelFor(event.keyCode) ?: return false
        val layout = DataStore.activeLayout ?: return false
        if (layout.mappings.none { it.inputName == label }) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                onButtonDown(label)
                return true
            }
            KeyEvent.ACTION_UP -> {
                onButtonUp(label)
                return true
            }
            else -> return false
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onMotionEvent(event: MotionEvent) {
        val isJoystick = event.source and InputDevice.SOURCE_JOYSTICK != 0
        val isGamepad = event.source and InputDevice.SOURCE_GAMEPAD != 0
        if (!isJoystick && !isGamepad) return
        if (padMapUiVisible) {
            listOf(
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
            ).forEach { axis -> ControllerEventBus.emitAxis(axis, event.getAxisValue(axis)) }
            return
        }
        val overlay = OverlayManager.instance
        if (overlay?.pendingZoneId != null && overlay.state == OverlayManager.State.CONFIG) {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (kotlin.math.abs(hatX) > 0.5f || kotlin.math.abs(hatY) > 0.5f) {
                val label = when {
                    hatX < -0.5f -> "D-Left"
                    hatX > 0.5f -> "D-Right"
                    hatY < -0.5f -> "D-Up"
                    else -> "D-Down"
                }
                overlay.assignPendingZone(label, false)
                return
            }
            val preset = overlay.editingLayout?.let { l ->
                DataStore.data.value.controllerPresets.find { it.id == l.controllerPresetId }
            }
            for ((axisCode, axisLabel) in (preset?.axes ?: emptyMap())) {
                if (kotlin.math.abs(event.getAxisValue(axisCode)) > 0.5f) {
                    overlay.assignPendingZone(axisLabel.removeSuffix(" X").removeSuffix(" Y"), true)
                    return
                }
            }
            return
        }
        if (overlay?.state == OverlayManager.State.CONFIG) return
        handlePlaybackMotion(event)
    }

    private fun onButtonDown(label: String) {
        val layout = DataStore.activeLayout ?: return
        val entries = layout.mappings.filter { it.inputName == label }
        if (entries.isEmpty() || !sidecarReady()) return
        val nonTurbo = entries.filter { !it.turbo }
        val turboEntries = entries.filter { it.turbo }
        val mode = nonTurbo.firstOrNull()?.let { ButtonTuningStore.get(it.zoneId).mode } ?: ButtonMode.TAP
        when (mode) {
            ButtonMode.HOLD -> startHold(label, nonTurbo)
            ButtonMode.TAP -> fireTaps(nonTurbo)
            ButtonMode.REPEAT -> {
                tapRepeatJobs[label]?.cancel()
                tapRepeatJobs[label] = scope.launch {
                    while (isActive) {
                        val captured = nonTurbo.toList()
                        val interval = captured.firstOrNull()
                            ?.let { ButtonTuningStore.get(it.zoneId).repeatIntervalMs }
                            ?: TURBO_INTERVAL_MS
                        mainHandler.post { fireTaps(captured) }
                        delay(interval)
                    }
                }
            }
        }
        if (turboEntries.isNotEmpty()) {
            turboJobs[label]?.cancel()
            turboJobs[label] = scope.launch {
                while (isActive) {
                    val captured = turboEntries.toList()
                    mainHandler.post { fireTaps(captured) }
                    delay(TURBO_INTERVAL_MS)
                }
            }
        }
    }

    private fun onButtonUp(label: String) {
        val layout = DataStore.activeLayout ?: return
        val mode = layout.mappings.filter { it.inputName == label && !it.turbo }
            .firstOrNull()?.let { ButtonTuningStore.get(it.zoneId).mode } ?: ButtonMode.TAP
        when (mode) {
            ButtonMode.HOLD -> releaseHold(label)
            ButtonMode.TAP -> {}
            ButtonMode.REPEAT -> {
                tapRepeatJobs[label]?.cancel()
                tapRepeatJobs.remove(label)
            }
        }
        turboJobs[label]?.cancel()
        turboJobs.remove(label)
    }

    private fun startHold(label: String, entries: List<MappingEntry>) {
        if (entries.isEmpty() || !sidecarReady()) return
        activeHolds.remove(label)?.let { old ->
            old.pointerIds.forEach { pid ->
                SidecarClient.pointerUp(pid)
                freePointer(pid)
            }
        }
        val pids = entries.map { allocPointer() }
        activeHolds[label] = HoldState(entries, pids)
        entries.forEachIndexed { i, entry ->
            val (x, y) = tapXY(entry)
            SidecarClient.pointerDown(pids[i], x, y)
        }
    }

    private fun releaseHold(label: String) {
        val hold = activeHolds.remove(label) ?: return
        hold.pointerIds.forEach { pid ->
            SidecarClient.pointerUp(pid)
            freePointer(pid)
        }
    }

    private fun fireTaps(entries: List<MappingEntry>) {
        if (entries.isEmpty() || !sidecarReady()) return
        entries.forEach { entry ->
            val t = ButtonTuningStore.get(entry.zoneId)
            val (x, y) = tapXY(entry)
            val pid = allocPointer()
            scope.launch {
                try {
                    mainHandler.post { SidecarClient.pointerDown(pid, x, y) }
                    delay(t.tapDurationMs.coerceAtLeast(16L))
                    mainHandler.post {
                        SidecarClient.pointerUp(pid)
                        freePointer(pid)
                    }
                } catch (_: Throwable) {
                    freePointer(pid)
                }
            }
        }
    }

    private fun startStick(label: String, drag: TouchAction.Drag) {
        if (activeSticks.containsKey(label) || !sidecarReady()) return
        val pid = allocPointer()
        activeSticks[label] = StickState(drag, drag.centerX, drag.centerY, drag.lookMode, pid)
        SidecarClient.pointerDown(pid, drag.centerX, drag.centerY)
        ensureStickLoop()
    }

    private fun tickSticks() {
        if (activeSticks.isEmpty()) return
        val updates = mutableMapOf<Int, Pair<Float, Float>>()
        val toRelease = mutableListOf<String>()
        for ((label, state) in activeSticks.toMap()) {
            val (rawX, rawY) = axisValues[label] ?: (0f to 0f)
            val (sx, sy) = radialDeadZone(rawX, rawY, state.drag.deadZone)
            val mag = sqrt(sx * sx + sy * sy)
            val zoneId = DataStore.activeLayout?.mappings
                ?.firstOrNull { it.inputName == label }?.zoneId?.ifBlank { label } ?: label
            val tuning = ButtonTuningStore.getStick(zoneId)
            val scale = tuning.sensitivityPct
            if (mag < 0.01f) {
                toRelease.add(label)
                OverlayManager.instance?.updateStickDebug(
                    label, state.drag.centerX, state.drag.centerY, rawX, rawY, false
                )
                continue
            }
            if (state.lookMode) {
                val step = tuning.lookSpeedPx * scale
                var nx = state.currentX + sx * step
                var ny = state.currentY + sy * step
                val dx = nx - state.drag.centerX
                val dy = ny - state.drag.centerY
                if (sqrt(dx * dx + dy * dy) > state.drag.radius) {
                    SidecarClient.pointerUp(state.pointerId)
                    SidecarClient.pointerDown(state.pointerId, state.drag.centerX, state.drag.centerY)
                    nx = state.drag.centerX + sx * step
                    ny = state.drag.centerY + sy * step
                }
                state.currentX = nx
                state.currentY = ny
                updates[state.pointerId] = nx to ny
            } else {
                val nx = state.drag.centerX + sx * state.drag.radius * scale
                val ny = state.drag.centerY + sy * state.drag.radius * scale
                state.currentX = nx
                state.currentY = ny
                updates[state.pointerId] = nx to ny
            }
            OverlayManager.instance?.updateStickDebug(label, state.currentX, state.currentY, sx, sy, true)
        }
        for (label in toRelease) {
            val state = activeSticks.remove(label) ?: continue
            SidecarClient.pointerUp(state.pointerId)
            freePointer(state.pointerId)
        }
        if (updates.isNotEmpty()) SidecarClient.batchUpdate(updates)
    }

    private fun tapXY(entry: MappingEntry): Pair<Float, Float> = when (val a = entry.action) {
        is TouchAction.Tap -> a.x to a.y
        is TouchAction.Drag -> a.centerX to a.centerY
    }

    private fun radialDeadZone(rawX: Float, rawY: Float, deadZone: Float): Pair<Float, Float> {
        val mag = sqrt(rawX * rawX + rawY * rawY)
        if (mag < deadZone) return 0f to 0f
        val scale = ((mag - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
        return (rawX / mag * scale) to (rawY / mag * scale)
    }

    private fun processHatAxis(
        event: MotionEvent, layout: GameLayout, axisCode: Int, negLabel: String, posLabel: String
    ) {
        val value = event.getAxisValue(axisCode)
        val pressedLabel = when {
            value < -0.5f -> negLabel
            value > 0.5f -> posLabel
            else -> null
        }
        for (label in listOf(negLabel, posLabel)) {
            val wasPressed = hatState[label] == true
            val nowPressed = label == pressedLabel
            if (nowPressed && !wasPressed) onButtonDown(label)
            if (!nowPressed && wasPressed) onButtonUp(label)
            hatState[label] = nowPressed
        }
    }

    private fun labelFor(keyCode: Int): String? {
        val layout = DataStore.activeLayout ?: return null
        val preset = DataStore.data.value.controllerPresets.find { it.id == layout.controllerPresetId }
        return preset?.buttons?.get(keyCode) ?: keyCodeLabel(keyCode)
    }

    private fun keyCodeLabel(code: Int) = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "LB"
        KeyEvent.KEYCODE_BUTTON_R1 -> "RB"
        KeyEvent.KEYCODE_BUTTON_L2 -> "LT"
        KeyEvent.KEYCODE_BUTTON_R2 -> "RT"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        KeyEvent.KEYCODE_DPAD_UP -> "D-Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "D-Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "D-Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "D-Right"
        else -> "Btn$code"
    }
}
