package com.slickstax841.padmap.data

enum class ButtonMode(val label: String) {
    HOLD("HOLD"),
    TAP("TAP"),
    REPEAT("REPEAT")
}

// Per-zone gesture strategy.
// V11: repeated independent one-shot taps to simulate holds (no continueStroke).
// V12: repeated independent one-shot taps — same button mechanics as V11; for sticks,
//      discrete stroke from current position each tick (no continueStroke chain).
// V13: continueStroke chain (current/default behaviour).
enum class AndroidApiLevel(val label: String) {
    V11("11"),
    V12("12"),
    V13("13+")
}

// Values stored independently per mode — switching mode never overwrites another mode's values.
data class ModeSettings(
    var durationMs: Long = 10L,
    var sizePx: Float = 1f,
    var repeatIntervalMs: Long = 100L,
    var holdReleaseDurationMs: Long = 16L
)

data class ButtonTuning(
    var mode: ButtonMode = ButtonMode.TAP,
    var androidApiLevel: AndroidApiLevel = AndroidApiLevel.V11,
    val modeSettings: MutableMap<ButtonMode, ModeSettings> = ButtonMode.values()
        .associateWith { ModeSettings() }.toMutableMap()
) {
    private val s get() = modeSettings.getOrPut(mode) { ModeSettings() }

    // Delegate accessors — read/write always targets the currently active mode's settings.
    var tapDurationMs: Long
        get() = s.durationMs
        set(v) { s.durationMs = v }
    var tapSizePx: Float
        get() = s.sizePx
        set(v) { s.sizePx = v }
    var repeatIntervalMs: Long
        get() = s.repeatIntervalMs
        set(v) { s.repeatIntervalMs = v }
    var holdReleaseDurationMs: Long
        get() = s.holdReleaseDurationMs
        set(v) { s.holdReleaseDurationMs = v }
}

data class StickTuning(
    var speedMs: Long = 50L,
    var sensitivityPct: Float = 1.0f,
    var androidApiLevel: AndroidApiLevel = AndroidApiLevel.V13,
    var showDebug: Boolean = false,
    // Latch: ignore releases shorter than this (ms below dead zone before chain drops). 0 = off.
    var latchMs: Long = 0L,
    // Coast: continue in last known direction for this many ms after a confirmed release. 0 = off.
    var coastMs: Long = 0L
)

object ButtonTuningStore {
    // In-memory only — resets each session. Purely for experimentation.
    val configs = mutableMapOf<String, ButtonTuning>()
    val stickConfigs = mutableMapOf<String, StickTuning>()

    fun get(zoneId: String) = configs.getOrPut(zoneId) { ButtonTuning() }
    fun reset(zoneId: String) { configs[zoneId] = ButtonTuning() }

    fun getStick(zoneId: String) = stickConfigs.getOrPut(zoneId) { StickTuning() }
    fun resetStick(zoneId: String) { stickConfigs[zoneId] = StickTuning() }

    // Apply LT/RT-specific defaults to a zone that has not been tuned yet.
    // Called when a zone is assigned LT or RT, and when saved LT/RT zones are loaded.
    // No-op if the zone already has a tuning entry (user-set values are preserved).
    fun initForTrigger(zoneId: String) {
        if (configs.containsKey(zoneId)) return
        configs[zoneId] = ButtonTuning(
            mode = ButtonMode.HOLD,
            androidApiLevel = AndroidApiLevel.V11,
            modeSettings = ButtonMode.values().associateWith { mode ->
                if (mode == ButtonMode.HOLD)
                    ModeSettings(durationMs = 10L, sizePx = 1f, holdReleaseDurationMs = 6L)
                else
                    ModeSettings()
            }.toMutableMap()
        )
    }
}
