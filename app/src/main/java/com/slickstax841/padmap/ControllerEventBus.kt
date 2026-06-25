package com.slickstax841.padmap

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ControllerEventBus {
    private val _keyEvents = MutableSharedFlow<KeyEvent>(extraBufferCapacity = 32)
    val keyEvents: SharedFlow<KeyEvent> = _keyEvents.asSharedFlow()

    private val _motionEvents = MutableSharedFlow<Pair<Int, Float>>(extraBufferCapacity = 64)
    val motionEvents: SharedFlow<Pair<Int, Float>> = _motionEvents.asSharedFlow()

    fun emitKey(event: KeyEvent) { _keyEvents.tryEmit(event) }
    fun emitAxis(axisCode: Int, value: Float) { _motionEvents.tryEmit(Pair(axisCode, value)) }
}
