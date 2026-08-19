package com.slickstax841.padmap.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*
import com.slickstax841.padmap.R
import com.slickstax841.padmap.data.ButtonTuningStore
import com.slickstax841.padmap.data.DataStore
import com.slickstax841.padmap.data.GameLayout
import com.slickstax841.padmap.data.MappingEntry
import com.slickstax841.padmap.data.AppData
import com.slickstax841.padmap.data.ScreenSize
import com.slickstax841.padmap.data.TouchAction
import com.slickstax841.padmap.data.resolvedOverlay
import com.slickstax841.padmap.data.seedOverlayIfNeeded
import java.util.UUID
import kotlin.math.*

class OverlayManager(private val context: Context) {

    companion object {
        var instance: OverlayManager? = null

        // Packages that must never get a layout profile — Google Play Services and related
        // system overlays that flash into the foreground briefly during app loading.
        val BLOCKED_PACKAGES = setOf(
            "com.google.android.gms",
            "com.android.vending",
            "com.google.android.gsf",
            "com.google.android.gms.ui"
        )
        // Status bar / toasts / permission chips. Not a real app switch — do not
        // tear down the play catcher or playback (v66 stuck-repeat / dead stick).
        val TRANSIENT_PACKAGES = setOf(
            "com.android.systemui",
            "com.oplus.systemui",
            "com.coloros.systemui",
            "com.oppo.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        ) + BLOCKED_PACKAGES
        private val KNOWN_LAUNCHERS = setOf(
            "com.oppo.launcher",
            "com.heytap.launcher",
            "com.oplus.launcher",
            "com.android.launcher",
            "com.android.launcher3"
        )
    }

    enum class State { FLOATING, CONFIG }
    private enum class DragMode { NONE, MOVE, RESIZE }

    var state = State.FLOATING
        private set

    var pendingZoneId: String? = null
        private set

    // ─── Tuning box state (shared by button tuning and stick tuning) ─────────
    private var tuneZoneId = ""   // zone ID — tuning key; stable across button reassignment
    private var tuneDisplayLabel = "A"  // button name shown in the title bar only
    private var stickTuneZoneId = ""
    private var stickTuneDisplayLabel = ""
    private var isTuningStick = false
    private var tuningBoxView: View? = null
    private var tuningRepeatRunnable: Runnable? = null
    private var panelLeftBeforeTune = 0
    private var panelTopBeforeTune = 0
    private var panelHiddenForTune = false

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val dm = context.resources.displayMetrics

    // ─── Floating icon ────────────────────────────────────────────────────────

    private var iconView: View? = null
    private val iconParams = WindowManager.LayoutParams(
        dp(56), dp(56),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = -1 }

    // 1×1 focusable backup for pad keys if a11y drops them. Full-screen was
    // sitting over every injected coordinate on ColorOS.
    private var playCatcher: View? = null
    private val playParams = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ─── Config overlay ───────────────────────────────────────────────────────

    private var configRoot: FrameLayout? = null
    private var zoneLayer: FrameLayout? = null
    private var configPanel: View? = null
    private var adjustMode = false
    private var adjustLayer: View? = null
    private val configParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        0,
        PixelFormat.TRANSLUCENT
    )

    data class ZoneData(
        val id: String = UUID.randomUUID().toString(),
        var inputName: String = "",
        var cx: Float = 0f,
        var cy: Float = 0f,
        var innerRadius: Float = 32f,
        var outerRadius: Float = 120f,
        var isStick: Boolean = false,
        var turbo: Boolean = false,
        var deadZone: Float = 0.15f,
        // false = Move (position-based), true = Look/Camera (velocity/sweep-based)
        var lookMode: Boolean = false,
        var layer: Int = 1
    )

    private var editingLayer = 1

    private val editingZones = mutableListOf<ZoneData>()
    private var editingLayoutId = ""
    // Package name of the game currently open in config — set on enter, cleared on exit.
    // Exposed so the accessibility service can suppress spurious own-package a11y events
    // during the brief window between state becoming FLOATING and the overlay being removed.
    var configGamePackage: String = ""
        private set
    private val zoneViews = mutableMapOf<String, ZoneCircleView>()
    private val contextMenuViews = mutableListOf<View>()
    private val debugViews = mutableMapOf<String, DebugStickView>()
    private val debugParams = mutableMapOf<String, WindowManager.LayoutParams>()
    // Retained so showContextMenu() can re-request focus after adding clickable views
    private var keyCatcher: KeyCatcherView? = null
    private var debugLogView: TextView? = null
    private var debugBox: View? = null
    private var debugOpen = false
    private val debugRefresh = object : Runnable {
        override fun run() {
            if (!debugOpen) return
            debugLogView?.text = PlaybackDebug.snapshot()
            handler.postDelayed(this, 400)
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    val isIconVisible: Boolean get() = iconView != null

    // The layout currently open in the config overlay (may not yet be the active layout)
    val editingLayout: com.slickstax841.padmap.data.GameLayout?
        get() = DataStore.data.value.gameLayouts.find { it.id == editingLayoutId }

    fun startService() { handler.post { showFloatingIcon() } }

    // Re-show the floating icon from the PadMap app after it's been hidden or closed
    fun restartIcon() {
        handler.post {
            iconView?.visibility = View.VISIBLE  // restore if hidden by isSystemUi
            showFloatingIcon()                    // create if it doesn't exist yet
        }
    }

    fun hideIconOnAppBackground() {
        handler.post { iconView?.visibility = View.GONE }
    }

    fun resetIconToTopCenter() {
        handler.post { pinIconTopCenter(apply = true) }
    }

    fun applyStoredOverlayFit() {
        handler.post {
            applyOverlayFit(configParams)
            configRoot?.let { runCatching { wm.updateViewLayout(it, configParams) } }
        }
    }

    private fun applyOverlayFit(lp: WindowManager.LayoutParams, forcePixels: Boolean = false) {
        var data = DataStore.data.value
        val seeded = data.seedOverlayIfNeeded(context)
        if (seeded !== data) {
            DataStore.update { seeded }
            data = seeded
        }
        val r = data.resolvedOverlay(context)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        lp.width = r.w.coerceAtLeast(1)
        lp.height = r.h.coerceAtLeast(1)
        lp.x = r.x
        lp.y = r.y
    }

    private fun saveOverlayRect(data: AppData, x: Int, y: Int, w: Int, h: Int): AppData {
        val land = data.overlayMode == "landscape" ||
            (data.overlayMode == "auto" && ScreenSize.isLandscape(context))
        return if (land) data.copy(
            overlayX = x, overlayY = y, overlayW = w, overlayH = h,
            landX = x, landY = y, landW = w, landH = h
        ) else data.copy(
            overlayX = x, overlayY = y, overlayW = w, overlayH = h,
            portX = x, portY = y, portW = w, portH = h
        )
    }

    // Injected touches hit this window if it is touchable and overlaps a zone (BUG-009).
    fun setIconPassThrough(passThrough: Boolean) {
        val apply = Runnable {
            val view = iconView ?: return@Runnable
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                if (passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            if (iconParams.flags == flags) return@Runnable
            iconParams.flags = flags
            runCatching { wm.updateViewLayout(view, iconParams) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run()
        else handler.post(apply)
    }

    // Icon is a separate window. Never use overlay W/H/X/Y or a stale landscape width.
    // Do not add the status-bar inset: this window is already laid out below the bar,
    // so adding it again sits the icon too low on splash (BUG-008).
    private fun pinIconTopCenter(apply: Boolean) {
        iconParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        iconParams.x = 0
        iconParams.y = dp(4)
        if (apply) {
            iconView?.let { runCatching { wm.updateViewLayout(it, iconParams) } }
        }
    }

    private var lastInjectMs = 0L
    private var lastInjectX = 0f
    private var lastInjectY = 0f
    private val endInjectPass = Runnable {
        if (PadMapAccessibilityService.instance?.isPlaybackBusy != true) {
            setIconPassThrough(false)
        }
    }

    fun noteInject(x: Float, y: Float) {
        lastInjectMs = android.os.SystemClock.uptimeMillis()
        lastInjectX = x
        lastInjectY = y
        setIconPassThrough(true)
        handler.removeCallbacks(endInjectPass)
        handler.postDelayed(endInjectPass, 160)
    }

    private fun isInjectTouch(rawX: Float, rawY: Float): Boolean {
        if (android.os.SystemClock.uptimeMillis() - lastInjectMs > 200) return false
        val dx = rawX - lastInjectX
        val dy = rawY - lastInjectY
        return dx * dx + dy * dy < 100f * 100f
    }

    private fun applyIconForGame(pkg: String) {
        if (state != State.FLOATING || iconView?.visibility != View.VISIBLE) return
        val layout = DataStore.data.value.gameLayouts.find { it.packageName == pkg && !it.archived }
        if (layout?.iconX != null && layout.iconY != null) {
            iconParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            iconParams.x = layout.iconX
            iconParams.y = layout.iconY
            iconView?.let { runCatching { wm.updateViewLayout(it, iconParams) } }
        } else {
            pinIconTopCenter(apply = true)
        }
    }

    private fun saveIconForCurrentGame() {
        val pkg = PadMapAccessibilityService.instance?.lastGamePackage?.ifBlank {
            PadMapAccessibilityService.instance?.foregroundPackage
        } ?: return
        if (pkg.isBlank()) return
        DataStore.update { data ->
            data.copy(gameLayouts = data.gameLayouts.map {
                if (it.packageName == pkg && !it.archived)
                    it.copy(iconX = iconParams.x, iconY = iconParams.y)
                else it
            })
        }
    }

    // PadMap Home is not a game — hide the overlay button.
    fun repositionForHome() {
        handler.post {
            iconView?.visibility = View.GONE
            hidePlayCatcher()
        }
    }

    // Move icon to top-center (game is foreground); re-assert config overlay focus if open.
    // Hides the icon when pkg is a system/Android screen (launcher, Settings, etc.).
    fun repositionForGame(pkg: String) {
        handler.post {
            if (state == State.CONFIG) {
                // Config overlay is open — re-assert it so the game window can't steal focus/touch
                configRoot?.let { runCatching { wm.updateViewLayout(it, configParams) } }
                keyCatcher?.requestFocus()
                return@post
            }
            if (pkg in TRANSIENT_PACKAGES) return@post
            if (iconView == null) showFloatingIcon()
            val view = iconView ?: return@post
            if (!com.slickstax841.padmap.data.GameScanner.isInstalledGame(context, pkg)) {
                view.visibility = View.GONE
                hidePlayCatcher()
                return@post
            }
            view.visibility = View.VISIBLE
            applyIconForGame(pkg)
            handler.postDelayed({ applyIconForGame(pkg) }, 400)
        }
    }

    // Returns true for the home launcher and any system-partition app (Settings, file managers, etc.)
    private fun isSystemUi(pkg: String): Boolean {
        if (isSystemLauncher(pkg)) return true
        return try {
            val flags = context.packageManager.getApplicationInfo(pkg, 0).flags
            flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        } catch (e: Exception) { false }
    }

    // Open config overlay directly from HomeScreen — uses last known foreground package
    fun openConfigDirect() { handler.post { enterConfigModeFromIcon() } }

    fun detach() { handler.post { hidePlayCatcher(); removeIcon(); removeConfig(); removeAllDebugViews() } }

    fun showToast(msg: String) {
        handler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    // Called from GameLayoutScreen "Configure Zones" button (manual path)
    fun startConfigOverlay(layoutId: String) {
        handler.post {
            loadLayout(layoutId)
            enterConfigMode()
        }
    }

    // Assign pending zone to a controller input. Called from the service.
    fun assignPendingZone(inputName: String, isStick: Boolean) {
        val id = pendingZoneId ?: return
        val zone = editingZones.find { it.id == id } ?: return

        // Sticks: only 1 zone allowed per stick label
        if (isStick) {
            val alreadyUsed = editingZones.any { it.id != id && it.layer == editingLayer && it.isStick && it.inputName == inputName }
            if (alreadyUsed) {
                showToast("$inputName is already mapped to a zone")
                editingZones.removeAll { it.id == id }
                pendingZoneId = null
                handler.post { rebuildZoneLayer() }
                return
            }
        }

        // If zone was previously a stick with a different label, remove its debug overlay.
        if (zone.isStick && zone.inputName.isNotBlank() && zone.inputName != inputName)
            hideDebugOverlay(zone.inputName)
        zone.inputName = inputName
        zone.isStick = isStick
        if (inputName == "LT" || inputName == "RT") ButtonTuningStore.initForTrigger(zone.id)
        pendingZoneId = null
        handler.post {
            rebuildZoneLayer()
        }
    }

    // ─── Floating icon ────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingIcon() {
        if (iconView != null) return
        // First-time position: centered horizontally, in the app title row
        if (iconParams.y < 0) pinIconTopCenter(apply = false)
        val view = ImageView(context).apply {
            setImageResource(R.drawable.padmap_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            alpha = 0.85f
        }
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var moved = false
        var ignoreInject = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isInjectTouch(e.rawX, e.rawY)) {
                        ignoreInject = true
                        return@setOnTouchListener true
                    }
                    ignoreInject = false
                    ix = iconParams.x; iy = iconParams.y; tx = e.rawX; ty = e.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ignoreInject) return@setOnTouchListener true
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) { moved = true; view.alpha = 1f }
                    iconParams.x = ix + dx; iconParams.y = iy + dy
                    iconView?.let { runCatching { wm.updateViewLayout(it, iconParams) } }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 0.85f
                    if (ignoreInject) {
                        ignoreInject = false
                        return@setOnTouchListener true
                    }
                    if (moved) saveIconForCurrentGame()
                    else if (PadMapAccessibilityService.instance?.isPlaybackBusy != true &&
                        !isInjectTouch(e.rawX, e.rawY)
                    ) {
                        enterConfigModeFromIcon()
                    }
                    true
                }
                else -> false
            }
        }
        iconView = view
        try {
            wm.addView(view, iconParams)
        } catch (e: Exception) {
            iconView = null
            showToast("Could not show overlay icon: ${e.message}")
        }
    }

