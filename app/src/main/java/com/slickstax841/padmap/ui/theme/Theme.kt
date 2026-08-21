package com.slickstax841.padmap.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.slickstax841.padmap.data.DataStore

// Legacy constants — kept so OverlayManager (View-based) can continue to reference them
val AccentBlue  = Color(0xFF00BFFF)
val BgDark      = Color(0xFF0D0D0D)
val SurfaceCol  = Color(0xFF1A1A1A)
val SurfaceVar  = Color(0xFF252525)
val TextWhite   = Color(0xFFFFFFFF)
val TextGrey    = Color(0xFF888888)

@Composable
fun PadMapTheme(content: @Composable () -> Unit) {
    val appData by DataStore.data.collectAsState()
    val skin = AllSkins.find { it.id == appData.skinId } ?: DefaultSkin

    val scheme = darkColorScheme(
        primary          = skin.accent,
        onPrimary        = skin.btnOnAccent,
        background       = skin.bgDark,
        onBackground     = skin.textPrimary,
        surface          = skin.surfaceCol,
        onSurface        = skin.textPrimary,
        surfaceVariant   = skin.surfaceVar,
        onSurfaceVariant = skin.textSecondary,
        outline          = skin.borderUnfocused
    )

    val gamerShapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp)
    )

    CompositionLocalProvider(LocalAppSkin provides skin) {
        MaterialTheme(colorScheme = scheme, shapes = gamerShapes, content = content)
    }
}
