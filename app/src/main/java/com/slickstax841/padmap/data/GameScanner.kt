package com.slickstax841.padmap.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.slickstax841.padmap.service.OverlayManager
import java.util.UUID

data class GameScanResult(
    val added: Int = 0,
    val archived: Int = 0,
    val restored: Int = 0,
    val renamed: Int = 0
) {
    val changed: Boolean get() = added + archived + restored + renamed > 0
    fun summary(): String {
        if (!changed) return "No game changes"
        val parts = mutableListOf<String>()
        if (added > 0) parts.add("$added new")
        if (archived > 0) parts.add("$archived archived")
        if (restored > 0) parts.add("$restored restored")
        if (renamed > 0) parts.add("$renamed renamed")
        return "Scan: " + parts.joinToString(", ")
    }
}

object GameScanner {

    fun isPackageInstalled(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return try {
            context.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: Exception) { false }
    }

    fun isInstalledGame(info: ApplicationInfo): Boolean {
        if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return false
        if (info.category == ApplicationInfo.CATEGORY_GAME) return true
        @Suppress("DEPRECATION")
        return info.flags and ApplicationInfo.FLAG_IS_GAME != 0
    }

    fun scan(context: Context): GameScanResult {
        DataStore.init(context)
        val pm = context.packageManager
        val ownPkg = context.packageName
        val installed = pm.getInstalledApplications(0)
            .filter { it.packageName != ownPkg && it.packageName !in OverlayManager.BLOCKED_PACKAGES }
        val installedByPkg = installed.associateBy { it.packageName }
        val installedGames = installed.filter(::isInstalledGame)

        var added = 0
        var archived = 0
        var restored = 0
        var renamed = 0

        DataStore.update { data ->
            val next = data.gameLayouts.toMutableList()

            next.forEachIndexed { i, layout ->
                val pkg = layout.packageName
                if (pkg.isBlank()) return@forEachIndexed
                val info = installedByPkg[pkg]
                if (info == null) {
                    if (!layout.archived) {
                        next[i] = layout.copy(archived = true)
                        archived++
                    }
                } else {
                    val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(layout.name)
                    var updated = layout
                    if (layout.archived) {
                        updated = updated.copy(archived = false)
                        restored++
                    }
                    if (label.isNotBlank() && label != layout.name) {
                        updated = updated.copy(name = label)
                        renamed++
                    }
                    if (updated != layout) next[i] = updated
                }
            }

            val known = next.map { it.packageName }.filter { it.isNotBlank() }.toHashSet()
            for (info in installedGames) {
                if (info.packageName in known) continue
                val label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName)
                next.add(
                    GameLayout(
                        id = UUID.randomUUID().toString(),
                        name = label,
                        packageName = info.packageName,
                        controllerPresetId = data.activePresetId
                    )
                )
                known.add(info.packageName)
                added++
            }

            val activeGone = next.none { it.id == data.activeLayoutId && !it.archived }
            val newActive = if (activeGone) {
                next.firstOrNull { !it.archived }?.id ?: ""
            } else data.activeLayoutId

            data.copy(gameLayouts = next, activeLayoutId = newActive)
        }

        return GameScanResult(added, archived, restored, renamed)
    }
}