    private fun removeIcon() { iconView?.let { runCatching { wm.removeView(it) } }; iconView = null }

    // ─── Config mode ──────────────────────────────────────────────────────────

    private fun isSystemLauncher(pkg: String): Boolean {
        if (pkg in KNOWN_LAUNCHERS) return true
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
        }
        return context.packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo.packageName == pkg }
    }

    private fun isMappablePackage(pkg: String): Boolean {
        return com.slickstax841.padmap.data.GameScanner.isInstalledGame(context, pkg)
    }

    // Icon tap path — auto-detect the current game
    private fun enterConfigModeFromIcon() {
        val svc = PadMapAccessibilityService.instance
        // foregroundPackage is always a real game package (never our own — we skip our own
        // package in onAccessibilityEvent). Fall back to lastGamePackage if the game hasn't
        // fired a window event yet since the service started.
        val pkg = svc?.foregroundPackage
            ?.takeIf { isMappablePackage(it) }
            ?: svc?.lastGamePackage?.takeIf { isMappablePackage(it) }
            ?: ""
        // Block if no game is open yet (blank) or if foreground is a non-game package (GMS, launcher)
        if (pkg.isBlank()) {
            showToast("Switch to a game first, then tap the config button")
            return
        }
        if (pkg.isNotBlank()) {
            val appName = runCatching {
                val ai = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(ai).toString()
            }.getOrDefault(pkg)

            configGamePackage = pkg  // capture now — no DataStore lookup needed at exit time
            val data = DataStore.data.value
            val existing = data.gameLayouts.find { it.packageName == pkg }
            if (existing != null) {
                if (existing.archived) {
                    DataStore.update { d ->
                        d.copy(gameLayouts = d.gameLayouts.map {
                            if (it.id == existing.id) it.copy(archived = false) else it
                        })
                    }
                }
                loadLayout(existing.id)
                if (data.activeLayoutId != existing.id) {
                    DataStore.update { it.copy(activeLayoutId = existing.id) }
                }
            } else {
                val newId = UUID.randomUUID().toString()
                val newLayout = GameLayout(
                    id = newId,
                    name = appName,
                    packageName = pkg,
                    controllerPresetId = data.activePresetId
                )
                DataStore.update { it.copy(gameLayouts = it.gameLayouts + newLayout) }
                editingLayoutId = newId
                editingZones.clear()
            }
        }
        enterConfigMode()
    }

    private fun loadLayout(layoutId: String) {
        editingLayoutId = layoutId
        editingLayer = 1
        editingZones.clear()
        // Keep configGamePackage in sync when opening via startConfigOverlay (GameLayoutScreen path)
        val layoutPkg = DataStore.data.value.gameLayouts.find { it.id == layoutId }?.packageName ?: ""
        if (layoutPkg.isNotBlank()) configGamePackage = layoutPkg
        DataStore.data.value.gameLayouts.find { it.id == layoutId }?.mappings?.forEach { entry ->
            // Preserve the saved zone ID so tuning keyed by that ID survives save/load cycles.
            // Blank zoneId means this entry was saved before zone-based tuning was introduced.
            val zid = entry.zoneId.ifBlank { UUID.randomUUID().toString() }
            when (val a = entry.action) {
                is TouchAction.Tap ->
                    editingZones.add(ZoneData(id = zid, inputName = entry.inputName, cx = a.x, cy = a.y, isStick = false, turbo = entry.turbo, innerRadius = a.radius, layer = entry.layer.coerceIn(1, 6)))
                is TouchAction.Drag ->
                    editingZones.add(ZoneData(id = zid, inputName = entry.inputName, cx = a.centerX, cy = a.centerY, innerRadius = 50f, outerRadius = a.radius, isStick = true, turbo = false, deadZone = a.deadZone, lookMode = a.lookMode, layer = entry.layer.coerceIn(1, 6)))
            }
            if (entry.inputName == "LT" || entry.inputName == "RT")
                ButtonTuningStore.initForTrigger(zid)
        }
    }

    private fun enterConfigMode() {
        state = State.CONFIG
        hidePlayCatcher()
        iconView?.visibility = View.GONE
        buildConfigOverlay()
        PadMapAccessibilityService.instance?.updateInputInterception()
    }

    private fun exitConfigMode() {
        // configGamePackage was set when config was opened — no DataStore lookup needed
        // and no timing race with async DataStore.update for newly created layouts.
        val gamePackage = configGamePackage

        state = State.FLOATING
        pendingZoneId = null

        // Defer WindowManager removal to the next looper iteration so we are not tearing
        // down the view hierarchy while still inside an active touch-dispatch chain.
        // configGamePackage stays non-blank until cleared here, which keeps the a11y guard
        // active for this one tick so the overlay can't overwrite foregroundPackage.
        handler.post {
            removeConfig()
            iconView?.visibility = View.VISIBLE
            if (gamePackage.isNotBlank()) applyIconForGame(gamePackage)
            PadMapAccessibilityService.instance?.restoreGamePackage(gamePackage)
            configGamePackage = ""
        }
    }

    private fun closeAndDisableIcon() {
        state = State.FLOATING
        pendingZoneId = null
        removeConfig()
        removeIcon()
        showToast("Open PadMap to show the config button again")
    }

    // ─── Config overlay build ─────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun buildConfigOverlay() {
        removeConfig()
        zoneViews.clear()
        contextMenuViews.clear()

        val root = FrameLayout(context)
        root.setBackgroundColor(Color.argb(80, 0, 160, 255))  // light blue tint
        // Consume window insets so Android does not automatically shrink the view
        // content away from nav bar areas — the overlay must fill its full window area.
        root.setOnApplyWindowInsetsListener { _, insets -> insets }

        val zones = FrameLayout(context)
        zoneLayer = zones

        editingZones.filter { it.layer == editingLayer }.forEach { addZoneView(zones, it) }

        // Tapping empty space: create new zone or dismiss context menu
        zones.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> true  // must consume DOWN to receive UP
                MotionEvent.ACTION_UP -> {
                    if (contextMenuViews.isNotEmpty()) dismissContextMenu()
                    val hitExisting = editingZones.any { z ->
                        if (z.layer != editingLayer) return@any false
                        val dx = e.x - z.cx; val dy = e.y - z.cy
                        sqrt(dx * dx + dy * dy) < (if (z.isStick && z.inputName.isNotBlank()) z.outerRadius else z.innerRadius) + dp(20)
                    }
                    val hasUnassigned = editingZones.any { it.layer == editingLayer && it.inputName.isBlank() }
                    if (!hitExisting && !hasUnassigned) createZone(e.x, e.y)
                    true
                }
                else -> false
            }
        }

        root.addView(zones, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Floating control panel — starts centred, draggable with no hold delay
        val panel = buildTopPanel()
        configPanel = panel
        val panelLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        root.addView(panel, panelLp)
        // After layout, switch to margin-based positioning so drag works correctly.
        // Restore saved position if the user has moved the panel before; otherwise centre it.
        panel.post {
            val lp = panel.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.TOP or Gravity.START
            val saved = DataStore.data.value
            lp.leftMargin = saved.panelX ?: ((root.width  - panel.width)  / 2)
            lp.topMargin  = saved.panelY ?: ((root.height - panel.height) / 2)
            panel.layoutParams = lp
        }
        val dragStart = floatArrayOf(0f, 0f)
        val panelOrigin = intArrayOf(0, 0)
        var dragging = false
        // Returns true if (px, py) — relative to vg — lands on a clickable descendant
        fun hasClickableAt(vg: android.view.ViewGroup, px: Int, py: Int): Boolean {
            for (i in vg.childCount - 1 downTo 0) {
                val child = vg.getChildAt(i)
                if (px in child.left..child.right && py in child.top..child.bottom) {
                    if (child.isClickable) return true
                    if (child is android.view.ViewGroup)
                        return hasClickableAt(child, px - child.left, py - child.top)
                    return false
                }
            }
            return false
        }
        panel.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    // If the finger is on a clickable button let it handle the touch normally
                    val hitButton = (v as? android.view.ViewGroup)
                        ?.let { hasClickableAt(it, e.x.toInt(), e.y.toInt()) } ?: false
                    if (hitButton) {
                        dragging = false
                        false  // pass through to child
                    } else {
                        // Touch landed on non-interactive area — capture for drag
                        dragging = true
                        dragStart[0] = e.rawX; dragStart[1] = e.rawY
                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        panelOrigin[0] = lp.leftMargin; panelOrigin[1] = lp.topMargin
                        true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    lp.leftMargin = (panelOrigin[0] + (e.rawX - dragStart[0])).toInt()
                    lp.topMargin  = (panelOrigin[1] + (e.rawY - dragStart[1])).toInt()
                    v.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return@setOnTouchListener false
                    dragging = false
                    // Persist the new panel position
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    DataStore.update { it.copy(panelX = lp.leftMargin, panelY = lp.topMargin) }
                    true
                }
                else -> false
            }
        }

        // Hidden focusable view that receives gamepad key/axis events directly from the window
        val catcher = KeyCatcherView(context,
            onButton = { handleButtonAssignment(it) },
            onAxis = { code, value -> handleAxisAssignment(code, value) }
        )
        keyCatcher = catcher
        root.addView(catcher, FrameLayout.LayoutParams(1, 1))

        applyOverlayFit(configParams)

        configRoot = root
        try {
            wm.addView(root, configParams)
            root.post { catcher.requestFocus() }
        } catch (e: Exception) {
            configRoot = null
            showToast("Could not show config overlay: ${e.message}")
        }
    }

    private fun buildTopPanel(): View {
        val layoutObj = DataStore.data.value.gameLayouts.find { it.id == editingLayoutId }
        val preset = DataStore.data.value.controllerPresets.find { it.id == layoutObj?.controllerPresetId }

        // Compact vertical card — centred on screen, draggable from the title area
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(166, 0, 0, 0))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#00BFFF"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = 12f
        }

        // Game icon + name row (non-clickable — acts as drag handle)
        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val pkg = layoutObj?.packageName ?: ""
        if (pkg.isNotBlank()) {
            runCatching {
                val icon = context.packageManager.getApplicationIcon(pkg)
                val iconSize = dp(24)
                nameRow.addView(ImageView(context).apply {
                    setImageDrawable(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        marginEnd = dp(8)
                    }
                })
            }
        }
        nameRow.addView(TextView(context).apply {
            text = layoutObj?.name ?: "Unknown game"
            textSize = 12f
            setTextColor(Color.parseColor("#00BFFF"))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // CLOSE — top right, opposite the game title
        nameRow.addView(TextView(context).apply {
            text = "\u2715"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { exitConfigMode() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        })
        panel.addView(nameRow)
        if (preset != null) {
            panel.addView(TextView(context).apply {
                text = preset.name
                textSize = 10f
                setTextColor(Color.parseColor("#666666"))
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }

        panel.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })

        val layerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)
            )
        }
        for (i in 1..6) {
            val n = i
            layerRow.addView(TextView(context).apply {
                text = n.toString()
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (n == editingLayer) Color.BLACK else Color.parseColor("#00BFFF"))
                background = GradientDrawable().apply {
                    setColor(if (n == editingLayer) Color.parseColor("#00BFFF") else Color.parseColor("#222222"))
                    setStroke(dp(1), Color.parseColor("#00BFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    if (editingLayer == n) return@setOnClickListener
                    editingLayer = n
                    dismissContextMenu()
                    rebuildZoneLayer()
                    buildConfigOverlay()
                }
            })
        }
        panel.addView(layerRow)
        panel.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })

        // Button row — WRAP_CONTENT so the card grows to fit all four actions
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ADJUST — overlay positioning tool
        btnRow.addView(AdjustIcon(context).apply {
            setOnClickListener { enterAdjustMode() }
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        btnRow.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        btnRow.addView(TextView(context).apply {
            text = "DEBUG"
            textSize = 11f
            setTextColor(Color.parseColor("#00BFFF"))
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = outlineDrawable(Color.parseColor("#00BFFF"))
            setOnClickListener { toggleDebugBox() }
        })
        btnRow.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        // CLEAR
        btnRow.addView(TextView(context).apply {
            text = "CLEAR"
            textSize = 11f
            setTextColor(Color.parseColor("#CC3333"))
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = outlineDrawable(Color.parseColor("#CC3333"))
            setOnClickListener { clearAllZones() }
        })
        btnRow.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        // SAVE
        btnRow.addView(TextView(context).apply {
            text = "SAVE"
            textSize = 11f
            setTextColor(Color.BLACK)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00BFFF"))
                cornerRadius = dp(5).toFloat()
            }
            setOnClickListener { saveAndExit() }
        })

        panel.addView(btnRow)
        panel.addView(buildDebugBox())
        return panel
    }

    private fun buildDebugBox(): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(TextView(context).apply {
            text = "SHARE"
            textSize = 11f
            setTextColor(Color.parseColor("#00BFFF"))
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = outlineDrawable(Color.parseColor("#00BFFF"))
            setOnClickListener { sharePlaybackDebug() }
        })
        val log = TextView(context).apply {
            textSize = 9f
            setTextColor(Color.parseColor("#DDDDDD"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
            maxHeight = dp(180)
        }
        debugLogView = log
        box.addView(log)
        debugBox = box
        return box
    }

    private fun toggleDebugBox() {
        debugOpen = !debugOpen
        debugBox?.visibility = if (debugOpen) View.VISIBLE else View.GONE
        handler.removeCallbacks(debugRefresh)
        if (debugOpen) {
            debugLogView?.text = PlaybackDebug.snapshot()
            handler.post(debugRefresh)
        }
    }

    private fun sharePlaybackDebug() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PadMap playback debug")
            putExtra(Intent.EXTRA_TEXT, PlaybackDebug.snapshot())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(send, "Share playback debug").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ─── Zone management ──────────────────────────────────────────────────────

    private fun addZoneView(parent: FrameLayout, zone: ZoneData) {
        val v = ZoneCircleView(context, zone) { z -> showContextMenu(z) }
        zoneViews[zone.id] = v
        val size = viewSizeForZone(zone)
        parent.addView(v, FrameLayout.LayoutParams(size, size).apply {
            leftMargin = (zone.cx - size / 2f).toInt()
            topMargin = (zone.cy - size / 2f).toInt()
        })
    }

    private fun bumpButtonSize(zone: ZoneData, delta: Float) {
        zone.innerRadius = (zone.innerRadius + delta).coerceIn(dp(16).toFloat(), dp(80).toFloat())
        DataStore.update { it.copy(buttonZoneRadius = zone.innerRadius) }
        val v = zoneViews[zone.id] ?: return
        val newSize = viewSizeForZone(zone)
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = newSize
        lp.height = newSize
        lp.leftMargin = (zone.cx - newSize / 2f).toInt()
        lp.topMargin = (zone.cy - newSize / 2f).toInt()
        v.layoutParams = lp
        v.invalidate()
    }

    private fun createZone(x: Float, y: Float) {
        val r = DataStore.data.value.buttonZoneRadius.coerceAtLeast(16f)
        val zone = ZoneData(cx = x, cy = y, innerRadius = r, layer = editingLayer)
        editingZones.add(zone)
        pendingZoneId = zone.id
        zoneLayer?.let { addZoneView(it, zone) }
        keyCatcher?.requestFocus()
    }

    private fun clearAllZones() {
        editingZones.removeAll { it.layer == editingLayer }
        pendingZoneId = null
        dismissContextMenu()
        rebuildZoneLayer()
    }

    private fun rebuildZoneLayer() {
        dismissContextMenu()
        val layer = zoneLayer ?: return
        layer.removeAllViews()
        zoneViews.clear()
        editingZones.filter { it.layer == editingLayer }.forEach { addZoneView(layer, it) }
    }

    private fun saveAndExit() {
        val mappings = editingZones.filter { it.inputName.isNotBlank() }.map { zone ->
            MappingEntry(
                inputName = zone.inputName,
                action = if (zone.isStick)
                    TouchAction.Drag(zone.cx, zone.cy, zone.outerRadius, zone.deadZone, zone.lookMode)
                else
                    TouchAction.Tap(zone.cx, zone.cy, zone.innerRadius),
                turbo = zone.turbo,
                zoneId = zone.id,
                layer = zone.layer.coerceIn(1, 6)
            )
        }
        DataStore.update { data ->
            val updatedLayouts = if (data.gameLayouts.any { it.id == editingLayoutId }) {
                // Normal path: layout exists, update its mappings in-place.
                data.gameLayouts.map {
                    if (it.id == editingLayoutId) it.copy(mappings = mappings) else it
                }
            } else {
                // Safety net: layout was dropped from in-memory state (e.g. DataStore race
                // at service startup overwrote the entry added in enterConfigModeFromIcon).
                // Re-add it rather than silently discarding the user's work.
                data.gameLayouts + GameLayout(
                    id = editingLayoutId,
                    packageName = configGamePackage,
                    name = configGamePackage,
                    controllerPresetId = data.activePresetId,
                    mappings = mappings
                )
            }
            data.copy(activeLayoutId = editingLayoutId, gameLayouts = updatedLayouts)
        }
        exitConfigMode()
    }

    // ─── Overlay adjust mode ──────────────────────────────────────────────────

    private fun enterAdjustMode() {
        if (adjustMode) return
        adjustMode = true
        val root = configRoot ?: return
        root.background = GradientDrawable().apply {
            setColor(Color.argb(40, 0, 160, 255))
        }
        // Switch window to explicit pixel positioning so it can be dragged/resized.
        // FLAG_LAYOUT_NO_LIMITS lets the user push the overlay past the screen edges
        // if the device clips zones that should be reachable.
        applyOverlayFit(configParams, forcePixels = true)
        runCatching { wm.updateViewLayout(root, configParams) }
        configPanel?.visibility = View.GONE
        showAdjustLayer(root)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showAdjustLayer(root: FrameLayout) {
        var dragStartRawX = 0f; var dragStartRawY = 0f
        var winStartX = 0; var winStartY = 0
        // per-edge drag state: [startRaw], [startParam1, startParam2?]
        val topRaw = floatArrayOf(0f);   val topP  = intArrayOf(0, 0)
        val botRaw = floatArrayOf(0f);   val botP  = intArrayOf(0)
        val lefRaw = floatArrayOf(0f);   val lefP  = intArrayOf(0, 0)
        val rigRaw = floatArrayOf(0f);   val rigP  = intArrayOf(0)

        // Minimum overlay size is double the panel size so there is always room to work.
        fun minW() = ((configPanel?.width  ?: 0) + dp(40)) * 2
        fun minH() = ((configPanel?.height ?: 0) + dp(40)) * 2

        // 0 = move window, 1–4 = resize that edge. Decided on DOWN from local x/y
        // so a press on the border never falls through to a whole-window drag.
        var dragMode = 0
        val band = dp(52)
        val layer = object : FrameLayout(context) {
            init {
                isClickable = true
                isLongClickable = false
            }

            private fun hitClickableChild(x: Float, y: Float): Boolean {
                for (i in childCount - 1 downTo 0) {
                    val c = getChildAt(i)
                    if (c.isClickable && x >= c.left && x < c.right && y >= c.top && y < c.bottom) return true
                }
                return false
            }

            private fun edgeAt(x: Float, y: Float): Int {
                val b = band.toFloat()
                val w = width.toFloat()
                val h = height.toFloat()
                return when {
                    x <= b -> 3
                    x >= w - b -> 4
                    y <= b -> 1
                    y >= h - b -> 2
                    else -> 0
                }
            }

            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                // Own the stream on DOWN so there is no press-and-hold before resize.
                return e.actionMasked == MotionEvent.ACTION_DOWN && !hitClickableChild(e.x, e.y)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        dragMode = edgeAt(e.x, e.y)
                        dragStartRawX = e.rawX
                        dragStartRawY = e.rawY
                        winStartX = configParams.x
                        winStartY = configParams.y
                        topRaw[0] = e.rawY
                        topP[0] = configParams.y
                        topP[1] = configParams.height.takeIf { it > 0 } ?: dm.heightPixels
                        botRaw[0] = e.rawY
                        botP[0] = configParams.height.takeIf { it > 0 } ?: dm.heightPixels
                        lefRaw[0] = e.rawX
                        lefP[0] = configParams.x
                        lefP[1] = configParams.width.takeIf { it > 0 } ?: dm.widthPixels
                        rigRaw[0] = e.rawX
                        rigP[0] = configParams.width.takeIf { it > 0 } ?: dm.widthPixels
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        when (dragMode) {
                            1 -> {
                                val delta = (e.rawY - topRaw[0]).toInt()
                                val newH = (topP[1] - delta).coerceAtLeast(minH())
                                configParams.y = topP[0] + (topP[1] - newH)
                                configParams.height = newH
                            }
                            2 -> {
                                configParams.height = (botP[0] + (e.rawY - botRaw[0]).toInt()).coerceAtLeast(minH())
                            }
                            3 -> {
                                val delta = (e.rawX - lefRaw[0]).toInt()
                                val newW = (lefP[1] - delta).coerceAtLeast(minW())
                                configParams.x = lefP[0] + (lefP[1] - newW)
                                configParams.width = newW
                            }
                            4 -> {
                                configParams.width = (rigP[0] + (e.rawX - rigRaw[0]).toInt()).coerceAtLeast(minW())
                            }
                            else -> {
                                configParams.x = winStartX + (e.rawX - dragStartRawX).toInt()
                                configParams.y = winStartY + (e.rawY - dragStartRawY).toInt()
                            }
                        }
                        runCatching { wm.updateViewLayout(configRoot, configParams) }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return true
            }
        }

        val glow = OverlayGlowView(context)
        glow.isClickable = false
        glow.isFocusable = false
        layer.addView(glow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Instruction label — centred, slightly above the Save button
        layer.addView(TextView(context).apply {
            text = "DRAG MIDDLE TO MOVE \u2022 DRAG ANY EDGE TO RESIZE"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(160, 0, 0, 0))
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        ).apply { bottomMargin = dp(52) })

        // Save button — direct child of layer so it always receives touches correctly
        layer.addView(TextView(context).apply {
            text = "Save"
            textSize = 13f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00BFFF"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { exitAdjustMode() }
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        ).apply { topMargin = dp(52) })

        fun edgeStrip() = View(context).apply {
            isClickable = false
            setBackgroundColor(Color.argb(110, 255, 140, 0))
        }
        layer.addView(edgeStrip(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, band, Gravity.TOP
        ))
        layer.addView(edgeStrip(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, band, Gravity.BOTTOM
        ))
        layer.addView(edgeStrip(), FrameLayout.LayoutParams(
            band, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START
        ))
        layer.addView(edgeStrip(), FrameLayout.LayoutParams(
            band, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END
        ))

        adjustLayer = layer
        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun exitAdjustMode() {
        adjustMode = false
        val root = configRoot ?: return
        // Save the current window position and size
        DataStore.update { saveOverlayRect(it, configParams.x, configParams.y, configParams.width, configParams.height) }
        // Remove the adjust layer
        adjustLayer?.let { root.removeView(it) }
        adjustLayer = null
        // Restore normal config tint
        root.setBackgroundColor(Color.argb(80, 0, 160, 255))
        // Show the panel again, centred on the new overlay dimensions
        val panel = configPanel ?: return
        panel.visibility = View.VISIBLE
        panel.post {
            val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return@post
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = (configParams.width  - panel.width)  / 2
            lp.topMargin  = (configParams.height - panel.height) / 2
            panel.layoutParams = lp
            // Persist the new centred position so it's restored next session
            DataStore.update { it.copy(panelX = lp.leftMargin, panelY = lp.topMargin) }
        }
    }

    private fun removeConfig() {
        debugOpen = false
        handler.removeCallbacks(debugRefresh)
        configRoot?.let { runCatching { wm.removeView(it) } }
        configRoot = null; zoneLayer = null; zoneViews.clear(); contextMenuViews.clear()
        keyCatcher = null; adjustLayer = null; adjustMode = false; configPanel = null
        debugLogView = null; debugBox = null
    }

    // ─── Context menu (tap on assigned zone) ─────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showContextMenu(zone: ZoneData) {
        dismissContextMenu()
        // Make this zone live for reassignment — pressing any button/stick while the
        // context menu is open will reassign the zone to the new input.
        pendingZoneId = zone.id

        // null onClick = drag handle (gets a touch listener instead of click listener)
        data class BtnSpec(val icon: String, val bgColor: Int, val onClick: (() -> Unit)?)
        val buttons = mutableListOf<BtnSpec>()

        // DELETE — always present
        buttons.add(BtnSpec("\u2715", Color.parseColor("#CC3333")) {
            if (zone.isStick && zone.inputName.isNotBlank()) hideDebugOverlay(zone.inputName)
            editingZones.removeAll { it.id == zone.id }
            if (pendingZoneId == zone.id) pendingZoneId = null
            dismissContextMenu()
            rebuildZoneLayer()
        })

        if (!zone.isStick && zone.inputName.isNotBlank()) {
            buttons.add(BtnSpec("\u2398", Color.parseColor("#336699")) {
                val copy = zone.copy(
                    id = UUID.randomUUID().toString(),
                    cx = (zone.cx + dp(40)).coerceAtMost(dm.widthPixels - zone.innerRadius),
                    cy = (zone.cy + dp(40)).coerceAtMost(dm.heightPixels - zone.innerRadius)
                )
                editingZones.add(copy)
                dismissContextMenu()
                rebuildZoneLayer()
                showContextMenu(copy)
            })
            buttons.add(BtnSpec("\u2212", Color.parseColor("#007A99")) {
                bumpButtonSize(zone, -8f)
            })
            buttons.add(BtnSpec("+", Color.parseColor("#007A99")) {
                bumpButtonSize(zone, 8f)
            })
        }

        // LOOK / MOVE toggle — stick zones only, must be assigned
        // ⊕ = Move (position-based locomotion), ◎ = Look (velocity-based camera)
        if (zone.isStick && zone.inputName.isNotBlank()) {
            val modeColor = if (zone.lookMode) Color.parseColor("#7B2FBE") else Color.parseColor("#1A7A4A")
            val modeIcon  = if (zone.lookMode) "\u25CE" else "\u2295"  // ◎ Look / ⊕ Move
            buttons.add(BtnSpec(modeIcon, modeColor) {
                zone.lookMode = !zone.lookMode
                zoneViews[zone.id]?.invalidate()
                showContextMenu(zone)
            })
        }

        // TUNE — button zones only, must be assigned
        if (!zone.isStick && zone.inputName.isNotBlank()) {
            buttons.add(BtnSpec("\u2699", Color.parseColor("#007A99")) {
                showTuningBox(zone.id, zone.inputName, zone.cx, zone.cy)
            })
        }

        // TUNE — stick zones only, must be assigned (speed, sensitivity)
        if (zone.isStick && zone.inputName.isNotBlank()) {
            buttons.add(BtnSpec("\u2699", Color.parseColor("#007A99")) {
                showStickTuningBox(zone.id, zone.inputName, zone.cx, zone.cy)
            })
        }

        val btnSize = dp(40)
        val gap = dp(12)
        val pad = dp(14)
        val orbit = btnSize + gap
        val clusterSize = (orbit + btnSize / 2 + pad) * 2

        fun optionBtn(spec: BtnSpec) = TextView(context).apply {
            text = spec.icon
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(spec.bgColor)
            }
            elevation = 14f
            if (spec.onClick != null) setOnClickListener { spec.onClick.invoke() }
        }

        val cluster = FrameLayout(context).apply {
            isClickable = true
            elevation = 16f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 0, 0, 0))
                setStroke(dp(2), Color.parseColor("#FF8C00"))
            }
        }

        val mid = (clusterSize - btnSize) / 2
        val moveBtn = optionBtn(BtnSpec("\u271B", Color.parseColor("#336699"), null))
        cluster.addView(moveBtn, FrameLayout.LayoutParams(btnSize, btnSize).apply {
            leftMargin = mid
            topMargin = mid
        })
        buttons.forEachIndexed { i, spec ->
            val angle = -Math.PI / 2.0 + 2.0 * Math.PI * i / buttons.size
            val cx = clusterSize / 2f + cos(angle).toFloat() * orbit - btnSize / 2f
            val cy = clusterSize / 2f + sin(angle).toFloat() * orbit - btnSize / 2f
            cluster.addView(optionBtn(spec), FrameLayout.LayoutParams(btnSize, btnSize).apply {
                leftMargin = cx.toInt()
                topMargin = cy.toInt()
            })
        }

        val overlayW = (configRoot?.width?.takeIf { it > 0 } ?: dm.widthPixels)
        val overlayH = (configRoot?.height?.takeIf { it > 0 } ?: dm.heightPixels)
        val maxX = (overlayW - clusterSize).coerceAtLeast(0)
        val maxY = (overlayH - clusterSize).coerceAtLeast(0)
        val saved = DataStore.data.value
        val clusterLp = FrameLayout.LayoutParams(clusterSize, clusterSize).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (saved.optionsX ?: ((overlayW - clusterSize) / 2)).coerceIn(0, maxX)
            topMargin = (saved.optionsY ?: ((overlayH - clusterSize) / 2)).coerceIn(0, maxY)
        }
        configRoot?.addView(cluster, clusterLp)
        contextMenuViews.add(cluster)
        highlightZone(zone.id)

        var dragStartRawX = 0f
        var dragStartRawY = 0f
        var dragStartX = 0
        var dragStartY = 0
        moveBtn.setOnTouchListener { _, event ->
            val lp = cluster.layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartX = lp.leftMargin
                    dragStartY = lp.topMargin
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.leftMargin = (dragStartX + (event.rawX - dragStartRawX).toInt()).coerceIn(0, maxX)
                    lp.topMargin = (dragStartY + (event.rawY - dragStartRawY).toInt()).coerceIn(0, maxY)
                    cluster.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    DataStore.update { it.copy(optionsX = lp.leftMargin, optionsY = lp.topMargin) }
                    true
                }
                else -> false
            }
        }

        // Re-grab focus so gamepad button presses reach the KeyCatcherView.
        // Clickable TextViews added above can steal focus, which would prevent
        // handleButtonAssignment from being called when the user presses a new button.
        keyCatcher?.requestFocus()
    }

    private fun highlightZone(id: String?) {
        zoneViews.forEach { (zid, v) -> v.setMenuActive(zid == id) }
    }

    private fun dismissContextMenu() {
        highlightZone(null)
        contextMenuViews.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        contextMenuViews.clear()
        dismissTuningBox()
        // If the pending zone is already assigned, clear the pending state — the user
        // dismissed the menu without pressing a new input, so no reassignment should happen.
        val pendingZone = editingZones.find { it.id == pendingZoneId }
        if (pendingZone?.inputName?.isNotBlank() == true) pendingZoneId = null
    }

    private fun dismissTuningBox() {
        tuningBoxView?.let { zoneLayer?.removeView(it) }
        tuningBoxView = null
        restoreHomeAfterTune()
    }

    private fun showPlayCatcher() {
        // A11y already gets pad keys/motion. A focusable overlay steals the
        // game's first window focus and hangs splash (BUG-008).
    }

    private fun hidePlayCatcher() {
        playCatcher?.let { runCatching { wm.removeView(it) } }
        playCatcher = null
        PadMapAccessibilityService.instance?.releaseAllPlayback()
    }

    private class PlaybackCatcherView(ctx: Context) : View(ctx) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (event.source and InputDevice.SOURCE_GAMEPAD == 0 &&
                event.source and InputDevice.SOURCE_JOYSTICK == 0) {
                return super.onKeyDown(keyCode, event)
            }
            if (event.repeatCount == 0) {
                PlaybackDebug.log("catcher key $keyCode")
                PadMapAccessibilityService.instance?.handlePlaybackDown(keyCode)
            }
            return true
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
            if (event.source and InputDevice.SOURCE_GAMEPAD == 0 &&
                event.source and InputDevice.SOURCE_JOYSTICK == 0) {
                return super.onKeyUp(keyCode, event)
            }
            PadMapAccessibilityService.instance?.handlePlaybackUp(keyCode)
            return true
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            val isPad = event.source and InputDevice.SOURCE_JOYSTICK != 0 ||
                event.source and InputDevice.SOURCE_GAMEPAD != 0
            if (!isPad || event.action != MotionEvent.ACTION_MOVE) {
                return super.dispatchGenericMotionEvent(event)
            }
            PlaybackDebug.logMotion("catcher motion")
            PadMapAccessibilityService.instance?.handlePlaybackMotion(event)
            return true
        }
    }

    // ─── Key/axis catcher — receives controller input while config overlay is focused ──

    private class KeyCatcherView(
        ctx: Context,
        private val onButton: (Int) -> Unit,
        private val onAxis: (Int, Float) -> Unit
    ) : View(ctx) {
        init { isFocusable = true; isFocusableInTouchMode = true }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (event.source and InputDevice.SOURCE_GAMEPAD != 0 ||
                event.source and InputDevice.SOURCE_JOYSTICK != 0) {
                if (event.repeatCount == 0) onButton(keyCode)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
            if (event.source and InputDevice.SOURCE_GAMEPAD != 0 ||
                event.source and InputDevice.SOURCE_JOYSTICK != 0) return true
            return super.onKeyUp(keyCode, event)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            val isJoystick = event.source and InputDevice.SOURCE_JOYSTICK != 0
            val isGamepad  = event.source and InputDevice.SOURCE_GAMEPAD  != 0
            if ((isJoystick || isGamepad) && event.action == MotionEvent.ACTION_MOVE) {
                // D-pad HAT axes arrive with SOURCE_GAMEPAD — check these first
                val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                if (abs(hatX) > 0.5f) { onAxis(MotionEvent.AXIS_HAT_X, hatX); return true }
                if (abs(hatY) > 0.5f) { onAxis(MotionEvent.AXIS_HAT_Y, hatY); return true }
                // Analog stick axes arrive with SOURCE_JOYSTICK
                if (isJoystick) {
                    listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ).forEach { axis ->
                        val v = event.getAxisValue(axis)
                        if (abs(v) > 0.4f) { onAxis(axis, v); return true }
                    }
                }
                return true
            }
            return super.dispatchGenericMotionEvent(event)
        }
    }

    private fun handleButtonAssignment(keyCode: Int) {
        if (pendingZoneId == null) return
        val preset = DataStore.data.value.gameLayouts.find { it.id == editingLayoutId }
            ?.let { l -> DataStore.data.value.controllerPresets.find { it.id == l.controllerPresetId } }
        val label = preset?.buttons?.get(keyCode) ?: keyCodeLabel(keyCode)
        assignPendingZone(label, false)
    }

    private fun handleAxisAssignment(axisCode: Int, value: Float) {
        if (pendingZoneId == null) return
        // D-pad HAT axes are directions, not analog sticks — resolve to a button label
        if (axisCode == MotionEvent.AXIS_HAT_X || axisCode == MotionEvent.AXIS_HAT_Y) {
            val label = when {
                axisCode == MotionEvent.AXIS_HAT_X && value < 0 -> "D-Left"
                axisCode == MotionEvent.AXIS_HAT_X && value > 0 -> "D-Right"
                axisCode == MotionEvent.AXIS_HAT_Y && value < 0 -> "D-Up"
                else                                             -> "D-Down"
            }
            assignPendingZone(label, false)
            return
        }
        val preset = DataStore.data.value.gameLayouts.find { it.id == editingLayoutId }
            ?.let { l -> DataStore.data.value.controllerPresets.find { it.id == l.controllerPresetId } }
        val raw = preset?.axes?.get(axisCode) ?: axisCodeLabel(axisCode)
        val label = raw.removeSuffix(" X").removeSuffix(" Y")
        assignPendingZone(label, true)
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

    private fun axisCodeLabel(axis: Int) = when (axis) {
        MotionEvent.AXIS_X, MotionEvent.AXIS_Y -> "L-Stick"
        MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ -> "R-Stick"
        MotionEvent.AXIS_LTRIGGER -> "LT"
        MotionEvent.AXIS_RTRIGGER -> "RT"
        else -> "Axis$axis"
    }

    // ─── Zone circle view ─────────────────────────────────────────────────────

    @SuppressLint("ViewConstructor")
    inner class ZoneCircleView(
        ctx: Context,
        var zone: ZoneData,
        private val onTap: (ZoneData) -> Unit
    ) : View(ctx) {

        private var dragMode = DragMode.NONE
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var menuHidden = false
        private var menuActive = false

        private val glowAccent = Color.parseColor("#FF8C00")
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
        }
        private val glowSpin = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3600
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { invalidate() }
        }
        private val glowPulse = ValueAnimator.ofFloat(0.78f, 1f).apply {
            duration = 1700
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { invalidate() }
        }

        fun setMenuActive(on: Boolean) {
            if (menuActive == on) {
                if (on) invalidate()
                return
            }
            menuActive = on
            if (on) {
                glowSpin.start()
                glowPulse.start()
            } else {
                glowSpin.cancel()
                glowPulse.cancel()
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            glowSpin.cancel()
            glowPulse.cancel()
            super.onDetachedFromWindow()
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 191, 255); style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00BFFF"); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF8C00"); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val deadZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00BFFF"); style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f) }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF8C00"); style = Paint.Style.FILL }
        private val deadDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00BFFF"); style = Paint.Style.FILL }
        private val pendingFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 180, 180, 180); style = Paint.Style.FILL }
        private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(12f, 7f), 0f) }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 28f }
        private val turboBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB300"); style = Paint.Style.FILL }

        // Returns the angle (radians) pointing away from the nearest screen corner —
        // this is where the resize handles are placed so they're always in open space.
        private fun handleAngle(): Double {
            val sw = (configRoot?.width?.takeIf { it > 0 } ?: dm.widthPixels).toFloat()
            val sh = (configRoot?.height?.takeIf { it > 0 } ?: dm.heightPixels).toFloat()
            val corners = listOf(0f to 0f, sw to 0f, 0f to sh, sw to sh)
            val nearest = corners.minByOrNull { (cx2, cy2) ->
                (zone.cx - cx2).pow(2) + (zone.cy - cy2).pow(2)
            } ?: (0f to 0f)
            return atan2((zone.cy - nearest.second).toDouble(), (zone.cx - nearest.first).toDouble())
        }

        private fun blendGlow(color: Int, amount: Float): Int {
            val t = amount.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(color) + (255 - Color.red(color)) * t).toInt(),
                (Color.green(color) + (255 - Color.green(color)) * t).toInt(),
                (Color.blue(color) + (255 - Color.blue(color)) * t).toInt()
            )
        }

        private fun drawBorderGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
            val progress = glowSpin.animatedValue as? Float ?: 0f
            val p = glowPulse.animatedValue as? Float ?: 1f
            val mid = blendGlow(glowAccent, 0.22f)
            val hot = blendGlow(glowAccent, 0.78f)
            val colors = intArrayOf(
                glowAccent, mid, hot, Color.WHITE, hot, mid, glowAccent,
                mid, hot, Color.WHITE, hot, mid, glowAccent
            )
            val stops = floatArrayOf(
                0f, 0.16f, 0.21f, 0.24f, 0.27f, 0.32f, 0.42f,
                0.66f, 0.71f, 0.74f, 0.77f, 0.82f, 1f
            )
            val shader = SweepGradient(cx, cy, colors, stops)
            shader.setLocalMatrix(Matrix().apply { setRotate(progress * 360f, cx, cy) })
            glowPaint.shader = shader
            val stroke = 6f
            glowPaint.strokeWidth = stroke * 1.7f
            glowPaint.alpha = (70 * p).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius, glowPaint)
            glowPaint.strokeWidth = stroke * 1.25f
            glowPaint.alpha = (165 * p).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius, glowPaint)
            glowPaint.strokeWidth = stroke * 1.05f
            glowPaint.alpha = 255
            canvas.drawCircle(cx, cy, radius, glowPaint)
            glowPaint.strokeWidth = stroke * 0.7f
            canvas.drawCircle(cx, cy, radius, glowPaint)
            glowPaint.shader = null
            glowPaint.alpha = 255
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            if (zone.inputName.isBlank()) {
                canvas.drawCircle(cx, cy, zone.innerRadius, pendingFill)
                canvas.drawCircle(cx, cy, zone.innerRadius, dashPaint)
                textPaint.textSize = zone.innerRadius * 0.65f
                textPaint.color = Color.LTGRAY
                canvas.drawText("?", cx, cy + textPaint.textSize * 0.35f, textPaint)
            } else {
                if (menuActive) {
                    fillPaint.color = Color.argb(200, 255, 140, 0)
                    strokePaint.color = glowAccent
                    strokePaint.strokeWidth = 5f
                } else {
                    fillPaint.color = Color.argb(150, 0, 191, 255)
                    strokePaint.color = Color.parseColor("#00BFFF")
                    strokePaint.strokeWidth = 3f
                }
                canvas.drawCircle(cx, cy, zone.innerRadius, fillPaint)
                canvas.drawCircle(cx, cy, zone.innerRadius, strokePaint)
                if (zone.isStick) {
                    canvas.drawCircle(cx, cy, zone.outerRadius, outerPaint)
                    canvas.drawCircle(cx, cy, zone.deadZone * zone.outerRadius, deadZonePaint)
                    val ang = handleAngle()
                    canvas.drawCircle(
                        cx + cos(ang).toFloat() * zone.outerRadius,
                        cy + sin(ang).toFloat() * zone.outerRadius,
                        dp(10).toFloat(), handlePaint
                    )
                }
                if (menuActive) {
                    val glowR = if (zone.isStick) zone.outerRadius else zone.innerRadius
                    drawBorderGlow(canvas, cx, cy, glowR)
                }
                // Stick zones show a joystick symbol; button zones show the button label
                val displayText = if (zone.isStick) "\u2295" else zone.inputName
                textPaint.textSize = when {
                    zone.isStick -> zone.innerRadius * 0.85f
                    displayText.length > 4 -> zone.innerRadius * 0.45f
                    else -> zone.innerRadius * 0.6f
                }
                textPaint.color = Color.WHITE
                canvas.drawText(displayText, cx, cy + textPaint.textSize / 3f, textPaint)

                // Turbo badge — small filled circle with "T" in top-right of inner circle
                if (zone.turbo) {
                    val bx = cx + zone.innerRadius * 0.65f
                    val by = cy - zone.innerRadius * 0.65f
                    val br = zone.innerRadius * 0.35f
                    canvas.drawCircle(bx, by, br, turboBgPaint)
                    textPaint.textSize = br * 1.3f; textPaint.color = Color.BLACK
                    canvas.drawText("T", bx, by + br * 0.4f, textPaint)
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val cx = width / 2f; val cy = height / 2f
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val dx = e.x - cx; val dy = e.y - cy
                    val dist = sqrt(dx * dx + dy * dy)
                    dragMode = if (zone.isStick && zone.inputName.isNotBlank()) {
                        val ang = handleAngle()
                        val outerDotX = cx + cos(ang).toFloat() * zone.outerRadius
                        val outerDotY = cy + sin(ang).toFloat() * zone.outerRadius
                        val dOuter = sqrt((e.x - outerDotX).pow(2) + (e.y - outerDotY).pow(2))
                        when {
                            dOuter < dp(22) -> DragMode.RESIZE
                            dist <= zone.outerRadius + dp(8) -> DragMode.MOVE
                            else -> return false
                        }
                    } else {
                        if (dist <= zone.innerRadius + dp(8)) DragMode.MOVE else return false
                    }
                    lastRawX = e.rawX; lastRawY = e.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val ddx = e.rawX - lastRawX; val ddy = e.rawY - lastRawY
                    when (dragMode) {
                        DragMode.MOVE -> {
                            if (sqrt(ddx * ddx + ddy * ddy) < dp(20) && !menuHidden) {
                                lastRawX = e.rawX; lastRawY = e.rawY
                                return true
                            }
                            if (!menuHidden) {
                                contextMenuViews.forEach { it.visibility = View.INVISIBLE }
                                menuHidden = true
                            }
                            moveZone(ddx, ddy)
                        }
                        DragMode.RESIZE -> resizeOuter(e.x - cx, e.y - cy)
                        DragMode.NONE -> {}
                    }
                    lastRawX = e.rawX; lastRawY = e.rawY
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dragged = menuHidden
                    if (menuHidden) {
                        contextMenuViews.forEach { it.visibility = View.VISIBLE }
                        menuHidden = false
                    }
                    if (dragMode == DragMode.MOVE && zone.inputName.isNotBlank() && !dragged)
                        onTap(zone)
                    dragMode = DragMode.NONE
                    return true
                }
                else -> return false
            }
        }

        private fun moveZone(ddx: Float, ddy: Float) {
            val r = if (zone.isStick && zone.inputName.isNotBlank()) zone.outerRadius else zone.innerRadius
            val overlayW = (configRoot?.width?.takeIf { it > 0 } ?: dm.widthPixels).toFloat()
            val overlayH = (configRoot?.height?.takeIf { it > 0 } ?: dm.heightPixels).toFloat()
            zone.cx = (zone.cx + ddx).coerceIn(r, overlayW - r)
            zone.cy = (zone.cy + ddy).coerceIn(r, overlayH - r)
            val lp = layoutParams as? FrameLayout.LayoutParams ?: return
            lp.leftMargin = (zone.cx - lp.width / 2f).toInt()
            lp.topMargin = (zone.cy - lp.height / 2f).toInt()
            layoutParams = lp
            if (zone.isStick && zone.inputName.isNotBlank())
                repositionDebugOverlay(zone.inputName, zone.cx, zone.cy, zone.outerRadius)
        }

        private fun resizeOuter(localDx: Float, localDy: Float) {
            zone.outerRadius = sqrt(localDx * localDx + localDy * localDy)
                .coerceAtLeast(zone.innerRadius + dp(20).toFloat())
            val newSize = viewSizeForZone(zone)
            val lp = layoutParams as? FrameLayout.LayoutParams ?: return
            lp.width = newSize; lp.height = newSize
            lp.leftMargin = (zone.cx - newSize / 2f).toInt()
            lp.topMargin = (zone.cy - newSize / 2f).toInt()
            layoutParams = lp
            if (zone.isStick && zone.inputName.isNotBlank())
                repositionDebugOverlay(zone.inputName, zone.cx, zone.cy, zone.outerRadius)
            invalidate()
        }
    }

    // ─── Adjust icon — four corner crop marks ────────────────────────────────

    private inner class AdjustIcon(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#888888")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.SQUARE
        }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val m = w * 0.15f; val len = w * 0.28f
            // Top-left
            canvas.drawLine(m, m + len, m, m, p); canvas.drawLine(m, m, m + len, m, p)
            // Top-right
            canvas.drawLine(w-m-len, m, w-m, m, p); canvas.drawLine(w-m, m, w-m, m+len, p)
            // Bottom-left
            canvas.drawLine(m, h-m-len, m, h-m, p); canvas.drawLine(m, h-m, m+len, h-m, p)
            // Bottom-right
            canvas.drawLine(w-m-len, h-m, w-m, h-m, p); canvas.drawLine(w-m, h-m, w-m, h-m-len, p)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun viewSizeForZone(zone: ZoneData): Int =
        ((if (zone.isStick && zone.inputName.isNotBlank()) zone.outerRadius else zone.innerRadius) * 2 + dp(24)).toInt()
            .coerceAtLeast(dp(56))

    private fun circleDrawable(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(2), stroke)
    }

    // ─── Button tuning box ────────────────────────────────────────────────────

    private fun hideHomeForTune() {
        val panel = configPanel ?: return
        val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        panelLeftBeforeTune = lp.leftMargin
        panelTopBeforeTune = lp.topMargin
        panelHiddenForTune = true
        highlightZone(null)
        contextMenuViews.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        contextMenuViews.clear()
        panel.visibility = View.GONE
    }

    private fun restoreHomeAfterTune() {
        if (!panelHiddenForTune) return
        panelHiddenForTune = false
        val panel = configPanel ?: return
        val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.leftMargin = panelLeftBeforeTune
        lp.topMargin = panelTopBeforeTune
        panel.layoutParams = lp
        panel.visibility = View.VISIBLE
    }

    private fun showTuningBox(zoneId: String, label: String, zoneCx: Float, zoneCy: Float) {
        dismissTuningBox()
        hideHomeForTune()
        tuneZoneId = zoneId
        tuneDisplayLabel = label
        isTuningStick = false
        val screenW = (configRoot?.width?.takeIf  { it > 0 } ?: dm.widthPixels).toFloat()
        val screenH = (configRoot?.height?.takeIf { it > 0 } ?: dm.heightPixels).toFloat()
        val boxW = minOf(dp(320), (screenW * 0.88f).toInt())

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 0, 0, 0))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#00BFFF"))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = 14f
        }

        // Title row
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        titleRow.addView(TextView(context).apply {
            text = "TUNE \u2014 $label"
            textSize = 16f
            setTextColor(Color.parseColor("#00BFFF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(context).apply {
            text = "\u2715"
            textSize = 18f
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(10), dp(8), dp(6), dp(8))
            setOnClickListener { dismissTuningBox() }
        })
        box.addView(titleRow)

        // Content placeholder — rebuilt by refreshTuningContent()
        val contentHolder = FrameLayout(context)
        box.addView(contentHolder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Consume touches inside the box so they don't bubble up to zoneLayer and close the box.
        // Children still receive their events first; this only catches taps on non-interactive areas.
        box.setOnTouchListener { _, _ -> true }

        val lp = FrameLayout.LayoutParams(boxW, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = ((screenW - boxW) / 2f).toInt().coerceAtLeast(0)
        lp.topMargin = ((screenH - dp(280)) / 2f).toInt().coerceAtLeast(dp(24))
        zoneLayer?.addView(box, lp)
        tuningBoxView = box

        // Fill content now that the view is in the hierarchy
        contentHolder.addView(buildTuningContent())
    }

    private fun refreshTuningContent() {
        // Defer to the next main-thread iteration so this never runs during a touch dispatch.
        // Calling removeAllViews() synchronously inside a touch listener removes the view
        // currently processing the event mid-dispatch, which crashes the view system.
        handler.post {
            val box = tuningBoxView as? LinearLayout ?: return@post
            val holder = box.getChildAt(1) as? FrameLayout ?: return@post
            holder.removeAllViews()
            holder.addView(if (isTuningStick) buildStickTuningContent() else buildTuningContent())
        }
    }

    private fun buildTuningContent(): View {
        val zone = editingZones.find { it.id == tuneZoneId }
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val turboRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        turboRow.addView(TextView(context).apply {
            text = "TURBO"
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        listOf("OFF", "ON").forEach { option ->
            val on = (option == "ON") == (zone?.turbo == true)
            turboRow.addView(TextView(context).apply {
                text = option
                textSize = 13f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(if (on) Color.BLACK else Color.parseColor("#FFB300"))
                background = if (on) GradientDrawable().apply {
                    setColor(Color.parseColor("#FFB300"))
                    cornerRadius = dp(4).toFloat()
                } else GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), Color.parseColor("#FFB300"))
                    cornerRadius = dp(4).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }
                setOnClickListener {
                    zone?.turbo = option == "ON"
                    zoneViews[tuneZoneId]?.invalidate()
                    refreshTuningContent()
                }
            })
        }
        container.addView(turboRow)

        if (zone?.turbo == true) {
            container.addView(stepperRow(
                label = "DURATION",
                getValue = { fmtMs(ButtonTuningStore.get(tuneZoneId).tapDurationMs) },
                minus = { amount ->
                    ButtonTuningStore.get(tuneZoneId).tapDurationMs =
                        (ButtonTuningStore.get(tuneZoneId).tapDurationMs - 10L * amount).coerceAtLeast(16L)
                },
                plus = { amount ->
                    ButtonTuningStore.get(tuneZoneId).tapDurationMs =
                        (ButtonTuningStore.get(tuneZoneId).tapDurationMs + 10L * amount).coerceAtMost(500L)
                }
            ))
            container.addView(stepperRow(
                label = "INTERVAL",
                getValue = { fmtMs(ButtonTuningStore.get(tuneZoneId).repeatIntervalMs) },
                minus = { amount ->
                    ButtonTuningStore.get(tuneZoneId).repeatIntervalMs =
                        (ButtonTuningStore.get(tuneZoneId).repeatIntervalMs - 25L * amount).coerceAtLeast(40L)
                },
                plus = { amount ->
                    ButtonTuningStore.get(tuneZoneId).repeatIntervalMs =
                        (ButtonTuningStore.get(tuneZoneId).repeatIntervalMs + 25L * amount).coerceAtMost(1000L)
                }
            ))
        }

        // Reset
        val resetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        resetRow.addView(TextView(context).apply {
            text = "RESET"
            textSize = 10f
            setTextColor(Color.parseColor("#CC3333"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = outlineDrawable(Color.parseColor("#CC3333"))
            setOnClickListener {
                ButtonTuningStore.reset(tuneZoneId)
                editingZones.find { it.id == tuneZoneId }?.turbo = false
                zoneViews[tuneZoneId]?.invalidate()
                refreshTuningContent()
            }
        })
        container.addView(resetRow)

        return container
    }

    // Formats a millisecond value as "Xs" (≥1000ms) or "Xms" (<1000ms).
    private fun fmtMs(ms: Long): String =
        if (ms >= 1000L) "${"%.1f".format(ms / 1000.0)}s" else "${ms}ms"

    // getValue is called after each action to refresh the displayed value in place —
    // no view rebuild needed, so press-and-hold repeat is never cancelled by a layout change.
    private fun stepperRow(
        label: String,
        getValue: () -> String,
        minus: (Int) -> Unit,
        plus: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }

        row.addView(TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        val valueLabel = TextView(context).apply {
            text = getValue()
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Acceleration: 1× for 0–1.5 s, 10× after 1.5 s, 100× after 3 s. Repeat every 80 ms.
        // tuningRepeatRunnable lives on the OverlayManager — not inside the view — so view
        // detachment (ACTION_CANCEL from removeAllViews) cannot kill a live hold gesture.
        fun makeBtn(icon: String, action: (Int) -> Unit): TextView = TextView(context).apply {
            text = icon
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = outlineDrawable(Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))

            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val pressStart = System.currentTimeMillis()
                        action(1)
                        valueLabel.text = getValue()
                        tuningRepeatRunnable?.let { handler.removeCallbacks(it) }
                        tuningRepeatRunnable = object : Runnable {
                            override fun run() {
                                val held = System.currentTimeMillis() - pressStart
                                val amount = when {
                                    held > 3000L -> 100
                                    held > 1500L -> 10
                                    else         -> 1
                                }
                                action(amount)
                                valueLabel.text = getValue()
                                handler.postDelayed(this, 80L)
                            }
                        }
                        handler.postDelayed(tuningRepeatRunnable!!, 400L)
                        v.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tuningRepeatRunnable?.let { handler.removeCallbacks(it) }
                        tuningRepeatRunnable = null
                        v.isPressed = false
                        true
                    }
                    else -> false
                }
            }
        }

        row.addView(makeBtn("\u2013") { minus(it) })
        row.addView(valueLabel)
        row.addView(makeBtn("+") { plus(it) })
        return row
    }

    // ─── Stick tuning box ─────────────────────────────────────────────────────

    private fun showStickTuningBox(zoneId: String, label: String, zoneCx: Float, zoneCy: Float) {
        dismissTuningBox()
        hideHomeForTune()
        stickTuneZoneId = zoneId
        stickTuneDisplayLabel = label
        isTuningStick = true
        val screenW = (configRoot?.width?.takeIf  { it > 0 } ?: dm.widthPixels).toFloat()
        val screenH = (configRoot?.height?.takeIf { it > 0 } ?: dm.heightPixels).toFloat()
        val boxW = minOf(dp(320), (screenW * 0.88f).toInt())

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 0, 0, 0))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#7B2FBE"))  // purple border to distinguish from button tuning
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = 14f
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        titleRow.addView(TextView(context).apply {
            text = "STICK TUNE \u2014 $label"
            textSize = 16f
            setTextColor(Color.parseColor("#BB88FF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(context).apply {
            text = "\u2715"
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(6), dp(4), dp(2), dp(4))
            setOnClickListener { dismissTuningBox() }
        })
        box.addView(titleRow)

        val contentHolder = FrameLayout(context)
        box.addView(contentHolder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Consume touches inside the box so they don't bubble up to zoneLayer and close the box.
        box.setOnTouchListener { _, _ -> true }

        val lp = FrameLayout.LayoutParams(boxW, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = ((screenW - boxW) / 2f).toInt().coerceAtLeast(0)
        lp.topMargin = ((screenH - dp(300)) / 2f).toInt().coerceAtLeast(dp(24))
        zoneLayer?.addView(box, lp)
        tuningBoxView = box

        contentHolder.addView(buildStickTuningContent())
    }

    private fun buildStickTuningContent(): View {
        val tuning = ButtonTuningStore.getStick(stickTuneZoneId)
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        container.addView(stepperRow(
            label = "SENS",
            getValue = { "${(ButtonTuningStore.getStick(stickTuneZoneId).sensitivityPct * 100).toInt()}%" },
            minus = { amount ->
                val cur = ButtonTuningStore.getStick(stickTuneZoneId).sensitivityPct
                ButtonTuningStore.getStick(stickTuneZoneId).sensitivityPct =
                    (Math.round((cur - 0.1f * amount) * 10) / 10f).coerceAtLeast(0.1f)
            },
            plus = { amount ->
                val cur = ButtonTuningStore.getStick(stickTuneZoneId).sensitivityPct
                ButtonTuningStore.getStick(stickTuneZoneId).sensitivityPct =
                    (Math.round((cur + 0.1f * amount) * 10) / 10f).coerceAtMost(3.0f)
            }
        ))

        val invertRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        invertRow.addView(TextView(context).apply {
            text = "INVERT Y"
            textSize = 10f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        listOf("OFF", "ON").forEach { option ->
            val isActive = (option == "ON") == ButtonTuningStore.getStick(stickTuneZoneId).invertY
            invertRow.addView(TextView(context).apply {
                text = option
                textSize = 10f
                setPadding(dp(7), dp(4), dp(7), dp(4))
                setTextColor(if (isActive) Color.BLACK else Color.parseColor("#BB88FF"))
                background = if (isActive) GradientDrawable().apply {
                    setColor(Color.parseColor("#BB88FF"))
                    cornerRadius = dp(4).toFloat()
                } else GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), Color.parseColor("#BB88FF"))
                    cornerRadius = dp(4).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }
                setOnClickListener {
                    ButtonTuningStore.getStick(stickTuneZoneId).invertY = option == "ON"
                    refreshTuningContent()
                }
            })
        }
        container.addView(invertRow)

        // DEBUG overlay toggle row
        val debugRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(2) }
        }
        debugRow.addView(TextView(context).apply {
            text = "DEBUG"
            textSize = 10f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        listOf("OFF", "ON").forEach { option ->
            val isActive = (option == "ON") == ButtonTuningStore.getStick(stickTuneZoneId).showDebug
            debugRow.addView(TextView(context).apply {
                text = option
                textSize = 10f
                setPadding(dp(7), dp(4), dp(7), dp(4))
                setTextColor(if (isActive) Color.BLACK else Color.parseColor("#BB88FF"))
                background = if (isActive) GradientDrawable().apply {
                    setColor(Color.parseColor("#BB88FF"))
                    cornerRadius = dp(4).toFloat()
                } else GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), Color.parseColor("#BB88FF"))
                    cornerRadius = dp(4).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }
                setOnClickListener {
                    val show = option == "ON"
                    ButtonTuningStore.getStick(stickTuneZoneId).showDebug = show
                    if (show) showDebugOverlay(stickTuneDisplayLabel)
                    else hideDebugOverlay(stickTuneDisplayLabel)
                    refreshTuningContent()
                }
            })
        }
        container.addView(debugRow)

        // Reset
        val resetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        resetRow.addView(TextView(context).apply {
            text = "RESET"
            textSize = 10f
            setTextColor(Color.parseColor("#CC3333"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = outlineDrawable(Color.parseColor("#CC3333"))
            setOnClickListener {
                ButtonTuningStore.resetStick(stickTuneZoneId)
                hideDebugOverlay(stickTuneDisplayLabel)
                refreshTuningContent()
            }
        })
        container.addView(resetRow)

        return container
    }

    private fun outlineDrawable(stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(Color.TRANSPARENT)
        setStroke(dp(1), stroke); cornerRadius = dp(6).toFloat()
    }

    // ─── Stick Debug Overlay ──────────────────────────────────────────────────

    /**
     * Called from masterTick on the Dispatchers.Default thread.
     * Posts to main thread to update the DebugStickView for the given stick label.
     * @param label   The stick label (e.g. "LEFT_STICK")
     * @param touchX  Current gesture endpoint X in screen pixels
     * @param touchY  Current gesture endpoint Y in screen pixels
     * @param axisX   Raw axis X value [-1..1] after dead-zone
     * @param axisY   Raw axis Y value [-1..1] after dead-zone
     * @param active  true = stick held; false = released (reset dot to centre)
     */
    fun updateStickDebug(label: String, touchX: Float, touchY: Float,
                         axisX: Float, axisY: Float, active: Boolean) {
        val view = debugViews[label] ?: return
        handler.post { view.update(touchX, touchY, axisX, axisY, active) }
    }

    private fun showDebugOverlay(label: String) {
        // Find the zone data for this stick
        val zone = editingZones.find { it.inputName == label && it.isStick } ?: return
        if (debugViews.containsKey(label)) return  // already showing

        val size = debugViewSize(zone.outerRadius)
        val view = DebugStickView(context, zoneCx = zone.cx, zoneCy = zone.cy,
            radius = zone.outerRadius, deadZoneFraction = zone.deadZone)
        debugViews[label] = view

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (zone.cx - size / 2f).toInt()
            y = (zone.cy - size / 2f).toInt()
        }
        debugParams[label] = params
        runCatching { wm.addView(view, params) }
    }

    private fun hideDebugOverlay(label: String) {
        debugParams.remove(label)
        val view = debugViews.remove(label) ?: return
        runCatching { wm.removeView(view) }
    }

    private fun removeAllDebugViews() {
        debugViews.keys.toList().forEach { hideDebugOverlay(it) }
    }

    // Called from ZoneCircleView when a stick zone is moved or resized.
    internal fun repositionDebugOverlay(label: String, cx: Float, cy: Float, outerRadius: Float) {
        val view = debugViews[label] ?: return
        val params = debugParams[label] ?: return
        val newSize = debugViewSize(outerRadius)
        view.zoneCx = cx; view.zoneCy = cy
        view.updateZone(outerRadius)
        params.width = newSize; params.height = newSize
        params.x = (cx - newSize / 2f).toInt()
        params.y = (cy - newSize / 2f).toInt()
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun debugViewSize(outerRadius: Float) = (outerRadius * 2f + dp(48)).toInt()

    @SuppressLint("ViewConstructor")
    inner class DebugStickView(
        context: Context,
        // Zone center in screen coordinates — updated by repositionDebugOverlay when zone is moved
        var zoneCx: Float = 0f,
        var zoneCy: Float = 0f,
        var radius: Float,
        var deadZoneFraction: Float
    ) : View(context) {

        private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#80BB88FF")
        }
        private val paintDeadZone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.parseColor("#50BB88FF")
        }
        private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.parseColor("#50FFFFFF")
        }
        private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#CCBB88FF")
        }
        private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FFBB88FF")
        }
        private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f
            color = Color.parseColor("#CCFFFFFF")
            textAlign = Paint.Align.CENTER
        }

        private val pad = 12f
        private var localCx = 0f
        private var localCy = 0f

        private var dotX = 0f
        private var dotY = 0f
        private var axisXVal = 0f
        private var axisYVal = 0f
        private var isActive = false

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            localCx = w / 2f
            localCy = h / 2f
            dotX = localCx
            dotY = localCy
        }

        // Called by repositionDebugOverlay when zone is resized.
        fun updateZone(newRadius: Float) {
            radius = newRadius
            dotX = localCx; dotY = localCy
            invalidate()
        }

        fun update(touchX: Float, touchY: Float, axisX: Float, axisY: Float, active: Boolean) {
            // Convert screen-space gesture endpoint to view-local coordinates.
            // The window is centred on zoneCx/zoneCy, so the offset is direct.
            dotX = localCx + (touchX - zoneCx)
            dotY = localCy + (touchY - zoneCy)
            axisXVal = axisX
            axisYVal = axisY
            isActive = active
            if (!active) { dotX = localCx; dotY = localCy }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawCircle(localCx, localCy, radius, paintBorder)
            canvas.drawCircle(localCx, localCy, radius * deadZoneFraction, paintDeadZone)
            canvas.drawLine(localCx - radius, localCy, localCx + radius, localCy, paintCross)
            canvas.drawLine(localCx, localCy - radius, localCx, localCy + radius, paintCross)
            if (isActive) canvas.drawLine(localCx, localCy, dotX, dotY, paintLine)
            canvas.drawCircle(dotX, dotY, if (isActive) 8f else 5f, paintDot)
            val text = "X:${String.format("%.2f", axisXVal)}  Y:${String.format("%.2f", axisYVal)}"
            canvas.drawText(text, localCx, localCy + radius + pad + 20f, paintText)
        }
    }

    private fun dp(v: Int) = (v * dm.density).toInt()
}

