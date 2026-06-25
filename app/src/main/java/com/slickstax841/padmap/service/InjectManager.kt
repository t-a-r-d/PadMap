package com.slickstax841.padmap.service

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process touch injection via InputManager.injectInputEvent() accessed through
 * HiddenApiBypass. No separate privileged app required.
 *
 * Manages the full Android multi-touch pointer lifecycle:
 *   pointerDown  → ACTION_DOWN (first pointer) or ACTION_POINTER_DOWN (additional)
 *   pointerMove  → ACTION_MOVE covering all currently active pointers
 *   batchUpdate  → update multiple positions then send one combined ACTION_MOVE
 *   pointerUp    → ACTION_POINTER_UP (non-last) or ACTION_UP (last pointer)
 *
 * All active pointers are included in every ACTION_MOVE event, matching the native
 * Android multi-touch protocol that games expect.
 *
 * TOOL_TYPE_FINGER is set on every pointer so games that call getToolType() see a
 * genuine finger event. dispatchGesture() cannot do this; injectInputEvent() can.
 */
object InjectManager {

    /** True once hidden InputManager methods have been resolved. Set once at app start. */
    var isAvailable: Boolean = false
        private set

    // ── Reflection cache ────────────────────────────────────────────────────────

    private var injectMethod: Method? = null
    private var inputManagerInstance: Any? = null

    // ── Active pointer tracking ─────────────────────────────────────────────────

    private data class Pointer(var x: Float, var y: Float, val downTime: Long)

    // ConcurrentHashMap: pointerDown/Up called from coroutines; stick loop on main thread.
    private val activePointers = ConcurrentHashMap<Int, Pointer>()

    // ── Initialisation ──────────────────────────────────────────────────────────

    /**
     * Call from Application.onCreate() AFTER HiddenApiBypass.addHiddenApiExemptions("").
     * Resolves the hidden InputManager API and sets isAvailable.
     */
    fun init() {
        try {
            val imClass = android.hardware.input.InputManager::class.java
            val getInstance = imClass.getDeclaredMethod("getInstance")
                .also { it.isAccessible = true }
            inputManagerInstance = getInstance.invoke(null)
            injectMethod = imClass.getDeclaredMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).also { it.isAccessible = true }
            isAvailable = true
        } catch (_: Throwable) {
            isAvailable = false
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────────

    private fun inject(event: MotionEvent) {
        injectMethod?.invoke(inputManagerInstance, event, 0)  // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
    }

    /**
     * Build a MotionEvent from the current activePointers snapshot.
     * actionPointerId identifies which pointer is being added/removed
     * (only used for ACTION_POINTER_DOWN / ACTION_POINTER_UP encoding).
     */
    private fun buildEvent(action: Int, actionPointerId: Int = -1): MotionEvent {
        val sorted = activePointers.entries.sortedBy { it.key }
        val count  = sorted.size
        val encodedAction = when (action) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val idx = sorted.indexOfFirst { it.key == actionPointerId }.coerceAtLeast(0)
                (idx shl 8) or action
            }
            else -> action
        }
        val props  = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id       = sorted[i].key
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x        = sorted[i].value.x
                y        = sorted[i].value.y
                pressure = 1f
                size     = 1f
            }
        }
        val firstDown = sorted.minOf { it.value.downTime }
        return MotionEvent.obtain(
            firstDown, SystemClock.uptimeMillis(),
            encodedAction, count, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    fun pointerDown(id: Int, x: Float, y: Float): Boolean {
        if (!isAvailable) return false
        return try {
            val downTime = SystemClock.uptimeMillis()
            val isFirst  = activePointers.isEmpty()
            activePointers[id] = Pointer(x, y, downTime)
            val event = buildEvent(
                if (isFirst) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN,
                id
            )
            inject(event); event.recycle()
            true
        } catch (_: Throwable) { false }
    }

    fun pointerMove(id: Int, x: Float, y: Float): Boolean {
        if (!isAvailable) return false
        return try {
            activePointers[id]?.apply { this.x = x; this.y = y } ?: return false
            val event = buildEvent(MotionEvent.ACTION_MOVE)
            inject(event); event.recycle()
            true
        } catch (_: Throwable) { false }
    }

    /**
     * Update multiple pointer positions and send a single combined ACTION_MOVE.
     * More efficient than calling pointerMove() for each stick individually.
     */
    fun batchUpdate(updates: Map<Int, Pair<Float, Float>>): Boolean {
        if (!isAvailable || activePointers.isEmpty()) return false
        return try {
            for ((id, pos) in updates) {
                activePointers[id]?.apply { x = pos.first; y = pos.second }
            }
            val event = buildEvent(MotionEvent.ACTION_MOVE)
            inject(event); event.recycle()
            true
        } catch (_: Throwable) { false }
    }

    fun pointerUp(id: Int, x: Float, y: Float): Boolean {
        if (!isAvailable) return false
        return try {
            activePointers[id]?.apply { this.x = x; this.y = y } ?: return false
            val isLast = activePointers.size == 1
            val event  = buildEvent(
                if (isLast) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP,
                id
            )
            activePointers.remove(id)
            inject(event); event.recycle()
            true
        } catch (_: Throwable) { false }
    }

    /**
     * Release all active pointers cleanly. Call on service destroy or layout change
     * to avoid leaving ghost touch points in the game.
     */
    fun releaseAll() {
        if (!isAvailable || activePointers.isEmpty()) return
        try {
            val ids = activePointers.keys.sorted().reversed()
            for (id in ids) {
                activePointers[id] ?: continue
                val isLast = activePointers.size == 1
                val event  = buildEvent(
                    if (isLast) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP,
                    id
                )
                activePointers.remove(id)
                inject(event); event.recycle()
            }
        } catch (_: Throwable) {
            activePointers.clear()
        }
    }
}
