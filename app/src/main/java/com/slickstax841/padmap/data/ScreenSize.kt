package com.slickstax841.padmap.data

import android.content.Context
import android.os.Build
import android.view.WindowManager

/** Real device pixels. Landscape/portrait are the two sides swapped. */
object ScreenSize {
    fun current(ctx: Context): Pair<Int, Int> {
        val (a, b) = raw(ctx)
        return a to b
    }

    fun landscape(ctx: Context): Pair<Int, Int> {
        val (a, b) = raw(ctx)
        return maxOf(a, b) to minOf(a, b)
    }

    fun portrait(ctx: Context): Pair<Int, Int> {
        val (a, b) = raw(ctx)
        return minOf(a, b) to maxOf(a, b)
    }

    fun isLandscape(ctx: Context): Boolean {
        val (w, h) = current(ctx)
        return w >= h
    }

    private fun raw(ctx: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = ctx.getSystemService(WindowManager::class.java)
                .maximumWindowMetrics.bounds
            val w = bounds.width()
            val h = bounds.height()
            if (w > 0 && h > 0) return w to h
        }
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }
}

data class OverlayRect(val x: Int, val y: Int, val w: Int, val h: Int)

fun AppData.resolvedOverlay(ctx: Context): OverlayRect {
    val (lw, lh) = ScreenSize.landscape(ctx)
    val (pw, ph) = ScreenSize.portrait(ctx)
    val (cw, ch) = ScreenSize.current(ctx)
    return when (overlayMode) {
        "landscape" -> OverlayRect(
            landX ?: overlayX ?: 0,
            landY ?: overlayY ?: 0,
            landW ?: if (overlayW != null && overlayW >= (overlayH ?: 0)) overlayW else lw,
            landH ?: if (overlayH != null && (overlayW ?: 0) >= overlayH) overlayH else lh
        )
        "portrait" -> OverlayRect(
            portX ?: overlayX ?: 0,
            portY ?: overlayY ?: 0,
            portW ?: if (overlayW != null && overlayW < (overlayH ?: Int.MAX_VALUE)) overlayW else pw,
            portH ?: if (overlayH != null && (overlayW ?: 0) < overlayH) overlayH else ph
        )
        else -> OverlayRect(0, 0, cw, ch)
    }
}
