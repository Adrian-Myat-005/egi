package com.example.egi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TrafficEvent {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val events = _events.asSharedFlow()

    fun log(message: String) {
        _events.tryEmit(message)
    }
}
