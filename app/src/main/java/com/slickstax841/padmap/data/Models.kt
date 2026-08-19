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
data class LayerBind(
    val index: Int = 1,
    val activateName: String = "",
    val deactivateName: String = ""
)

@Serializable
data class GameLayout(
    val id: String,
    val name: String = "",
    val packageName: String = "",
    val controllerPresetId: String = "",
    val mappings: List<MappingEntry> = emptyList(),
    val layerBinds: List<LayerBind> = emptyList(),
    // Uninstalled games stay here so zones come back if the user reinstalls.
    val archived: Boolean = false,
    val iconX: Int? = null,
    val iconY: Int? = null
)

@Serializable
data class MappingEntry(
    val inputName: String,
    val action: TouchAction,
    val turbo: Boolean = false,
    // Stable identifier for the zone this entry belongs to. Tuning is keyed by this ID,
    // not by inputName — so reassigning a zone to a different button preserves its tuning.
    val zoneId: String = "",
    val layer: Int = 1,
    // Non-blank: this tap is driven by that stick zone, not a pad button.
    val parentZoneId: String = ""
)

@Serializable
sealed class TouchAction {
    @Serializable
    @SerialName("tap")
    data class Tap(val x: Float, val y: Float, val radius: Float = 32f) : TouchAction()

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
    // Saved position of the zone-options cluster. null = overlay centre.
    val optionsX: Int? = null,
    val optionsY: Int? = null,
    // Saved overlay window position and size from the overlay adjust tool.
    // null = not yet adjusted; overlay defaults to MATCH_PARENT.
    val overlayX: Int? = null,
    val overlayY: Int? = null,
    val overlayW: Int? = null,
    val overlayH: Int? = null,
    val overlayMode: String = "auto",
    val landX: Int? = null,
    val landY: Int? = null,
    val landW: Int? = null,
    val landH: Int? = null,
    val portX: Int? = null,
    val portY: Int? = null,
    val portW: Int? = null,
    val portH: Int? = null,
    val buttonZoneRadius: Float = 32f
)

fun GameLayout.resolvedBinds(): List<LayerBind> {
    val by = layerBinds.associateBy { it.index }
    return (1..6).map { by[it] ?: LayerBind(it) }
}

fun GameLayout.withBind(index: Int, transform: (LayerBind) -> LayerBind): GameLayout {
    val next = resolvedBinds().map { if (it.index == index) transform(it) else it }
    return copy(layerBinds = next)
}
