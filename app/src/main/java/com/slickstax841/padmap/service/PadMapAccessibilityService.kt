package com.slickstax841.padmap.service

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
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
import com.slickstax841.padmap.data.resolvedBinds
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
        private const val STICK_RELEASE_TICKS = 8
        private const val LOOK_RELEASE_TICKS = 20
        private val DEFAULT_AXES = mapOf(
            MotionEvent.AXIS_X to "L-Stick X",
            MotionEvent.AXIS_Y to "L-Stick Y",
            MotionEvent.AXIS_Z to "R-Stick X",
            MotionEvent.AXIS_RZ to "R-Stick Y"
        )
    }

    private data class StickState(
        val drag: TouchAction.Drag,
        var currentX: Float,
        var currentY: Float,
        val lookMode: Boolean,
        val pointerId: Int,
        var lastTickMs: Long = 0L,
        var filtX: Float = 0f,
        var filtY: Float = 0f
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
    private val stickDeadTicks = mutableMapOf<String, Int>()
    private var inFlightTaps = 0
    private var playbackGen = 0
    private val freePointerIds = ArrayDeque<Int>().apply { addAll(0..9) }
    val activeHoldCount: Int get() = activeHolds.size
    val activeStickCount: Int get() = activeSticks.size
    val isPlaybackBusy: Boolean
        get() = activeHolds.isNotEmpty() || activeSticks.isNotEmpty() ||
            inFlightTaps > 0 || turboJobs.isNotEmpty() || tapRepeatJobs.isNotEmpty()

    private var stickLoopRunning = false
    private var lastSidecarWarnMs = 0L

    var foregroundPackage: String = ""
        private set
    var lastGamePackage: String = ""
        private set
    var padMapUiVisible: Boolean = false
    private var playingPackage: String = ""
    var activeLayer: Int = 1
        private set

    private val stickRunnable = object : Runnable {
        override fun run() {
            tickSticks()
            if (activeSticks.isNotEmpty()) mainHandler.postDelayed(this, STICK_TICK_MS)
            else stickLoopRunning = false
        }
    }

    private fun allocPointer(): Int? = freePointerIds.removeFirstOrNull()
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
        val now = System.currentTimeMillis()
        if (now - lastSidecarWarnMs > 2500L) {
            lastSidecarWarnMs = now
            val why = SidecarClient.lastError.ifBlank { "not paired" }
            PlaybackDebug.log("sidecar off ($why)")
            OverlayManager.instance?.showToast("Injector off ($why) — Settings → OPEN DEVELOPER")
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
        startKeepAlive()
        SidecarClient.ping()
    }

    private fun startKeepAlive() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel("padmap_keep", "PadMap", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
        val n = Notification.Builder(this, "padmap_keep")
            .setContentTitle("PadMap")
            .setContentText("Gamepad mapping is on")
            .setSmallIcon(com.slickstax841.padmap.R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                @Suppress("DEPRECATION")
                startForeground(41, n)
            }
        } catch (_: Throwable) {
            runCatching { startForeground(41, n) }
        }
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
        if (pkg.isBlank()) return
        foregroundPackage = pkg
        lastGamePackage = pkg
        if (playingPackage != pkg) {
            releaseAllPlayback()
            playingPackage = pkg
            PlaybackDebug.log("enter game $pkg")
        }
        updateInputInterception()
    }

    fun disableAndStop() {
        OverlayManager.instance?.detach()
        OverlayManager.instance = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { disableSelf() }
    }

    fun releaseAllPlayback() {
        tapRepeatJobs.values.forEach { it.cancel() }
        tapRepeatJobs.clear()
        turboJobs.values.forEach { it.cancel() }
        turboJobs.clear()
        activeHolds.keys.toList().forEach { releaseHold(it) }
        for (label in activeSticks.keys.toList()) {
            val state = activeSticks.remove(label) ?: continue
            SidecarClient.pointerUp(state.pointerId)
            freePointer(state.pointerId)
        }
        stickDeadTicks.clear()
        hatState.clear()
        SidecarClient.releaseAll()
        inFlightTaps = 0
        playbackGen++
        OverlayManager.instance?.setIconPassThrough(false)
        PlaybackDebug.log("playback released")
    }

    private fun syncIconPassThrough() {
        OverlayManager.instance?.setIconPassThrough(isPlaybackBusy)
    }

    private fun canInject(): Boolean {
        if (playingPackage.isEmpty()) return false
        if (OverlayManager.instance?.state == OverlayManager.State.CONFIG) return false
        return true
    }

    private fun layerOf(entry: MappingEntry) = if (entry.layer in 1..6) entry.layer else 1

    private fun layerMappings(layout: GameLayout) =
        layout.mappings.filter { layerOf(it) == activeLayer }

    private fun switchLayer(n: Int) {
        val next = n.coerceIn(1, 6)
        if (next == activeLayer) return
        activeHolds.keys.toList().forEach { releaseHold(it) }
        for (label in activeSticks.keys.toList()) {
            val state = activeSticks.remove(label) ?: continue
            SidecarClient.pointerUp(state.pointerId)
            freePointer(state.pointerId)
        }
        stickDeadTicks.clear()
        activeLayer = next
        PlaybackDebug.log("layer $activeLayer")
        updateInputInterception()
        syncIconPassThrough()
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onInterrupt() {}

    fun handlePlaybackDown(keyCode: Int) {
        onButtonDown(labelFor(keyCode) ?: return)
    }

    fun handlePlaybackUp(keyCode: Int) {
        onButtonUp(labelFor(keyCode) ?: return)
    }

    fun handlePlaybackMotion(event: MotionEvent) {
        if (playingPackage.isEmpty()) return
        val layout = DataStore.activeLayout
        if (layout == null) {
            PlaybackDebug.logMotion("motion no layout")
            return
        }
        val preset = DataStore.data.value.controllerPresets.find { it.id == layout.controllerPresetId }
            ?: DataStore.activePreset
        val axes = preset?.axes?.takeIf { it.isNotEmpty() } ?: DEFAULT_AXES

        processHatAxis(event, layout, MotionEvent.AXIS_HAT_X, "D-Left", "D-Right")
        processHatAxis(event, layout, MotionEvent.AXIS_HAT_Y, "D-Up", "D-Down")

        for ((axisCode, axisLabelStr) in axes) {
            if (axisCode == MotionEvent.AXIS_HAT_X || axisCode == MotionEvent.AXIS_HAT_Y) continue
            val stickLabel = axisLabelStr.removeSuffix(" X").removeSuffix(" Y")
            if (layerMappings(layout).none { it.inputName == stickLabel }) continue
            val value = event.getAxisValue(axisCode)
            val isX = axisLabelStr.endsWith(" X")
            val cur = axisValues[stickLabel] ?: (0f to 0f)
            axisValues[stickLabel] = if (isX) cur.copy(first = value) else cur.copy(second = value)
        }

        for ((stickLabel, pair) in axisValues) {
            val (rawX, rawY) = pair
            val entries = layerMappings(layout).filter { it.inputName == stickLabel }
            val deadZone = (entries.firstOrNull()?.action as? TouchAction.Drag)?.deadZone ?: DEAD_ZONE
            val (sx, sy) = radialDeadZone(rawX, rawY, deadZone)
            val mag = sqrt(sx * sx + sy * sy)
            if (mag < 0.01f) {
                // release handled in tick
            } else if (activeSticks[stickLabel] == null) {
                if (entries.none { it.action is TouchAction.Drag })
                    PlaybackDebug.logMotion("stick $stickLabel not mapped as drag")
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
                if (pkg in OverlayManager.TRANSIENT_PACKAGES) return
                if (pkg == packageName) return
                foregroundPackage = pkg
                val isGame = com.slickstax841.padmap.data.GameScanner.isInstalledGame(this, pkg)
                if (isGame) {
                    lastGamePackage = pkg
                    if (playingPackage != pkg) {
                        releaseAllPlayback()
                        playingPackage = pkg
                        activeLayer = 1
                        PlaybackDebug.log("enter game $pkg")
                    }
                    OverlayManager.instance?.repositionForGame(pkg)
                    val layoutMatch = DataStore.data.value.gameLayouts.find { it.packageName == pkg }
                    if (layoutMatch != null) {
                        if (layoutMatch.archived || DataStore.data.value.activeLayoutId != layoutMatch.id) {
                            DataStore.update { data ->
                                data.copy(
                                    gameLayouts = if (layoutMatch.archived)
                                        data.gameLayouts.map { if (it.id == layoutMatch.id) it.copy(archived = false) else it }
                                    else data.gameLayouts,
                                    activeLayoutId = layoutMatch.id
                                )
                            }
                        }
                    } else {
                        val current = DataStore.activeLayout
                        if (current != null && current.mappings.isNotEmpty()) {
                            if (current.packageName.isBlank()) {
                                DataStore.update { data ->
                                    data.copy(gameLayouts = data.gameLayouts.map {
                                        if (it.id == current.id) it.copy(packageName = pkg) else it
                                    })
                                }
                            }
                        } else {
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
                            OverlayManager.instance?.showToast("Layout created: $appName")
                        }
                    }
                } else {
                    if (playingPackage.isNotEmpty()) {
                        releaseAllPlayback()
                        playingPackage = ""
                        PlaybackDebug.log("leave game for $pkg")
                    }
                    OverlayManager.instance?.repositionForGame(pkg)
                }
                updateInputInterception()
            }
        }
    }

    @SuppressLint("NewApi")
    internal fun updateInputInterception() {
        val info = serviceInfo ?: return
        val flagKey = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val configOpen = OverlayManager.instance?.state == OverlayManager.State.CONFIG
        val layout = DataStore.activeLayout
        val mapped = layout?.mappings.orEmpty().filter { it.inputName.isNotBlank() }
        val binds = layout?.resolvedBinds().orEmpty()
        val wantKeys = configOpen || (playingPackage.isNotBlank() && (
            mapped.any { it.action is TouchAction.Tap } ||
                binds.any { it.activateName.isNotBlank() || it.deactivateName.isNotBlank() }
            ))
        val wantStick = configOpen || (playingPackage.isNotBlank() &&
            mapped.any { it.action is TouchAction.Drag && layerOf(it) == activeLayer })
        val newFlags = if (wantKeys) info.flags or flagKey else info.flags and flagKey.inv()
        info.flags = newFlags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Requested motion sources are NOT delivered to the game. Only take
            // sticks when a stick zone is overriding; otherwise built-in look works.
            info.motionEventSources = if (wantStick)
                InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD
            else 0
        }
        // Always write back. ColorOS drops key/motion delivery on game start;
        // assigning serviceInfo again is what overlay ✕ restores (BUG-008).
        serviceInfo = info
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
        if (overlay?.state == OverlayManager.State.CONFIG) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                PlaybackDebug.log("key swallowed CONFIG")
            return true
        }
        if (padMapUiVisible) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                PlaybackDebug.log("key skipped PadMap UI")
            ControllerEventBus.emitKey(event)
            return false
        }
        if (playingPackage.isEmpty()) return false
        val label = labelFor(event.keyCode)
        if (label == null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                PlaybackDebug.log("key ${event.keyCode} no label")
            return false
        }
        val layout = DataStore.activeLayout
        if (layout == null) {
            if (event.action == KeyEvent.ACTION_DOWN) PlaybackDebug.log("key $label no layout")
            return false
        }
        for (bind in layout.resolvedBinds()) {
            val isAct = bind.activateName == label
            val isDeact = bind.deactivateName == label
            if (!isAct && !isDeact) continue
            val mappedHere = layerMappings(layout).any { it.inputName == label }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount > 0) return mappedHere
                    if (mappedHere && sidecarReady()) onButtonDown(label)
                    if (isAct) switchLayer(bind.index) else switchLayer(1)
                    return mappedHere
                }
                KeyEvent.ACTION_UP -> {
                    if (mappedHere) onButtonUp(label)
                    return mappedHere
                }
            }
        }
        if (layerMappings(layout).none { it.inputName == label }) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                PlaybackDebug.log("key $label not mapped")
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                if (sidecarReady()) onButtonDown(label)
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
            PlaybackDebug.logMotion("motion skipped PadMap UI")
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
        if (overlay?.state == OverlayManager.State.CONFIG) {
            PlaybackDebug.logMotion("motion swallowed CONFIG")
            return
        }
        handlePlaybackMotion(event)
    }

    private fun onButtonDown(label: String) {
        val layout = DataStore.activeLayout ?: return
        val entries = layerMappings(layout).filter { it.inputName == label }
        if (entries.isEmpty()) {
            PlaybackDebug.log("btn $label no zones")
            return
        }
        if (!sidecarReady()) return
        PlaybackDebug.log("btn down $label")
        val nonTurbo = entries.filter { !it.turbo }
        val turboEntries = entries.filter { it.turbo }
        val mode = nonTurbo.firstOrNull()?.let { ButtonTuningStore.get(it.zoneId).mode } ?: ButtonMode.HOLD
        if (nonTurbo.isNotEmpty()) {
            when (mode) {
                ButtonMode.HOLD -> {
                    if (activeHolds.containsKey(label)) {
                        PlaybackDebug.log("btn $label re-down")
                        releaseHold(label)
                    }
                    startHold(label, nonTurbo)
                }
                ButtonMode.TAP -> fireTaps(nonTurbo)
                ButtonMode.REPEAT -> {
                    if (tapRepeatJobs.containsKey(label)) return
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
                    syncIconPassThrough()
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
            syncIconPassThrough()
        }
    }

    private fun onButtonUp(label: String) {
        PlaybackDebug.log("btn up $label")
        val layout = DataStore.activeLayout ?: return
        val mode = layerMappings(layout).filter { it.inputName == label && !it.turbo }
            .firstOrNull()?.let { ButtonTuningStore.get(it.zoneId).mode } ?: ButtonMode.HOLD
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
        syncIconPassThrough()
    }

    private fun startHold(label: String, entries: List<MappingEntry>) {
        if (entries.isEmpty() || !sidecarReady()) return
        activeHolds.remove(label)?.let { old ->
            old.pointerIds.forEach { pid ->
                SidecarClient.pointerUp(pid)
                freePointer(pid)
            }
        }
        val pids = mutableListOf<Int>()
        for (entry in entries) {
            val pid = allocPointer()
            if (pid == null) {
                pids.forEach { freePointer(it) }
                PlaybackDebug.log("down $label no pid")
                return
            }
            pids.add(pid)
        }
        activeHolds[label] = HoldState(entries, pids)
        syncIconPassThrough()
        entries.forEachIndexed { i, entry ->
            val (x, y) = tapXY(entry)
            val ok = SidecarClient.pointerDown(pids[i], x, y)
            PlaybackDebug.log("down $label pid=${pids[i]} ${x.toInt()},${y.toInt()} ok=$ok ${SidecarClient.lastError}")
        }
    }

    private fun releaseHold(label: String) {
        val hold = activeHolds.remove(label) ?: return
        hold.pointerIds.forEach { pid ->
            SidecarClient.pointerUp(pid)
            freePointer(pid)
        }
        syncIconPassThrough()
    }

    private fun fireTaps(entries: List<MappingEntry>) {
        if (entries.isEmpty() || !sidecarReady() || !canInject()) return
        val gen = playbackGen
        entries.forEach { entry ->
            val t = ButtonTuningStore.get(entry.zoneId)
            val (x, y) = tapXY(entry)
            val pid = allocPointer() ?: run {
                PlaybackDebug.log("tap ${entry.inputName} no pid")
                return@forEach
            }
            inFlightTaps++
            syncIconPassThrough()
            val holdMs = t.tapDurationMs.coerceIn(16L, 80L)
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(up: Boolean) {
                if (!finished.compareAndSet(false, true)) return
                if (up) SidecarClient.pointerUp(pid)
                freePointer(pid)
                inFlightTaps = (inFlightTaps - 1).coerceAtLeast(0)
                syncIconPassThrough()
            }
            scope.launch {
                try {
                    mainHandler.post {
                        if (gen != playbackGen || !canInject()) {
                            finish(up = false)
                            return@post
                        }
                        SidecarClient.pointerDown(pid, x, y)
                    }
                    delay(holdMs)
                    mainHandler.post { finish(up = true) }
                } catch (_: Throwable) {
                    mainHandler.post { finish(up = false) }
                }
            }
        }
    }

    private fun startStick(label: String, drag: TouchAction.Drag) {
        if (activeSticks.containsKey(label) || !sidecarReady()) return
        val pid = allocPointer() ?: run {
            PlaybackDebug.log("stick $label no pid")
            return
        }
        activeSticks[label] = StickState(drag, drag.centerX, drag.centerY, drag.lookMode, pid)
        stickDeadTicks[label] = 0
        syncIconPassThrough()
        val ok = SidecarClient.pointerDown(pid, drag.centerX, drag.centerY)
        PlaybackDebug.log("stick $label down pid=$pid ${drag.centerX.toInt()},${drag.centerY.toInt()} ok=$ok ${SidecarClient.lastError}")
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
            val zoneId = DataStore.activeLayout?.let { layerMappings(it) }
                ?.firstOrNull { it.inputName == label }?.zoneId?.ifBlank { label } ?: label
            val tuning = ButtonTuningStore.getStick(zoneId)
            val scale = tuning.sensitivityPct
            val lookY = if (tuning.invertY) -sy else sy
            if (mag < 0.01f) {
                val n = (stickDeadTicks[label] ?: 0) + 1
                stickDeadTicks[label] = n
                val need = if (state.lookMode) LOOK_RELEASE_TICKS else STICK_RELEASE_TICKS
                if (n >= need) {
                    toRelease.add(label)
                    OverlayManager.instance?.updateStickDebug(
                        label, state.drag.centerX, state.drag.centerY, rawX, rawY, false
                    )
                }
                continue
            }
            stickDeadTicks[label] = 0
            if (state.lookMode) {
                state.filtX += (sx - state.filtX) * 0.55f
                state.filtY += (lookY - state.filtY) * 0.55f
                val now = android.os.SystemClock.uptimeMillis()
                val dt = if (state.lastTickMs == 0L) 0.016f
                    else ((now - state.lastTickMs).coerceIn(4L, 32L)) / 1000f
                state.lastTickMs = now
                val step = 700f * scale * dt
                var nx = state.currentX + state.filtX * step
                var ny = state.currentY + state.filtY * step
                val dm = resources.displayMetrics
                val margin = 24f
                val oob = nx < margin || ny < margin ||
                    nx > dm.widthPixels - margin || ny > dm.heightPixels - margin
                if (oob) {
                    val fm = sqrt(state.filtX * state.filtX + state.filtY * state.filtY)
                        .coerceAtLeast(0.01f)
                    SidecarClient.pointerUp(state.pointerId)
                    val ox = state.drag.centerX + state.filtX / fm * 16f
                    val oy = state.drag.centerY + state.filtY / fm * 16f
                    SidecarClient.pointerDown(state.pointerId, ox, oy)
                    nx = ox + state.filtX * step
                    ny = oy + state.filtY * step
                }
                state.currentX = nx
                state.currentY = ny
                updates[state.pointerId] = nx to ny
            } else {
                val nx = state.drag.centerX + sx * state.drag.radius * scale
                val ny = state.drag.centerY + lookY * state.drag.radius * scale
                state.currentX = nx
                state.currentY = ny
                updates[state.pointerId] = nx to ny
            }
            OverlayManager.instance?.updateStickDebug(label, state.currentX, state.currentY, sx, lookY, true)
        }
        for (label in toRelease) {
            val state = activeSticks.remove(label) ?: continue
            stickDeadTicks.remove(label)
            SidecarClient.pointerUp(state.pointerId)
            freePointer(state.pointerId)
            PlaybackDebug.log("stick $label up")
        }
        if (toRelease.isNotEmpty()) syncIconPassThrough()
        if (updates.isNotEmpty()) {
            SidecarClient.batchUpdateAsync(updates)
        }
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
            if (layerMappings(layout).none { it.inputName == label }) {
                hatState[label] = nowPressed
                continue
            }
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
