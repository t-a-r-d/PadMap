package com.slickstax841.padmap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.slickstax841.padmap.R
import com.slickstax841.padmap.data.ControllerPreset
import com.slickstax841.padmap.data.DataStore
import com.slickstax841.padmap.data.GameLayout
import com.slickstax841.padmap.data.GameScanner
import com.slickstax841.padmap.inject.SidecarClient
import com.slickstax841.padmap.service.OverlayManager
import com.slickstax841.padmap.service.PadMapAccessibilityService
import com.slickstax841.padmap.ui.theme.*
import java.util.UUID

@Composable
fun HomeScreen(onEditPreset: (String) -> Unit, onEditLayout: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val appData by DataStore.data.collectAsState()
    val skin = LocalAppSkin.current

    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var hasA11y by remember { mutableStateOf(isA11yEnabled(ctx)) }
    var hasInjector by remember { mutableStateOf(SidecarClient.isAvailable) }
    // Scan for a connected gamepad, build a preset from its reported capabilities, and save it.
    // Called on every resume so a newly connected controller is picked up automatically.
    fun scanAndSaveController() {
        val im = ctx.getSystemService(InputManager::class.java)
        val device = im.inputDeviceIds.toList()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { d ->
                d.sources and InputDevice.SOURCE_GAMEPAD != 0 ||
                d.sources and InputDevice.SOURCE_JOYSTICK != 0
            } ?: return

        // Check each known button keycode against what the device reports
        val knownCodes = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT
        )
        val knownLabels = arrayOf("A","B","X","Y","LB","RB","LT","RT","L3","R3","Start","Select")
        val found = device.hasKeys(*knownCodes)
        val buttons = mutableMapOf<Int, String>()
        knownCodes.forEachIndexed { i, code -> if (found[i]) buttons[code] = knownLabels[i] }

        // Accept SOURCE_JOYSTICK (sticks) and SOURCE_GAMEPAD (D-pad HAT on some controllers)
        val joystickRanges = device.motionRanges.filter {
            (it.source and InputDevice.SOURCE_JOYSTICK != 0) ||
            (it.source and InputDevice.SOURCE_GAMEPAD != 0)
        }

        // D-pad: derive from HAT axes which are always present on controllers with a D-pad
        val hasHatX = joystickRanges.any { it.axis == MotionEvent.AXIS_HAT_X }
        val hasHatY = joystickRanges.any { it.axis == MotionEvent.AXIS_HAT_Y }
        if (hasHatX) { buttons[KeyEvent.KEYCODE_DPAD_LEFT] = "D-Left"; buttons[KeyEvent.KEYCODE_DPAD_RIGHT] = "D-Right" }
        if (hasHatY) { buttons[KeyEvent.KEYCODE_DPAD_UP] = "D-Up"; buttons[KeyEvent.KEYCODE_DPAD_DOWN] = "D-Down" }

        // Analog axes — exclude HAT (mapped as buttons above) and triggers (handled as buttons)
        val skipAxes = setOf(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER)
        val axisNames = mapOf(
            MotionEvent.AXIS_X to "L-Stick X", MotionEvent.AXIS_Y to "L-Stick Y",
            MotionEvent.AXIS_Z to "R-Stick X", MotionEvent.AXIS_RZ to "R-Stick Y"
        )
        val axes = mutableMapOf<Int, String>()
        joystickRanges.forEach { r ->
            if (r.axis !in skipAxes) axes[r.axis] = axisNames[r.axis] ?: "Axis ${r.axis}"
        }

        // Deterministic ID: same controller always maps to the same preset slot
        val id = "auto_${device.vendorId}_${device.productId}"
        // Use the Bluetooth device name when available — it's the user-visible name the
        // user gave the device (e.g. "My Xbox Controller") rather than the HID descriptor name.
        val displayName = bluetoothNameFor(ctx, device) ?: device.name
        val preset = ControllerPreset(id = id, name = displayName, deviceName = device.name,
            buttons = buttons, axes = axes, isGamepad = true)
        DataStore.update { data ->
            val existing = data.controllerPresets.find { it.id == id }
            val updatedPresets = when {
                existing == null ->
                    // First time seeing this controller — add it
                    data.controllerPresets + preset
                existing.axes.isEmpty() && axes.isNotEmpty() ->
                    // Preset was created before stick/D-pad detection was fixed — update it now
                    data.controllerPresets.map { if (it.id == id) preset else it }
                else ->
                    // Preset already has axes — leave user edits untouched
                    data.controllerPresets
            }
            data.copy(controllerPresets = updatedPresets, activePresetId = id)
        }
    }

    // Re-check permissions every time the screen resumes (user returns from Settings)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val newOverlay = Settings.canDrawOverlays(ctx)
                val newA11y = isA11yEnabled(ctx)
                if (newOverlay && !hasOverlay)
                    Toast.makeText(ctx, "Overlay permission granted", Toast.LENGTH_SHORT).show()
                if (newA11y && !hasA11y)
                    Toast.makeText(ctx, "Accessibility service enabled", Toast.LENGTH_SHORT).show()
                hasOverlay = newOverlay
                hasA11y = newA11y
                hasInjector = SidecarClient.ping()
                OverlayManager.instance?.repositionForHome()
                scanAndSaveController()
                GameScanner.scan(ctx)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    SkinBackground(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 20.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Top) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.padmap_logo),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("PAD MAP", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = skin.accent,
                                letterSpacing = 4.sp, fontFamily = skin.headingFont)
                            Text("gamepad \u2192 touch mapper", fontSize = 12.sp, color = skin.textSecondary)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            PadMapAccessibilityService.instance?.disableAndStop()
                            (ctx as? android.app.Activity)?.finishAndRemoveTask()
                        }) {
                            Text("EXIT", color = skin.textSecondary, fontSize = 11.sp)
                        }
                        TextButton(onClick = onSettings) {
                            Text("\u2699", fontSize = 22.sp, color = skin.textSecondary)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // Always show overlay restore button once permissions are granted —
            // the config icon can disappear from the game screen and this is the only way back
            if (hasOverlay && hasA11y) {
                item {
                    TextButton(
                        onClick = { OverlayManager.instance?.restartIcon() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(skin.activeItemBg)
                    ) {
                        Text("\u25CE  Show overlay config button", color = skin.accent, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            if (hasOverlay && hasA11y && !hasInjector) {
                item {
                    SectionLabel("INJECTOR")
                    Spacer(Modifier.height(6.dp))
                    PermCard(
                        label = "Wireless debugging injector",
                        instructions = "Settings \u2192 pair a 6-digit wireless debugging code. PadMap starts its own injector. Overlay mapping will not reach games until this is running."
                    ) { onSettings() }
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (!hasOverlay || !hasA11y) {
                item {
                    SectionLabel("PERMISSIONS")
                    Spacer(Modifier.height(6.dp))
                    if (!hasOverlay) {
                        PermCard(
                            label = "Display over other apps",
                            instructions = "Tap FIX \u2192 find PadMap in the list \u2192 toggle Allow."
                        ) {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        }
                    }
                    if (!hasA11y) {
                        if (!hasOverlay) Spacer(Modifier.height(6.dp))
                        PermCard(
                            label = "Accessibility service",
                            instructions = "First: Settings \u2192 Apps \u2192 PadMap \u2192 \u22EE menu \u2192 Allow restricted settings.\nThen tap FIX \u2192 Installed apps \u2192 PadMap \u2192 enable the toggle."
                        ) {
                            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                SectionLabel("ACTIVE CONFIG")
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(skin.surfaceCol).padding(14.dp), Arrangement.SpaceBetween) {
                    Column {
                        Text("Layout", fontSize = 11.sp, color = skin.textSecondary)
                        Text(appData.gameLayouts.find { it.id == appData.activeLayoutId && !it.archived }?.name ?: "None", color = skin.textPrimary, fontWeight = FontWeight.Medium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Controller", fontSize = 11.sp, color = skin.textSecondary)
                        Text(appData.controllerPresets.find { it.id == appData.activePresetId }?.name ?: "None", color = skin.textPrimary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    SectionLabel("CONTROLLER PRESETS")
                    TextButton(onClick = {
                        val id = UUID.randomUUID().toString()
                        DataStore.update { it.copy(controllerPresets = it.controllerPresets + ControllerPreset(id = id, name = "New Controller")) }
                        onEditPreset(id)
                    }) { Text("+ ADD", color = skin.accent, fontSize = 12.sp) }
                }
            }

            if (appData.controllerPresets.isEmpty()) item { EmptyHint("No presets. Tap + ADD to map your controller buttons.") }

            items(appData.controllerPresets) { preset ->
                ItemCard(
                    title = preset.name,
                    subtitle = "${preset.buttons.size} buttons \u00b7 ${preset.axes.size} axes" + if (preset.deviceName.isNotBlank()) " \u00b7 ${preset.deviceName}" else "",
                    isActive = preset.id == appData.activePresetId,
                    leadingIcon = if (preset.isGamepad) {{ GamepadIcon(36.dp) }} else null,
                    onSelect = { DataStore.update { it.copy(activePresetId = preset.id) } },
                    onEdit = { onEditPreset(preset.id) },
                    onDelete = { DataStore.update { it.copy(controllerPresets = it.controllerPresets.filter { p -> p.id != preset.id }) } }
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    SectionLabel("GAME LAYOUTS")
                    Row {
                        TextButton(onClick = {
                            val result = GameScanner.scan(ctx)
                            Toast.makeText(ctx, result.summary(), Toast.LENGTH_SHORT).show()
                        }) { Text("SCAN", color = skin.accent, fontSize = 12.sp) }
                        TextButton(onClick = {
                            val id = UUID.randomUUID().toString()
                            DataStore.update { it.copy(gameLayouts = it.gameLayouts + GameLayout(id = id, name = "New Game")) }
                            onEditLayout(id)
                        }) { Text("+ ADD", color = skin.accent, fontSize = 12.sp) }
                    }
                }
            }

            val liveLayouts = appData.gameLayouts.filter { !it.archived }
            val archivedLayouts = appData.gameLayouts.filter { it.archived }

            if (liveLayouts.isEmpty()) item { EmptyHint("No layouts. Tap SCAN or + ADD.") }

            items(liveLayouts, key = { it.id }) { layout ->
                ItemCard(
                    title = layout.name,
                    subtitle = "${layout.mappings.size} zones" + if (layout.packageName.isNotBlank()) " \u00b7 ${layout.packageName}" else "",
                    isActive = layout.id == appData.activeLayoutId,
                    leadingIcon = if (layout.packageName.isNotBlank()) {{ AppIconImage(layout.packageName, 48.dp) }} else null,
                    showEdit = false,
                    onSelect = { DataStore.update { it.copy(activeLayoutId = layout.id) } },
                    onEdit = { onEditLayout(layout.id) },
                    onDelete = { DataStore.update { it.copy(gameLayouts = it.gameLayouts.filter { l -> l.id != layout.id }) } }
                )
            }

            if (archivedLayouts.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    SectionLabel("ARCHIVE")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Uninstalled games. Layouts come back if you reinstall.",
                        fontSize = 12.sp,
                        color = skin.textSecondary
                    )
                }
                items(archivedLayouts, key = { it.id }) { layout ->
                    ItemCard(
                        title = layout.name,
                        subtitle = "archived \u00b7 ${layout.mappings.size} zones" +
                            if (layout.packageName.isNotBlank()) " \u00b7 ${layout.packageName}" else "",
                        isActive = false,
                        leadingIcon = null,
                        showEdit = false,
                        onSelect = {
                            Toast.makeText(ctx, "Reinstall the game to restore this layout", Toast.LENGTH_SHORT).show()
                        },
                        onEdit = {},
                        onDelete = {
                            DataStore.update { data ->
                                val next = data.gameLayouts.filter { l -> l.id != layout.id }
                                data.copy(
                                    gameLayouts = next,
                                    activeLayoutId = if (data.activeLayoutId == layout.id)
                                        next.firstOrNull { !it.archived }?.id ?: ""
                                    else data.activeLayoutId
                                )
                            }
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable private fun SectionLabel(text: String) {
    val skin = LocalAppSkin.current
    Text(text, fontSize = 11.sp, color = skin.textSecondary, letterSpacing = 2.sp, fontFamily = skin.labelFont)
}

@Composable
private fun PermCard(label: String, instructions: String, onFix: () -> Unit) {
    val skin = LocalAppSkin.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(skin.surfaceCol).padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("\u2715", color = Color(0xFFCC3333), fontSize = 18.sp)
                Text(label, color = skin.textPrimary)
            }
            TextButton(onClick = onFix) { Text("FIX", color = skin.accent, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Text(instructions, fontSize = 12.sp, color = skin.textSecondary, lineHeight = 18.sp)
    }
}

@Composable
private fun GamepadIcon(size: Dp) {
    val skin = LocalAppSkin.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(skin.surfaceVar),
        contentAlignment = Alignment.Center
    ) {
        Text("\u25CE", fontSize = (size.value * 0.5f).sp, color = skin.accent)
    }
}

@Composable
private fun AppIconImage(packageName: String, size: Dp) {
    val ctx = LocalContext.current
    val bitmap: ImageBitmap? = remember(packageName) {
        if (packageName.isBlank()) return@remember null
        try {
            val d = ctx.packageManager.getApplicationIcon(packageName)
            val bm = Bitmap.createBitmap(
                d.intrinsicWidth.coerceAtLeast(1),
                d.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bm)
            d.setBounds(0, 0, canvas.width, canvas.height)
            d.draw(canvas)
            bm.asImageBitmap()
        } catch (_: Exception) { null }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size))
    }
}

@Composable
private fun ItemCard(
    title: String,
    subtitle: String,
    isActive: Boolean,
    leadingIcon: (@Composable () -> Unit)? = null,
    showEdit: Boolean = true,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val skin = LocalAppSkin.current
    val borderMod = when {
        isActive && skin.neonGlow -> Modifier.neonGlow(skin.activeItemBorder)
        isActive -> Modifier.border(1.dp, skin.activeItemBorder, RoundedCornerShape(8.dp))
        else -> Modifier.border(1.dp, Color.Transparent, RoundedCornerShape(8.dp))
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) skin.activeItemBg else skin.surfaceCol)
            .then(borderMod)
            .padding(14.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f).clickable(onClick = onSelect)) {
            Text(title, color = skin.textPrimary, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = skin.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showEdit) TextButton(onClick = onEdit) { Text("EDIT", color = skin.accent, fontSize = 12.sp) }
            TextButton(onClick = onDelete) { Text("\u2715", color = Color(0xFFCC3333), fontSize = 14.sp) }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    val skin = LocalAppSkin.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(skin.surfaceVar).padding(20.dp), Alignment.Center) {
        Text(text, color = skin.textSecondary, fontSize = 13.sp)
    }
}

/**
 * Returns the Bluetooth alias/name for a gamepad [device] if it is paired via Bluetooth.
 * Matches against [BluetoothAdapter.getBondedDevices] by name since there is no direct
 * InputDevice → BluetoothDevice link in the public API.
 * Returns null if not Bluetooth, permission not granted, or no match found.
 */
@SuppressLint("MissingPermission")
private fun bluetoothNameFor(ctx: Context, device: InputDevice): String? {
    // BLUETOOTH_CONNECT is required on API 31+ to read bonded device info
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) return null
    val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
    val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return null
    val inputName = device.name ?: return null
    // Find a paired BT device whose name appears in (or matches) the InputDevice name
    val btDevice = bonded.firstOrNull { bt ->
        val btName = runCatching { bt.name }.getOrNull() ?: return@firstOrNull false
        btName.isNotBlank() && inputName.contains(btName, ignoreCase = true)
    } ?: return null
    // getAlias() (API 30+) is the user-set friendly name; fall back to the hardware name
    val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { btDevice.alias }.getOrNull()
    } else null
    return (alias?.takeIf { it.isNotBlank() } ?: runCatching { btDevice.name }.getOrNull())
        ?.takeIf { it.isNotBlank() }
}

private fun isA11yEnabled(ctx: Context): Boolean {
    // Fast path: service is actively connected right now
    if (PadMapAccessibilityService.instance != null) return true
    // Settings.Secure stores enabled services as a colon-separated list of flat component names.
    // Use ComponentName to produce the exact format Android writes, then match by token so a
    // partial package name can never produce a false positive.
    val target = android.content.ComponentName(ctx, PadMapAccessibilityService::class.java)
        .flattenToString()
    val stored = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return stored.split(':').any { it.equals(target, ignoreCase = true) }
}
