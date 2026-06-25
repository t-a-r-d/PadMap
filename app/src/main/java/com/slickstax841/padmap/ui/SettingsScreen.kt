package com.slickstax841.padmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slickstax841.padmap.data.DataStore
import com.slickstax841.padmap.service.InjectManager
import com.slickstax841.padmap.ui.theme.*

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val skin = LocalAppSkin.current
    val appData by DataStore.data.collectAsState()

    SkinBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u2190", color = skin.accent, fontSize = 20.sp) }
                Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = skin.textPrimary,
                    fontFamily = skin.headingFont)
            }
            Spacer(Modifier.height(24.dp))

            // APPEARANCE
            Text("APPEARANCE", fontSize = 11.sp, color = skin.textSecondary, letterSpacing = 2.sp,
                fontFamily = skin.labelFont)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AllSkins.forEach { s ->
                    SkinThumbCard(
                        previewSkin = s,
                        isSelected = appData.skinId == s.id,
                        currentSkin = skin,
                        onClick = { DataStore.update { it.copy(skinId = s.id) } }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // INPUT MODE
            Text("INPUT MODE", fontSize = 11.sp, color = skin.textSecondary, letterSpacing = 2.sp,
                fontFamily = skin.labelFont)
            Spacer(Modifier.height(12.dp))
            InjectionStatusCard(skin = skin)
        }
    }
}

@Composable
private fun InjectionStatusCard(skin: AppSkin) {
    val available = InjectManager.isAvailable
    val dotColor  = if (available) Color(0xFF00DD66) else Color(0xFFFF8800)
    val statusText = if (available) "Enhanced injection active" else "Standard injection active"
    val detail = if (available)
        "injectInputEvent() \u2014 TOOL_TYPE_FINGER, true multi-touch, 16ms timer loop"
    else
        "dispatchGesture() fallback \u2014 some anti-cheat games may not respond"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(skin.surfaceCol)
            .border(1.dp, skin.borderUnfocused, RoundedCornerShape(8.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(dotColor)
                )
                Spacer(Modifier.width(10.dp))
                Text(statusText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = skin.textPrimary, fontFamily = skin.labelFont)
            }
            Spacer(Modifier.height(6.dp))
            Text(detail, fontSize = 12.sp, color = skin.textSecondary, fontFamily = skin.labelFont)
        }
    }
}

@Composable
private fun SkinThumbCard(
    previewSkin: AppSkin,
    isSelected: Boolean,
    currentSkin: AppSkin,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        val borderMod = if (isSelected && currentSkin.neonGlow)
            Modifier.neonGlow(previewSkin.accent, cornerRadius = 6.dp)
        else if (isSelected)
            Modifier.border(2.dp, previewSkin.accent, RoundedCornerShape(6.dp))
        else
            Modifier.border(1.dp, currentSkin.borderUnfocused, RoundedCornerShape(6.dp))

        Box(
            modifier = Modifier
                .width(80.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(previewSkin.bgDark)
                .then(borderMod)
        ) {
            if (previewSkin.showPulsingGrid) {
                Canvas(Modifier.fillMaxSize()) {
                    val spacing = 10.dp.toPx()
                    val lineColor = previewSkin.accentSecondary.copy(alpha = 0.14f)
                    var x = 0f
                    while (x <= size.width) {
                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.5f)
                        x += spacing
                    }
                    var y = 0f
                    while (y <= size.height) {
                        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5f)
                        y += spacing
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(6.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(2.dp))
                    .background(previewSkin.surfaceCol))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.fillMaxWidth(0.8f).height(5.dp).clip(RoundedCornerShape(1.dp))
                        .background(previewSkin.surfaceVar))
                    Box(Modifier.fillMaxWidth(0.6f).height(5.dp).clip(RoundedCornerShape(1.dp))
                        .background(previewSkin.surfaceVar))
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(2.dp))
                    .background(previewSkin.accent))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            previewSkin.name,
            fontSize = 11.sp,
            color = if (isSelected) currentSkin.accent else currentSkin.textSecondary,
            fontFamily = currentSkin.labelFont
        )
    }
}
