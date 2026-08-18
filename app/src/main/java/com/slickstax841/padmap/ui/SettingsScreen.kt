package com.slickstax841.padmap.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slickstax841.padmap.data.DataStore
import com.slickstax841.padmap.inject.NsdAdbFinder
import com.slickstax841.padmap.inject.SidecarClient
import com.slickstax841.padmap.inject.SidecarHost
import com.slickstax841.padmap.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

            Text("INJECTOR", fontSize = 11.sp, color = skin.textSecondary, letterSpacing = 2.sp,
                fontFamily = skin.labelFont)
            Spacer(Modifier.height(12.dp))
            InjectorCard(skin = skin)
        }
    }
}

@Composable
private fun InjectorCard(skin: AppSkin) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(SidecarClient.isAvailable) }
    var paired by remember { mutableStateOf(SidecarHost.hasPaired(ctx)) }

    val dotColor = if (running) Color(0xFF00DD66) else Color(0xFFFF8800)
    val statusText = if (running) "Injector running" else SidecarHost.status

    fun openDeveloper() {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(skin.surfaceCol)
            .border(1.dp, skin.borderUnfocused, RoundedCornerShape(8.dp))
            .padding(14.dp)
    ) {
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
        Spacer(Modifier.height(8.dp))
        Text(
            if (paired)
                "Already paired. Turn on Wireless debugging (Developer options) and tap START. No code after the first time."
            else
                "Leave the pair popup open. A new popup makes a new code AND a new port. Type the 6 digits from that one screen. Paste IP:port only if auto-find fails.",
            fontSize = 12.sp, color = skin.textSecondary, fontFamily = skin.labelFont
        )
        if (!paired) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 6) code = it.filter { ch -> ch.isDigit() } },
                label = { Text("6-digit pairing code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pairPort,
                onValueChange = { pairPort = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ':' } },
                label = { Text("Paste IP:port from pair dialog (if auto-find fails)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { openDeveloper() }) {
                Text("OPEN DEVELOPER", color = skin.accent, fontSize = 12.sp)
            }
            if (paired) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { SidecarHost.ensureRunning(ctx) }
                            }
                            busy = false
                            running = SidecarClient.isAvailable
                            result.exceptionOrNull()?.let {
                                Toast.makeText(ctx, it.message ?: "Start failed", Toast.LENGTH_LONG).show()
                            } ?: Toast.makeText(
                                ctx,
                                if (running) "Injector running" else SidecarHost.status,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) { Text(if (busy) "WORKING…" else "START", color = skin.accent, fontSize = 12.sp) }
            } else {
                TextButton(
                    enabled = !busy && code.length == 6,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (!SidecarHost.isWirelessDebugOn(ctx)) {
                                        error("Turn on Wireless debugging first")
                                    }
                                    val pairEp = if (pairPort.isNotBlank()) {
                                        com.slickstax841.padmap.inject.AdbEndpoint.parse(pairPort)
                                            ?: error("Pair address should look like 192.168.0.12:37123")
                                    } else {
                                        NsdAdbFinder.findWithRetry(ctx, pairing = true)
                                            ?: error("Could not find pairing port. Paste the IP:port from the same open popup.")
                                    }
                                    SidecarHost.pair(ctx, pairEp.host, pairEp.port, code)
                                    val connEp = NsdAdbFinder.findWithRetry(ctx, pairing = false)
                                        ?: error("Paired, but could not find the wireless-debug connect port. Leave Wireless debugging ON and try again.")
                                    SidecarHost.start(ctx, connEp.host, connEp.port)
                                }
                            }
                            busy = false
                            running = SidecarClient.isAvailable
                            paired = SidecarHost.hasPaired(ctx)
                            result.exceptionOrNull()?.let {
                                Toast.makeText(ctx, it.message ?: "Pair failed", Toast.LENGTH_LONG).show()
                            } ?: Toast.makeText(ctx, "Injector running — you will not need the code again", Toast.LENGTH_LONG).show()
                        }
                    }
                ) { Text(if (busy) "WORKING…" else "PAIR ONCE", color = skin.accent, fontSize = 12.sp) }
            }
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
