package com.slickstax841.padmap.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

class PresetStorage(context: Context) {
    private val file = File(context.filesDir, "padmap_data.json")

    suspend fun load(): AppData = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext AppData()
        runCatching { AppJson.decodeFromString<AppData>(file.readText()) }
            .getOrDefault(AppData())
    }

    suspend fun save(data: AppData): Unit = withContext(Dispatchers.IO) {
        file.writeText(AppJson.encodeToString(data))
    }
}