/** M3DIA-style rotating/pulsing orange sweep on the overlay resize rim. */
private class OverlayGlowView(context: Context) : View(context) {
    private val accent = Color.parseColor("#FF8C00")
    private val stroke = 6f
    private val pad = stroke * 1.15f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
        strokeWidth = stroke
    }
    private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3600
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { invalidate() }
    }
    private val pulse = ValueAnimator.ofFloat(0.78f, 1f).apply {
        duration = 1700
        interpolator = AccelerateDecelerateInterpolator()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spin.start()
        pulse.start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val progress = spin.animatedValue as? Float ?: 0f
        val p = pulse.animatedValue as? Float ?: 1f
        if (width <= pad * 2 + stroke || height <= pad * 2 + stroke) return
        val frame = RectF(pad, pad, width - pad, height - pad)
        val mid = blend(accent, 0.22f)
        val hot = blend(accent, 0.78f)
        val colors = intArrayOf(
            accent, mid, hot, Color.WHITE, hot, mid, accent,
            mid, hot, Color.WHITE, hot, mid, accent
        )
        val stops = floatArrayOf(
            0f, 0.16f, 0.21f, 0.24f, 0.27f, 0.32f, 0.42f,
            0.66f, 0.71f, 0.74f, 0.77f, 0.82f, 1f
        )
        val shader = SweepGradient(width / 2f, height / 2f, colors, stops)
        shader.setLocalMatrix(Matrix().apply { setRotate(progress * 360f, width / 2f, height / 2f) })
        paint.shader = shader
        paint.strokeWidth = stroke * 1.7f
        paint.alpha = (70 * p).toInt().coerceIn(0, 255)
        canvas.drawRect(frame, paint)
        paint.strokeWidth = stroke * 1.25f
        paint.alpha = (165 * p).toInt().coerceIn(0, 255)
        canvas.drawRect(frame, paint)
        paint.strokeWidth = stroke * 1.05f
        paint.alpha = 255
        canvas.drawRect(frame, paint)
        paint.strokeWidth = stroke * 0.7f
        canvas.drawRect(frame, paint)
        paint.shader = null
        paint.alpha = 255
    }

    private fun blend(color: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) + (255 - Color.red(color)) * t).toInt(),
            (Color.green(color) + (255 - Color.green(color)) * t).toInt(),
            (Color.blue(color) + (255 - Color.blue(color)) * t).toInt()
        )
    }
}
