package com.slickstax841.padmap.service

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.slickstax841.padmap.ControllerEventBus
import com.slickstax841.padmap.data.AndroidApiLevel
import com.slickstax841.padmap.data.ButtonMode
import com.slickstax841.padmap.data.ButtonTuningStore
import com.slickstax841.padmap.data.DataStore
import com.slickstax841.padmap.data.GameLayout
import com.slickstax841.padmap.data.MappingEntry
import com.slickstax841.padmap.data.TouchAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

class PadMapAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PadMapAccessibilityService? = null
        private const val DEAD_ZONE = 0.15f
        private const val STICK_SEGMENT_MS = 50L
        private const val HOLD_SEGMENT_MS = 20L
        private const val HOLD_RELEASE_MS = 16L
        private const val TURBO_INTERVAL_MS = 100L
        private const val STICK_TICK_MS = 16L
    }

    private data class StickState(
        val drag: TouchAction.Drag,
        var currentX: Float,
        var currentY: Float,
        @Volatile var active: Boolean = true,
        val lookMode: Boolean = false,
        var lastSx: Float = 0f,
        var lastSy: Float = 0f,
        var latchStartMs: Long = 0L,
        var isCoasting: Boolean = false,
        var coastUntilMs: Long = 0L,
        // InjectManager path only — pointer ID allocated from pool; -1 for dispatchGesture path
        val pointerId: Int = -1
    )

    private data class HoldState(
        val entries: List<MappingEntry>,
        @Volatile var active: Boolean = true,
        var isReleasing: Boolean = false,
        var tick: Int = 0,
        // InjectManager path only — one pointer ID per entry
        val pointerIds: List<Int> = emptyList()
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeSticks = mutableMapOf<String, StickState>()
    private val activeHolds  = mutableMapOf<String, HoldState>()
    private val turboJobs       = mutableMapOf<String, Job>()
    private val tapRepeatJobs   = mutableMapOf<String, Job>()
    private val holdRepeatJobs  = mutableMapOf<String, Job>()
    private val axisValues   = mutableMapOf<String, Pair<Float, Float>>()
    private val hatState     = mutableMapOf<String, Boolean>()

    // ─── Pointer ID pool (InjectManager path) ────────────────────────────────────
    // Android supports up to 10 simultaneous touch pointers (IDs 0–9).
    private val freePointerIds = ArrayDeque<Int>().apply { addAll(0..9) }
    private fun allocPointer(): Int = freePointerIds.removeFirstOrNull() ?: 0
    private fun freePointer(id: Int) {
        if (id >= 0 && !freePointerIds.contains(id)) freePointerIds.addFirst(id)
    }

    // ─── Stick loop (InjectManager path) ─────────────────────────────────────────
    // Fixed 16ms timer drives stick injection when InjectManager is available.
    // Runs on the main thread (same as all accessibility callbacks) so no
    // concurrency guards needed for activeSticks / axisValues access.
    private var stickLoopRunning = false

    private val stickRunnable = object : Runnable {
        override fun run() {
            tickInjectSticks()
            if (activeSticks.isNotEmpty()) {
                mainHandler.postDelayed(this, STICK_TICK_MS)
            } else {
                stickLoopRunning = false
            }
        }
    }

    private fun ensureStickLoopRunning() {
        if (!stickLoopRunning) {
            stickLoopRunning = true
            mainHandler.post(stickRunnable)
        }
    }

    // ─── Master gesture dispatcher (dispatchGesture fallback path) ────────────────
    // Used when InjectManager.isAvailable == false.
    private val channelStrokes = mutableMapOf<String, GestureDescription.StrokeDescription>()
    private val pendingTaps = mutableListOf<GestureDescription.StrokeDescription>()
    private var masterRunning = false

    private fun ensureMasterRunning() {
        if (!masterRunning) {
            masterRunning = true
            masterTick()
        }
    }

    private fun masterTick() {
        val builder = GestureDescription.Builder()
        var strokeCount = 0
        val nextStrokes = mutableMapOf<String, GestureDescription.StrokeDescription>()

        // ── Sticks ──────────────────────────────────────────────────────────
        for ((label, state) in activeSticks.toMap()) {
            val channelId = "stick:$label"
            val (rawX, rawY) = axisValues[label] ?: (0f to 0f)
            val (sx, sy) = radialDeadZone(rawX, rawY, state.drag.deadZone)
            val mag = sqrt(sx * sx + sy * sy)
            val prev = channelStrokes[channelId]
            val stickTuning = ButtonTuningStore.getStick(label)
            val segMs = stickTuning.speedMs
            val apiLevel = stickTuning.androidApiLevel
            val latchMs = stickTuning.latchMs
            val coastMs = stickTuning.coastMs
            val nowMs = System.currentTimeMillis()

            if (mag >= 0.01f) {
                state.lastSx = sx; state.lastSy = sy
                state.latchStartMs = 0L
                state.isCoasting = false; state.coastUntilMs = 0L
            } else if (state.latchStartMs == 0L) {
                state.latchStartMs = nowMs
            }
            val latchElapsed = if (state.latchStartMs > 0L) nowMs - state.latchStartMs else 0L
            val withinLatch = mag < 0.01f && latchElapsed < latchMs
            if (mag < 0.01f && latchElapsed >= latchMs && !state.isCoasting &&
                coastMs > 0L && (state.lastSx != 0f || state.lastSy != 0f)) {
                state.isCoasting = true
                state.coastUntilMs = nowMs + coastMs
            }
            val coastActive = state.isCoasting && nowMs < state.coastUntilMs
            val activeSx = if (mag >= 0.01f) sx else state.lastSx
            val activeSy = if (mag >= 0.01f) sy else state.lastSy
            val activeMag = if (mag >= 0.01f) mag
                            else sqrt(activeSx * activeSx + activeSy * activeSy)
            val shouldRelease = mag < 0.01f && !withinLatch && !coastActive

            if (shouldRelease) {
                val relPath = if (state.lookMode) {
                    Path().apply {
                        moveTo(state.currentX, state.currentY)
                        lineTo(state.drag.centerX, state.drag.centerY)
                    }
                } else {
                    Path().apply {
                        moveTo(state.currentX, state.currentY)
                        lineTo(state.currentX + 1f, state.currentY)
                    }
                }
                val stroke = if (apiLevel == AndroidApiLevel.V13 && prev != null)
                    prev.continueStroke(relPath, 0L, segMs, false)
                else
                    GestureDescription.StrokeDescription(relPath, 0L, segMs, false)
                builder.addStroke(stroke); strokeCount++
                channelStrokes.remove(channelId)
                activeSticks.remove(label)
                OverlayManager.instance?.updateStickDebug(label, state.drag.centerX, state.drag.centerY, rawX, rawY, false)
            } else {
                val fromX = if (apiLevel == AndroidApiLevel.V11) state.drag.centerX
                            else if (prev == null) state.drag.centerX else state.currentX
                val fromY = if (apiLevel == AndroidApiLevel.V11) state.drag.centerY
                            else if (prev == null) state.drag.centerY else state.currentY
                val newX: Float; val newY: Float
                val dirX = if (activeMag > 0f) activeSx / activeMag else 0f
                val dirY = if (activeMag > 0f) activeSy / activeMag else 0f
                if (apiLevel == AndroidApiLevel.V11) {
                    newX = state.drag.centerX + dirX * activeMag * state.drag.radius * 0.84f * stickTuning.sensitivityPct
                    newY = state.drag.centerY + dirY * activeMag * state.drag.radius * 0.84f * stickTuning.sensitivityPct
                } else {
                    val sweepStep = activeMag * state.drag.radius / 8f * stickTuning.sensitivityPct
                    val rawNewX = state.currentX + dirX * sweepStep
                    val rawNewY = state.currentY + dirY * sweepStep
                    val dxC = rawNewX - state.drag.centerX
                    val dyC = rawNewY - state.drag.centerY
                    if (sqrt(dxC * dxC + dyC * dyC) > state.drag.radius * 0.85f) {
                        if (state.lookMode) {
                            newX = state.drag.centerX + dirX * state.drag.radius * 0.1f
                            newY = state.drag.centerY + dirY * state.drag.radius * 0.1f
                        } else {
                            val clampX = state.drag.centerX + dirX * state.drag.radius * 0.84f
                            val clampY = state.drag.centerY + dirY * state.drag.radius * 0.84f
                            val near = abs(state.currentX - clampX) < 0.3f &&
                                       abs(state.currentY - clampY) < 0.3f
                            newX = if (near) clampX + 0.5f else clampX
                            newY = if (near) clampY + 0.5f else clampY
                        }
                    } else {
                        newX = rawNewX; newY = rawNewY
                    }
                }
                val path = Path().apply { moveTo(fromX, fromY); lineTo(newX, newY) }
                val stroke = when (apiLevel) {
                    AndroidApiLevel.V13 ->
                        prev?.continueStroke(path, 0L, segMs, true)
                            ?: GestureDescription.StrokeDescription(path, 0L, segMs, true)
                    AndroidApiLevel.V12, AndroidApiLevel.V11 ->
                        GestureDescription.StrokeDescription(path, 0L, segMs, false)
                }
                builder.addStroke(stroke); strokeCount++
                if (apiLevel == AndroidApiLevel.V13) nextStrokes[channelId] = stroke
                state.currentX = newX; state.currentY = newY
                OverlayManager.instance?.updateStickDebug(label, newX, newY, activeSx, activeSy, true)
            }
        }

        // ── Holds ────────────────────────────────────────────────────────────
        for ((label, state) in activeHolds.toMap()) {
            if (state.isReleasing) continue
            state.tick++
            val alt = state.tick % 2 == 1
            val willContinue = state.active
            state.entries.forEachIndexed { i, entry ->
                val entryTuning = ButtonTuningStore.get(entry.zoneId)
                val holdSize = entryTuning.tapSizePx
                val holdDur  = if (willContinue) entryTuning.tapDurationMs else entryTuning.holdReleaseDurationMs
                val channelId = "hold:$label:$i"
                val (x, y) = tapXY(entry)
                val prev = channelStrokes[channelId]
                val stroke = prev?.continueStroke(holdPath(x, y, alt, holdSize), 0L, holdDur, willContinue)
                    ?: GestureDescription.StrokeDescription(holdPath(x, y, false, holdSize), i * 10L, holdDur, willContinue)
                builder.addStroke(stroke); strokeCount++
                if (willContinue) nextStrokes[channelId] = stroke
                else channelStrokes.remove(channelId)
            }
            if (!willContinue) state.isReleasing = true
        }

        // ── Discrete taps ────────────────────────────────────────────────────
        pendingTaps.forEach { builder.addStroke(it); strokeCount++ }
        pendingTaps.clear()

        channelStrokes.putAll(nextStrokes)

        if (strokeCount == 0) { masterRunning = false; return }

        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) {
                val iter = activeHolds.iterator()
                while (iter.hasNext()) { if (iter.next().value.isReleasing) iter.remove() }
                val hasMore = activeSticks.isNotEmpty() || activeHolds.isNotEmpty() || pendingTaps.isNotEmpty()
                if (hasMore) masterTick() else { masterRunning = false; channelStrokes.clear() }
            }
            override fun onCancelled(g: GestureDescription) {
                activeSticks.clear(); activeHolds.clear()
                channelStrokes.clear(); masterRunning = false
            }
        }, null)
    }

    // ─── InjectManager stick tick ─────────────────────────────────────────────────
    // Called every 16ms from the stick loop when InjectManager.isAvailable is true.
    // Mirrors the latch/coast/dead-zone logic from masterTick but drives
    // InjectManager.batchUpdate() instead of GestureDescription strokes.
    // Always uses continuous movement (V13-style): the pointer stays down and
    // ACTION_MOVE events shift its position each tick.
    private fun tickInjectSticks() {
        val updates   = mutableMapOf<Int, Pair<Float, Float>>()
        val toRelease = mutableListOf<Pair<String, StickState>>()
        val nowMs     = System.currentTimeMillis()

        for ((label, state) in activeSticks.toMap()) {
            val (rawX, rawY) = axisValues[label] ?: (0f to 0f)
            val (sx, sy)     = radialDeadZone(rawX, rawY, state.drag.deadZone)
            val mag          = sqrt(sx * sx + sy * sy)
            val stickTuning  = ButtonTuningStore.getStick(label)
            val latchMs      = stickTuning.latchMs
            val coastMs      = stickTuning.coastMs

            // ── Latch / Coast (same logic as masterTick) ──────────────────
            if (mag >= 0.01f) {
                state.lastSx = sx; state.lastSy = sy
                state.latchStartMs = 0L
                state.isCoasting = false; state.coastUntilMs = 0L
            } else if (state.latchStartMs == 0L) {
                state.latchStartMs = nowMs
            }
            val latchElapsed = if (state.latchStartMs > 0L) nowMs - state.latchStartMs else 0L
            val withinLatch  = mag < 0.01f && latchElapsed < latchMs
            if (mag < 0.01f && latchElapsed >= latchMs && !state.isCoasting &&
                coastMs > 0L && (state.lastSx != 0f || state.lastSy != 0f)) {
                state.isCoasting   = true
                state.coastUntilMs = nowMs + coastMs
            }
            val coastActive   = state.isCoasting && nowMs < state.coastUntilMs
            val activeSx      = if (mag >= 0.01f) sx else state.lastSx
            val activeSy      = if (mag >= 0.01f) sy else state.lastSy
            val activeMag     = if (mag >= 0.01f) mag
                                else sqrt(activeSx * activeSx + activeSy * activeSy)
            val shouldRelease = mag < 0.01f && !withinLatch && !coastActive
            // ─────────────────────────────────────────────────────────────

            if (shouldRelease) {
                val relX = if (state.lookMode) state.drag.centerX else state.currentX
                val relY = if (state.lookMode) state.drag.centerY else state.currentY
                toRelease.add(label to state.copy(currentX = relX, currentY = relY))
                OverlayManager.instance?.updateStickDebug(
                    label, state.drag.centerX, state.drag.centerY, rawX, rawY, false
                )
            } else {
                val dirX      = if (activeMag > 0f) activeSx / activeMag else 0f
                val dirY      = if (activeMag > 0f) activeSy / activeMag else 0f
                val sweepStep = activeMag * state.drag.radius / 8f * stickTuning.sensitivityPct
                val rawNewX   = state.currentX + dirX * sweepStep
                val rawNewY   = state.currentY + dirY * sweepStep
                val dxC = rawNewX - state.drag.centerX
                val dyC = rawNewY - state.drag.centerY
                val newX: Float; val newY: Float
                if (sqrt(dxC * dxC + dyC * dyC) > state.drag.radius * 0.85f) {
                    if (state.lookMode) {
                        newX = state.drag.centerX + dirX * state.drag.radius * 0.1f
                        newY = state.drag.centerY + dirY * state.drag.radius * 0.1f
                    } else {
                        val clampX = state.drag.centerX + dirX * state.drag.radius * 0.84f
                        val clampY = state.drag.centerY + dirY * state.drag.radius * 0.84f
                        val near   = abs(state.currentX - clampX) < 0.3f &&
                                     abs(state.currentY - clampY) < 0.3f
                        newX = if (near) clampX + 0.5f else clampX
                        newY = if (near) clampY + 0.5f else clampY
                    }
                } else {
                    newX = rawNewX; newY = rawNewY
                }
                updates[state.pointerId] = newX to newY
                state.currentX = newX; state.currentY = newY
                OverlayManager.instance?.updateStickDebug(label, newX, newY, activeSx, activeSy, true)
            }
        }

        // Release before move so released pointers are excluded from the MOVE event
        for ((label, finalState) in toRelease) {
            InjectManager.pointerUp(finalState.pointerId, finalState.currentX, finalState.currentY)
            freePointer(finalState.pointerId)
            activeSticks.remove(label)
        }
        if (updates.isNotEmpty()) {
            InjectManager.batchUpdate(updates)
        }
    }

    // ─── Public fields ────────────────────────────────────────────────────────

    var foregroundPackage: String = ""
        private set

    var lastGamePackage: String = ""
        private set

    var padMapUiVisible: Boolean = false

    // ─── Service lifecycle ────────────────────────────────────────────────────

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val device = android.view.InputDevice.getDevice(deviceId) ?: return
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
        if (OverlayManager.instance == null) {
            OverlayManager.instance = OverlayManager(this)
        }
        OverlayManager.instance?.startService()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        // Stop the inject stick loop and release any lingering InjectManager pointers
        mainHandler.removeCallbacks(stickRunnable)
        stickLoopRunning = false
        InjectManager.releaseAll()
        activeSticks.clear(); activeHolds.clear()
        freePointerIds.clear(); freePointerIds.addAll(0..9)
        channelStrokes.clear(); pendingTaps.clear()
        tapRepeatJobs.clear()
        holdRepeatJobs.clear()
        masterRunning = false
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

    // ─── Playback API (called from HomeScreen / GamepadPresetScreen) ──────────

    fun handlePlaybackDown(keyCode: Int) {
        val layout = DataStore.activeLayout ?: return
        val preset = DataStore.data.value.controllerPresets.find { it.id == layout.controllerPresetId }
        val label = preset?.buttons?.get(keyCode) ?: keyCodeLabel(keyCode)
        val entries = layout.mappings.filter { it.inputName == label }
        if (entries.isEmpty()) return
        val nonTurbo = entries.filter { !it.turbo }
        val tuning = nonTurbo.firstOrNull()?.let { ButtonTuningStore.get(it.zoneId) } ?: return
        when (tuning.mode) {
            ButtonMode.HOLD   -> startHold(label, nonTurbo)
            ButtonMode.TAP    -> queueTuned(nonTurbo)
            ButtonMode.REPEAT -> {
                tapRepeatJobs[label]?.cancel()
                tapRepeatJobs[label] = scope.launch {
                    while (isActive) {
                        val captured = nonTurbo.toList()
                        val interval = captured.firstOrNull()?.let { ButtonTuningStore.get(it.zoneId).repeatIntervalMs } ?: TURBO_INTERVAL_MS
                        mainHandler.post { queueTuned(captured) }
                        delay(interval)
                    }
                }
            }
        }
        val turboEntries = entries.filter { it.turbo }
        if (turboEntries.isNotEmpty()) {
            turboJobs[label]?.cancel()
            turboJobs[label] = scope.launch {
                while (isActive) {
                    val captured = turboEntries.toList()
                    mainHandler.post { queueStaggered(captured) }
                    delay(TURBO_INTERVAL_MS)
                }
            }
        }
    }

    fun handlePlaybackUp(keyCode: Int) {
        val layout = DataStore.activeLayout ?: return
        val preset = DataStore.data.value.controllerPresets.find { it.id == layout.controllerPresetId }
        val label = preset?.buttons?.get(keyCode) ?: keyCodeLabel(keyCode)
        val mode = layout.mappings.filter { it.inputName == label && !it.turbo }
            .firstOrNull()?.let { ButtonTuningStore.get(it.zoneId).mode } ?: ButtonMode.TAP
        when (mode) {
            ButtonMode.HOLD   -> releaseHold(label)
            ButtonMode.TAP    -> {}
            ButtonMode.REPEAT -> { tapRepeatJobs[label]?.cancel(); tapRepeatJobs.remove(label) }
        }
        turboJobs[label]?.cancel()
        turboJobs.remove(label)
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
            val cur = axisValues[stickLabel] ?: Pair(0f, 0f)
            axisValues[stickLabel] = if (isX) cur.copy(first = value) else cur.copy(second = value)
        }

        for ((stickLabel, pair) in axisValues) {
            val (rawX, rawY) = pair
            val entries = layout.mappings.filter { it.inputName == stickLabel }
            val deadZone = (entries.firstOrNull()?.action as? TouchAction.Drag)?.deadZone ?: DEAD_ZONE
            val (sx, sy) = radialDeadZone(rawX, rawY, deadZone)
            val mag = sqrt(sx * sx + sy * sy)

            if (mag < 0.01f) {
                activeSticks[stickLabel]?.active = false
            } else if (activeSticks[stickLabel]?.active != true) {
                entries.forEach { entry ->
                    val drag = entry.action as? TouchAction.Drag ?: return@forEach
                    startStickChain(stickLabel, drag, sx, sy)
                }
            }
        }
    }

    // ─── Accessibility events ─────────────────────────────────────────────────

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
                    } catch (e: Exception) { pkg }
                    val id = java.util.UUID.randomUUID().toString()
                    DataStore.update { it.copy(
                        gameLayouts = it.gameLayouts + GameLayout(id = id, name = appName, packageName = pkg, controllerPresetId = it.activePresetId),
                        activeLayoutId = id
                    )}
                    updateInputInterception()
                    OverlayManager.instance?.showToast("Layout created: $appName")
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (OverlayManager.instance?.state == OverlayManager.State.CONFIG) return
                val hasActiveSystemWindow = windows.any {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                    it.isFocused
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
        } catch (e: Exception) { false }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val overlay = OverlayManager.instance

        if (event.action == KeyEvent.ACTION_DOWN &&
            overlay?.pendingZoneId != null &&
            overlay.state == OverlayManager.State.CONFIG) {
            val preset = overlay.editingLayout?.let { l ->
                DataStore.data.value.controllerPresets.find { it.id == l.controllerPresetId }
            }
            val label = preset?.buttons?.get(event.keyCode) ?: keyCodeLabel(event.keyCode)
            overlay.assignPendingZone(label, false)
            return true
        }

        if (overlay?.state == OverlayManager.State.CONFIG) return true

        if (padMapUiVisible) {
            ControllerEventBus.emitKey(event)
            return false
        }

        val layout = DataStore.activeLayout ?: return false
        val preset = DataStore.data.value.controllerPresets
            .find { it.id == layout.controllerPresetId }
        val label = preset?.buttons?.get(event.keyCode) ?: keyCodeLabel(event.keyCode)
        val entries = layout.mappings.filter { it.inputName == label }
        if (entries.isEmpty()) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                val nonTurbo = entries.filter { !it.turbo }
                val tuning = nonTurbo.firstOrNull()?.let { ButtonTuningStore.get(it.zoneId) } ?: return true
                when (tuning.mode) {
                    ButtonMode.HOLD   -> startHold(label, nonTurbo)
                    ButtonMode.TAP    -> queueTuned(nonTurbo)
                    ButtonMode.REPEAT -> {
                        tapRepeatJobs[label]?.cancel()
                        tapRepeatJobs[label] = scope.launch {
                            while (isActive) {
                                val captured = nonTurbo.toList()
                                val interval = captured.firstOrNull()?.let { ButtonTuningStore.get(it.zoneId).repeatIntervalMs } ?: TURBO_INTERVAL_MS
                                mainHandler.post { queueTuned(captured) }
                                delay(interval)
                            }
                        }
                    }
                }
                val turboEntries = entries.filter { it.turbo }
                if (turboEntries.isNotEmpty()) {
                    turboJobs[label]?.cancel()
                    turboJobs[label] = scope.launch {
                        while (isActive) {
                            val captured = turboEntries.toList()
                            mainHandler.post { queueStaggered(captured) }
                            delay(TURBO_INTERVAL_MS)
                        }
                    }
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                val mode = entries.filter { !it.turbo }
                    .firstOrNull()?.let { ButtonTuningStore.get(it.zoneId).mode } ?: ButtonMode.TAP
                when (mode) {
                    ButtonMode.HOLD   -> releaseHold(label)
                    ButtonMode.TAP    -> {}
                    ButtonMode.REPEAT -> { tapRepeatJobs[label]?.cancel(); tapRepeatJobs.remove(label) }
                }
                turboJobs[label]?.cancel()
                turboJobs.remove(label)
                return true
            }
            else -> return false
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onMotionEvent(event: MotionEvent) {
        val isJoystick = event.source and InputDevice.SOURCE_JOYSTICK != 0
        val isGamepad  = event.source and InputDevice.SOURCE_GAMEPAD  != 0
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
            if (abs(hatX) > 0.5f || abs(hatY) > 0.5f) {
                val label = when {
                    hatX < -0.5f -> "D-Left"
                    hatX > 0.5f  -> "D-Right"
                    hatY < -0.5f -> "D-Up"
                    else         -> "D-Down"
                }
                overlay.assignPendingZone(label, false)
                return
            }
            val preset = overlay.editingLayout?.let { l ->
                DataStore.data.value.controllerPresets.find { it.id == l.controllerPresetId }
            }
            for ((axisCode, axisLabel) in (preset?.axes ?: emptyMap())) {
                if (abs(event.getAxisValue(axisCode)) > 0.5f) {
                    val stickLabel = axisLabel.removeSuffix(" X").removeSuffix(" Y")
                    overlay.assignPendingZone(stickLabel, true)
                    return
                }
            }
            return
        }

        if (overlay?.state == OverlayManager.State.CONFIG) return

        handlePlaybackMotion(event)
    }

    // ─── Hold chain ───────────────────────────────────────────────────────────

    private fun startHold(label: String, entries: List<MappingEntry>) {
        if (entries.isEmpty()) return
        if (InjectManager.isAvailable) {
            // Release any existing hold for this label first
            activeHolds[label]?.let { old ->
                old.pointerIds.forEachIndexed { i, pid ->
                    val (x, y) = tapXY(old.entries.getOrElse(i) { old.entries.last() })
                    InjectManager.pointerUp(pid, x, y)
                    freePointer(pid)
                }
            }
            val pids = entries.map { allocPointer() }
            activeHolds[label] = HoldState(entries, pointerIds = pids)
            entries.forEachIndexed { i, entry ->
                val (x, y) = tapXY(entry)
                val pid = pids[i]
                scope.launch {
                    delay(i * 10L)
                    mainHandler.post { InjectManager.pointerDown(pid, x, y) }
                }
            }
            return
        }
        val apiLevel = entries.firstOrNull()?.let { ButtonTuningStore.get(it.zoneId).androidApiLevel }
            ?: AndroidApiLevel.V13
        when (apiLevel) {
            AndroidApiLevel.V13 -> {
                activeHolds[label]?.let { old ->
                    old.active = false
                    old.entries.forEachIndexed { i, _ -> channelStrokes.remove("hold:$label:$i") }
                }
                activeHolds[label] = HoldState(entries)
                ensureMasterRunning()
            }
            AndroidApiLevel.V11, AndroidApiLevel.V12 -> {
                holdRepeatJobs[label]?.cancel()
                holdRepeatJobs[label] = scope.launch {
                    while (isActive) {
                        val captured = entries.toList()
                        val t = captured.firstOrNull()?.let { ButtonTuningStore.get(it.zoneId) }
                        mainHandler.post { queueTuned(captured) }
                        delay((t?.tapDurationMs ?: 10L) + (t?.holdReleaseDurationMs ?: 16L))
                    }
                }
            }
        }
    }

    private fun releaseHold(label: String) {
        if (InjectManager.isAvailable) {
            holdRepeatJobs[label]?.cancel()
            holdRepeatJobs.remove(label)
            val hold = activeHolds.remove(label) ?: return
            hold.pointerIds.forEachIndexed { i, pid ->
                val (x, y) = tapXY(hold.entries.getOrElse(i) { hold.entries.last() })
                InjectManager.pointerUp(pid, x, y)
                freePointer(pid)
            }
            return
        }
        holdRepeatJobs[label]?.cancel()
        holdRepeatJobs.remove(label)
        activeHolds[label]?.active = false
    }

    // ─── Stick chain ──────────────────────────────────────────────────────────

    private fun startStickChain(label: String, drag: TouchAction.Drag, sx: Float, sy: Float) {
        val existing = activeSticks[label]
        if (existing != null) {
            existing.active = true
            if (InjectManager.isAvailable) ensureStickLoopRunning() else ensureMasterRunning()
            return
        }
        if (InjectManager.isAvailable) {
            val pid = allocPointer()
            activeSticks[label] = StickState(
                drag, drag.centerX, drag.centerY,
                lookMode = drag.lookMode, pointerId = pid
            )
            InjectManager.pointerDown(pid, drag.centerX, drag.centerY)
            ensureStickLoopRunning()
        } else {
            activeSticks[label] = StickState(drag, drag.centerX, drag.centerY, lookMode = drag.lookMode)
            ensureMasterRunning()
        }
    }

    // ─── Tap queue ────────────────────────────────────────────────────────────

    private fun queueStaggered(entries: List<MappingEntry>) {
        if (entries.isEmpty()) return
        if (InjectManager.isAvailable) {
            entries.forEachIndexed { i, entry ->
                val (x, y) = tapXY(entry)
                val pid = allocPointer()
                scope.launch {
                    delay(i * 10L)
                    try {
                        mainHandler.post { InjectManager.pointerDown(pid, x, y) }
                        delay(HOLD_SEGMENT_MS)
                        mainHandler.post { InjectManager.pointerUp(pid, x, y); freePointer(pid) }
                    } catch (_: Throwable) { freePointer(pid) }
                }
            }
        } else {
            entries.forEachIndexed { i, entry ->
                val (x, y) = tapXY(entry)
                pendingTaps.add(
                    GestureDescription.StrokeDescription(tapPath(x, y), i * 10L, HOLD_SEGMENT_MS, false)
                )
            }
            ensureMasterRunning()
        }
    }

    // ─── Gesture helpers ──────────────────────────────────────────────────────

    private fun holdPath(x: Float, y: Float, alt: Boolean, sizePx: Float = 1f): Path = Path().apply {
        if (alt) { moveTo(x + sizePx, y); lineTo(x, y) }
        else     { moveTo(x, y);          lineTo(x + sizePx, y) }
    }

    private fun tapPath(x: Float, y: Float): Path = Path().apply {
        moveTo(x, y); lineTo(x + 1f, y)
    }

    private fun tapXY(entry: MappingEntry): Pair<Float, Float> = when (val a = entry.action) {
        is TouchAction.Tap  -> a.x to a.y
        is TouchAction.Drag -> a.centerX to a.centerY
    }

    private fun radialDeadZone(rawX: Float, rawY: Float, deadZone: Float = DEAD_ZONE): Pair<Float, Float> {
        val mag = sqrt(rawX * rawX + rawY * rawY)
        if (mag < deadZone) return 0f to 0f
        val scale = ((mag - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
        return (rawX / mag * scale) to (rawY / mag * scale)
    }

    private fun curve(v: Float): Float = v * abs(v)

    private fun queueTuned(entries: List<MappingEntry>) {
        if (entries.isEmpty()) return
        if (InjectManager.isAvailable) {
            entries.forEachIndexed { i, entry ->
                val t = ButtonTuningStore.get(entry.zoneId)
                val (x, y) = tapXY(entry)
                val pid = allocPointer()
                scope.launch {
                    delay(i * 10L)
                    try {
                        mainHandler.post { InjectManager.pointerDown(pid, x, y) }
                        delay(t.tapDurationMs)
                        mainHandler.post { InjectManager.pointerUp(pid, x, y); freePointer(pid) }
                    } catch (_: Throwable) { freePointer(pid) }
                }
            }
        } else {
            entries.forEachIndexed { i, entry ->
                val t = ButtonTuningStore.get(entry.zoneId)
                val (x, y) = tapXY(entry)
                pendingTaps.add(
                    GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y); lineTo(x + t.tapSizePx, y) },
                        i * 10L, t.tapDurationMs, false
                    )
                )
            }
            ensureMasterRunning()
        }
    }

    // ─── Hat / D-pad ──────────────────────────────────────────────────────────

    private fun processHatAxis(event: MotionEvent, layout: GameLayout, axisCode: Int, negLabel: String, posLabel: String) {
        val value = event.getAxisValue(axisCode)
        val pressedLabel = when {
            value < -0.5f -> negLabel
            value > 0.5f  -> posLabel
            else          -> null
        }
        for (label in listOf(negLabel, posLabel)) {
            val wasPressed = hatState[label] == true
            val nowPressed = label == pressedLabel
            if (nowPressed && !wasPressed) {
                queueStaggered(layout.mappings.filter { it.inputName == label })
            }
            hatState[label] = nowPressed
        }
    }

    // ─── Key/axis label maps ──────────────────────────────────────────────────

    private fun keyCodeLabel(code: Int) = when (code) {
        KeyEvent.KEYCODE_BUTTON_A      -> "A"
        KeyEvent.KEYCODE_BUTTON_B      -> "B"
        KeyEvent.KEYCODE_BUTTON_X      -> "X"
        KeyEvent.KEYCODE_BUTTON_Y      -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1     -> "LB"
        KeyEvent.KEYCODE_BUTTON_R1     -> "RB"
        KeyEvent.KEYCODE_BUTTON_L2     -> "LT"
        KeyEvent.KEYCODE_BUTTON_R2     -> "RT"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_START  -> "Start"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        KeyEvent.KEYCODE_DPAD_UP       -> "D-Up"
        KeyEvent.KEYCODE_DPAD_DOWN     -> "D-Down"
        KeyEvent.KEYCODE_DPAD_LEFT     -> "D-Left"
        KeyEvent.KEYCODE_DPAD_RIGHT    -> "D-Right"
        else -> "Btn$code"
    }
}
