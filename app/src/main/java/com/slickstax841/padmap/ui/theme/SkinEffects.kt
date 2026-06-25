package com.slickstax841.padmap.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Horizontal scanline overlay — no-op when skin.showScanlines is false
@Composable
fun ScanlineOverlay(modifier: Modifier = Modifier) {
    val skin = LocalAppSkin.current
    if (!skin.showScanlines) return
    Canvas(modifier = modifier) {
        val spacing = 4.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = Color.Black.copy(alpha = 0.18f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5f
            )
            y += spacing
        }
    }
}

// Pulsing grid drawn behind content — no-op when skin.showPulsingGrid is false
@Composable
fun PulsingGridBackground(modifier: Modifier = Modifier) {
    val skin = LocalAppSkin.current
    if (!skin.showPulsingGrid) return
    val transition = rememberInfiniteTransition(label = "grid")
    val alpha by transition.animateFloat(
        initialValue = 0.03f,
        targetValue  = 0.08f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gridAlpha"
    )
    Canvas(modifier = modifier) {
        val spacing = 28.dp.toPx()
        val lineColor = skin.accentSecondary.copy(alpha = alpha)
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

// Layered borders that simulate a neon bloom glow — use when skin.neonGlow is true
fun Modifier.neonGlow(color: Color, cornerRadius: Dp = 8.dp): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .border(3.dp, color.copy(alpha = 0.12f), shape)
        .border(2.dp, color.copy(alpha = 0.32f), shape)
        .border(1.dp, color.copy(alpha = 0.85f), shape)
}

// Convenience wrapper that layers the pulsing grid behind and scanlines on top of content
@Composable
fun SkinBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val skin = LocalAppSkin.current
    Box(modifier = modifier.background(skin.bgDark)) {
        PulsingGridBackground(Modifier.matchParentSize())
        content()
        ScanlineOverlay(Modifier.matchParentSize())
    }
}
