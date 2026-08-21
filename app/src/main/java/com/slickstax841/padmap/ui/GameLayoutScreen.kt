package com.slickstax841.padmap.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slickstax841.padmap.data.DataStore
import com.slickstax841.padmap.data.TouchAction
import com.slickstax841.padmap.service.OverlayManager
import com.slickstax841.padmap.ui.theme.*

@Composable
fun GameLayoutScreen(layoutId: String, onBack: () -> Unit) {
    val appData by DataStore.data.collectAsState()
    val skin = LocalAppSkin.current
    val layout = appData.gameLayouts.find { it.id == layoutId } ?: run { onBack(); return }

    var layoutName by remember(layout.name) { mutableStateOf(layout.name) }
    var packageName by remember(layout.packageName) { mutableStateOf(layout.packageName) }
    var selectedPresetId by remember(layout.controllerPresetId) { mutableStateOf(layout.controllerPresetId) }
    var dropdownOpen by remember { mutableStateOf(false) }

    fun save() {
        DataStore.update { d -> d.copy(gameLayouts = d.gameLayouts.map {
            if (it.id == layoutId) it.copy(name = layoutName, packageName = packageName, controllerPresetId = selectedPresetId) else it
        }) }
    }

    SkinBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GamerTextButton(onClick = { save(); onBack() }) { Text("\u2190", color = skin.accent, fontSize = 20.sp) }
                Text("Game Layout", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = skin.textPrimary,
                    fontFamily = skin.headingFont)
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(value = layoutName, onValueChange = { layoutName = it }, label = { Text("Game name", color = skin.textSecondary) }, singleLine = true, colors = tfColors(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = packageName, onValueChange = { packageName = it }, label = { Text("App package for auto-detection (e.g. com.pubg.imobile)", color = skin.textSecondary) }, singleLine = true, colors = tfColors(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))

            Box {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(skin.surfaceCol).clickable { dropdownOpen = true }.padding(14.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text("Controller Preset", fontSize = 11.sp, color = skin.textSecondary)
                        Text(appData.controllerPresets.find { it.id == selectedPresetId }?.name ?: "None selected", color = if (selectedPresetId.isBlank()) skin.textSecondary else skin.textPrimary, fontWeight = FontWeight.Medium)
                    }
                    Text("\u25BE", color = skin.accent, fontSize = 16.sp)
                }
                DropdownMenu(expanded = dropdownOpen, onDismissRequest = { dropdownOpen = false }, modifier = Modifier.background(skin.surfaceCol)) {
                    appData.controllerPresets.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name, color = skin.textPrimary) }, onClick = { selectedPresetId = p.id; dropdownOpen = false })
                    }
                    if (appData.controllerPresets.isEmpty())
                        DropdownMenuItem(text = { Text("No presets \u2014 create one first", color = skin.textSecondary) }, onClick = { dropdownOpen = false })
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("ZONES  (${layout.mappings.size})", fontSize = 11.sp, color = skin.textSecondary, letterSpacing = 2.sp,
                fontFamily = skin.labelFont)
            Spacer(Modifier.height(6.dp))

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (layout.mappings.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(skin.surfaceVar).padding(20.dp), Alignment.Center) {
                            Text("No zones yet \u2014 tap Configure Zones to place them on screen.", color = skin.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
                items(layout.mappings) { entry ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(skin.surfaceCol).padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(entry.inputName, color = skin.textPrimary, fontWeight = FontWeight.Medium)
                        Text(when (val a = entry.action) {
                            is TouchAction.Tap -> "Tap (${a.x.toInt()}, ${a.y.toInt()})"
                            is TouchAction.Drag -> "Drag centre (${a.centerX.toInt()}, ${a.centerY.toInt()}) r=${a.radius.toInt()}"
                        }, fontSize = 12.sp, color = skin.textSecondary)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = { save(); OverlayManager.instance?.startConfigOverlay(layoutId) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = skin.accent)) {
                Text("CONFIGURE ZONES", color = skin.btnOnAccent, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { save(); onBack() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.accent)) {
                Text("SAVE & BACK")
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
