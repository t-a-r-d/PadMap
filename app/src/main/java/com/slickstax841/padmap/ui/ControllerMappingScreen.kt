package com.slickstax841.padmap.ui

import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slickstax841.padmap.ControllerEventBus
import com.slickstax841.padmap.data.ControllerPreset
import com.slickstax841.padmap.data.DataStore
import com.slickstax841.padmap.ui.theme.*
import kotlinx.coroutines.flow.collect
import kotlin.math.abs

data class CapturedInput(val code: Int, val isAxis: Boolean, var label: String)

@Composable
fun ControllerMappingScreen(presetId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val skin = LocalAppSkin.current
    val preset = DataStore.data.collectAsState().value.controllerPresets.find { it.id == presetId }
    val captured = remember {
        mutableStateListOf<CapturedInput>().also { list ->
            preset?.buttons?.forEach { (code, label) -> list.add(CapturedInput(code, false, label)) }
            preset?.axes?.forEach { (code, label) -> list.add(CapturedInput(code, true, label)) }
        }
    }
    var presetName by remember { mutableStateOf(preset?.name ?: "New Controller") }
    var deviceName by remember { mutableStateOf(preset?.deviceName ?: "") }
    var capturingDevice by remember { mutableStateOf(false) }
    // Tracks inputs currently held — (code, isAxis) pairs. Rows highlight while their entry is present.
    val activeInputs = remember { mutableStateListOf<Pair<Int, Boolean>>() }

    // Auto-scan for a connected gamepad and pre-fill name on first open
    LaunchedEffect(Unit) {
        if (deviceName.isNotBlank()) return@LaunchedEffect
        val im = ctx.getSystemService(InputManager::class.java)
        val gamepad = im.inputDeviceIds.toList()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { d ->
                (d.sources and InputDevice.SOURCE_GAMEPAD != 0) ||
                (d.sources and InputDevice.SOURCE_JOYSTICK != 0)
            }
        if (gamepad != null) {
            deviceName = gamepad.name
            if (presetName == "New Controller") presetName = gamepad.name
        }
    }
    val listState = rememberLazyListState()
    var editingIndex by remember { mutableStateOf(-1) }
    var editLabel by remember { mutableStateOf("") }

    // Auto-scroll to bottom when a new input is captured
    LaunchedEffect(captured.size) {
        if (captured.isNotEmpty()) listState.animateScrollToItem(captured.size - 1)
    }

    LaunchedEffect(Unit) {
        ControllerEventBus.keyEvents.collect { event ->
            if (capturingDevice) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    deviceName = event.device?.name ?: ""
                    capturingDevice = false
                }
                return@collect
            }
            when (event.action) {
                android.view.KeyEvent.ACTION_DOWN -> {
                    if (captured.none { !it.isAxis && it.code == event.keyCode })
                        captured.add(CapturedInput(event.keyCode, false, keyCodeLabel(event.keyCode)))
                    else
                        activeInputs.add(event.keyCode to false)
                }
                android.view.KeyEvent.ACTION_UP -> activeInputs.remove(event.keyCode to false)
            }
        }
    }
    LaunchedEffect(Unit) {
        ControllerEventBus.motionEvents.collect { (axis, value) ->
            if (axis == android.view.MotionEvent.AXIS_LTRIGGER || axis == android.view.MotionEvent.AXIS_RTRIGGER) return@collect

            // D-pad HAT axes — digital, mapped to button codes
            if (axis == android.view.MotionEvent.AXIS_HAT_X || axis == android.view.MotionEvent.AXIS_HAT_Y) {
                val posCode = if (axis == android.view.MotionEvent.AXIS_HAT_X) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_DOWN
                val negCode = if (axis == android.view.MotionEvent.AXIS_HAT_X) android.view.KeyEvent.KEYCODE_DPAD_LEFT  else android.view.KeyEvent.KEYCODE_DPAD_UP
                val posLabel = if (axis == android.view.MotionEvent.AXIS_HAT_X) "D-Right" else "D-Down"
                val negLabel = if (axis == android.view.MotionEvent.AXIS_HAT_X) "D-Left"  else "D-Up"
                when {
                    value > 0.5f -> {
                        if (captured.none { !it.isAxis && it.code == posCode }) captured.add(CapturedInput(posCode, false, posLabel))
                        else activeInputs.add(posCode to false)
                        activeInputs.remove(negCode to false)
                    }
                    value < -0.5f -> {
                        if (captured.none { !it.isAxis && it.code == negCode }) captured.add(CapturedInput(negCode, false, negLabel))
                        else activeInputs.add(negCode to false)
                        activeInputs.remove(posCode to false)
                    }
                    else -> { activeInputs.remove(posCode to false); activeInputs.remove(negCode to false) }
                }
                return@collect
            }

            // Analog axes — highlight existing entries; add new ones on first deflection
            if (abs(value) >= 0.5f) {
                if (captured.none { it.isAxis && it.code == axis })
                    captured.add(CapturedInput(axis, true, axisLabel(axis)))
                else
                    activeInputs.add(axis to true)
            } else {
                activeInputs.remove(axis to true)
            }
        }
    }

    SkinBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u2190", color = skin.accent, fontSize = 20.sp) }
                Text("Controller Preset", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = skin.textPrimary,
                    fontFamily = skin.headingFont)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = presetName, onValueChange = { presetName = it }, label = { Text("Preset name", color = skin.textSecondary) }, singleLine = true, colors = tfColors(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Device (auto-detection)", fontSize = 12.sp, color = skin.textSecondary)
                    Text(deviceName.ifBlank { "Not set" }, color = if (deviceName.isBlank()) skin.textSecondary else skin.accent, fontSize = 13.sp)
                }
                TextButton(onClick = { capturingDevice = true }) {
                    Text(if (capturingDevice) "PRESS ANY BUTTON..." else "CAPTURE DEVICE", color = skin.accent, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(skin.surfaceVar).padding(16.dp), Alignment.Center) {
                Text("Press any button or move any stick on your controller", color = skin.accent, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text("CAPTURED  (${captured.size})", fontSize = 11.sp, color = skin.textSecondary, letterSpacing = 2.sp,
                fontFamily = skin.labelFont)
            Spacer(Modifier.height(6.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(captured.size) { i ->
                    val item = captured[i]
                    if (editingIndex == i) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = editLabel, onValueChange = { editLabel = it }, singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { captured[i] = item.copy(label = editLabel); editingIndex = -1 }),
                                colors = tfColors(), modifier = Modifier.weight(1f))
                            TextButton(onClick = { captured[i] = item.copy(label = editLabel); editingIndex = -1 }) { Text("OK", color = skin.accent) }
                        }
                    } else {
                        val isActive = (item.code to item.isAxis) in activeInputs
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (isActive) skin.activeRowBg else skin.surfaceCol)
                            .clickable { editingIndex = i; editLabel = item.label }
                            .padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (item.isAxis) "AXIS" else "BTN", fontSize = 10.sp,
                                    color = if (item.isAxis) Color(0xFFFF8C00) else skin.accent,
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                        .background(if (item.isAxis) Color(0x33FF8C00) else skin.accent.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                                Text(item.label, color = skin.textPrimary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val codeText = if (skin.showHexCodes)
                                    "0x${item.code.toString(16).uppercase().padStart(2, '0')}"
                                else
                                    "code ${item.code}"
                                Text(codeText, fontSize = 11.sp, color = skin.textSecondary,
                                    fontFamily = skin.labelFont)
                                Spacer(Modifier.width(12.dp))
                                Text("\u2715", color = Color(0xFFCC3333), modifier = Modifier.clickable { captured.removeAt(i) })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                val updated = ControllerPreset(id = presetId, name = presetName, deviceName = deviceName,
                    buttons = captured.filter { !it.isAxis }.associate { it.code to it.label },
                    axes = captured.filter { it.isAxis }.associate { it.code to it.label })
                DataStore.update { d -> d.copy(controllerPresets = d.controllerPresets.map { if (it.id == presetId) updated else it }) }
                onBack()
            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = skin.accent)) {
                Text("SAVE PRESET", color = skin.btnOnAccent, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable private fun tfColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = LocalAppSkin.current.accent,
    unfocusedBorderColor = LocalAppSkin.current.borderUnfocused,
    focusedTextColor     = LocalAppSkin.current.textPrimary,
    unfocusedTextColor   = LocalAppSkin.current.textPrimary,
    cursorColor          = LocalAppSkin.current.accent,
    focusedLabelColor    = LocalAppSkin.current.accent
)

private fun keyCodeLabel(code: Int) = when (code) {
    android.view.KeyEvent.KEYCODE_BUTTON_A -> "A"
    android.view.KeyEvent.KEYCODE_BUTTON_B -> "B"
    android.view.KeyEvent.KEYCODE_BUTTON_X -> "X"
    android.view.KeyEvent.KEYCODE_BUTTON_Y -> "Y"
    android.view.KeyEvent.KEYCODE_BUTTON_L1 -> "LB"
    android.view.KeyEvent.KEYCODE_BUTTON_R1 -> "RB"
    android.view.KeyEvent.KEYCODE_BUTTON_L2 -> "LT"
    android.view.KeyEvent.KEYCODE_BUTTON_R2 -> "RT"
    android.view.KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
    android.view.KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
    android.view.KeyEvent.KEYCODE_BUTTON_START -> "Start"
    android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
    android.view.KeyEvent.KEYCODE_DPAD_UP -> "D-Up"
    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "D-Down"
    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "D-Left"
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "D-Right"
    else -> "Button $code"
}

private fun axisLabel(axis: Int) = when (axis) {
    android.view.MotionEvent.AXIS_X -> "L-Stick X"
    android.view.MotionEvent.AXIS_Y -> "L-Stick Y"
    android.view.MotionEvent.AXIS_Z -> "R-Stick X"
    android.view.MotionEvent.AXIS_RZ -> "R-Stick Y"
    android.view.MotionEvent.AXIS_HAT_X -> "D-Pad X"
    android.view.MotionEvent.AXIS_HAT_Y -> "D-Pad Y"
    android.view.MotionEvent.AXIS_LTRIGGER -> "LT"
    android.view.MotionEvent.AXIS_RTRIGGER -> "RT"
    else -> "Axis $axis"
}
