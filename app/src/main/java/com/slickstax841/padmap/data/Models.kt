package com.slickstax841.padmap.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ControllerPreset(
    val id: String,
    val name: String = "",
    val deviceName: String = "",
    val buttons: Map<Int, String> = emptyMap(),
    val axes: Map<Int, String> = emptyMap(),
    val isGamepad: Boolean = false
)

@Serializable
data class GameLayout(
    val id: String,
    val name: String = "",
    val packageName: String = "",
    val controllerPresetId: String = "",
    val mappings: List<MappingEntry> = emptyList(),
    // Uninstalled games stay here so zones come back if the user reinstalls.
    val archived: Boolean = false
)

@Serializable
data class MappingEntry(
    val inputName: String,
    val action: TouchAction,
    val turbo: Boolean = false,
    // When false, PadMap does not intercept this input — the game keeps its own mapping.
    val overrideGame: Boolean = true,
    // Stable identifier for the zone this entry belongs to. Tuning is keyed by this ID,
    // not by inputName — so reassigning a zone to a different button preserves its tuning.
    val zoneId: String = ""
)

@Serializable
sealed class TouchAction {
    @Serializable
    @SerialName("tap")
    data class Tap(val x: Float, val y: Float) : TouchAction()

    @Serializable
    @SerialName("drag")
    data class Drag(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val deadZone: Float = 0.15f,
        // false = Move (position-based: touch held at offset from center)
        // true  = Look (velocity-based: touch sweeps across zone, camera responds to movement)
        val lookMode: Boolean = false
    ) : TouchAction()
}

@Serializable
data class AppData(
    val controllerPresets: List<ControllerPreset> = emptyList(),
    val gameLayouts: List<GameLayout> = emptyList(),
    val activeLayoutId: String = "",
    val activePresetId: String = "",
    val skinId: String = "default",
    // Saved position of the config overlay panel (close/save/clear box).
    // null = not yet moved by user; panel defaults to screen centre on first open.
    val panelX: Int? = null,
    val panelY: Int? = null,
    // Saved overlay window position and size from the overlay adjust tool.
    // null = not yet adjusted; overlay defaults to MATCH_PARENT.
    val overlayX: Int? = null,
    val overlayY: Int? = null,
    val overlayW: Int? = null,
    val overlayH: Int? = null
)
