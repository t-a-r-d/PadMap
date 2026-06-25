package com.slickstax841.padmap.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

data class AppSkin(
    val id: String,
    val name: String,
    // Colours
    val accent: Color,
    val accentSecondary: Color,
    val bgDark: Color,
    val surfaceCol: Color,
    val surfaceVar: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val borderUnfocused: Color,
    val activeItemBg: Color,
    val activeItemBorder: Color,
    val activeRowBg: Color,
    val btnOnAccent: Color,
    // Typography
    val headingFont: FontFamily,
    val labelFont: FontFamily,
    // Effects
    val showScanlines: Boolean,
    val showPulsingGrid: Boolean,
    val showHexCodes: Boolean,
    val neonGlow: Boolean
)

val DefaultSkin = AppSkin(
    id = "default",
    name = "Default",
    accent            = Color(0xFF00BFFF),
    accentSecondary   = Color(0xFF00BFFF),
    bgDark            = Color(0xFF0D0D0D),
    surfaceCol        = Color(0xFF1A1A1A),
    surfaceVar        = Color(0xFF252525),
    textPrimary       = Color(0xFFFFFFFF),
    textSecondary     = Color(0xFF888888),
    borderUnfocused   = Color(0xFF333333),
    activeItemBg      = Color(0xFF0A2A3A),
    activeItemBorder  = Color(0xFF00BFFF),
    activeRowBg       = Color(0xFF0D3D5C),
    btnOnAccent       = Color.Black,
    headingFont       = FontFamily.SansSerif,
    labelFont         = FontFamily.SansSerif,
    showScanlines     = false,
    showPulsingGrid   = false,
    showHexCodes      = false,
    neonGlow          = false
)

val CyberpunkSkin = AppSkin(
    id = "cyberpunk",
    name = "Cyberpunk",
    accent            = Color(0xFFFF00DD),
    accentSecondary   = Color(0xFF00E5FF),
    bgDark            = Color(0xFF050508),
    surfaceCol        = Color(0xFF0D0D16),
    surfaceVar        = Color(0xFF13132A),
    textPrimary       = Color(0xFFE0E0FF),
    textSecondary     = Color(0xFF606080),
    borderUnfocused   = Color(0xFF2A2A44),
    activeItemBg      = Color(0xFF1A0020),
    activeItemBorder  = Color(0xFFFF00DD),
    activeRowBg       = Color(0xFF001520),
    btnOnAccent       = Color.Black,
    headingFont       = FontFamily.Monospace,
    labelFont         = FontFamily.Monospace,
    showScanlines     = true,
    showPulsingGrid   = true,
    showHexCodes      = true,
    neonGlow          = true
)

val AllSkins = listOf(DefaultSkin, CyberpunkSkin)

val LocalAppSkin = staticCompositionLocalOf { DefaultSkin }
