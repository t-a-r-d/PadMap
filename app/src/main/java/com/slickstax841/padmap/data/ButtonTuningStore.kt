package com.slickstax841.padmap.data

enum class ButtonMode(val label: String) {
    HOLD("HOLD"),
    TAP("TAP"),
    REPEAT("REPEAT")
}

data class ModeSettings(
    var durationMs: Long = 32L,
    var repeatIntervalMs: Long = 100L
)

data class ButtonTuning(
    var mode: ButtonMode = ButtonMode.TAP,
    val modeSettings: MutableMap<ButtonMode, ModeSettings> = ButtonMode.values()
        .associateWith { ModeSettings() }.toMutableMap()
) {
    private val s get() = modeSettings.getOrPut(mode) { ModeSettings() }

    var tapDurationMs: Long
        get() = s.durationMs
        set(v) { s.durationMs = v }
    var repeatIntervalMs: Long
        get() = s.repeatIntervalMs
        set(v) { s.repeatIntervalMs = v }
}

data class StickTuning(
    var lookSpeedPx: Float = 5f,
    var sensitivityPct: Float = 1.0f,
    var showDebug: Boolean = false
)

object ButtonTuningStore {
    val configs = mutableMapOf<String, ButtonTuning>()
    val stickConfigs = mutableMapOf<String, StickTuning>()

    fun get(zoneId: String) = configs.getOrPut(zoneId) { ButtonTuning() }
    fun reset(zoneId: String) { configs[zoneId] = ButtonTuning() }

    fun getStick(zoneId: String) = stickConfigs.getOrPut(zoneId) { StickTuning() }
    fun resetStick(zoneId: String) { stickConfigs[zoneId] = StickTuning() }

    fun initForTrigger(zoneId: String) {
        if (configs.containsKey(zoneId)) return
        configs[zoneId] = ButtonTuning(mode = ButtonMode.HOLD)
    }
}
