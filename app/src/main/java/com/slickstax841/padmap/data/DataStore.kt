package com.slickstax841.padmap.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object DataStore {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var storage: PresetStorage

    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data.asStateFlow()

    fun init(context: Context) {
        if (::storage.isInitialized) return
        storage = PresetStorage(context.applicationContext)
        // Load synchronously so data is ready before onServiceConnected returns.
        // Prevents the async-load race where a late IO write overwrites updates
        // already applied on the main thread (e.g. a new layout added right after start).
        runBlocking { _data.value = storage.load() }
    }

    fun update(transform: (AppData) -> AppData) {
        val updated = transform(_data.value)
        _data.value = updated          // update in-memory state immediately (any thread)
        scope.launch { storage.save(updated) }  // persist to disk asynchronously
    }

    val activeLayout: GameLayout?
        get() = _data.value.gameLayouts.find { it.id == _data.value.activeLayoutId }

    val activePreset: ControllerPreset?
        get() = _data.value.controllerPresets.find { it.id == _data.value.activePresetId }
}
