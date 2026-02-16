package com.example.egi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object TrafficEvent {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val events = _events.asSharedFlow()

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount = _blockedCount.asStateFlow()

    private val _vpnActive = MutableStateFlow(false)
    val vpnActive = _vpnActive.asStateFlow()

    private val _isLockdown = MutableStateFlow(false)
    val isLockdown = _isLockdown.asStateFlow()

    fun log(message: String) {
        if (message.contains("BLOCKED")) {
            _blockedCount.value += 1
        }
        _events.tryEmit(message)
    }

    fun clearLogs() {
        _events.tryEmit("CONSOLE_CLEARED")
    }

    fun setVpnActive(active: Boolean) {
        _vpnActive.value = active
    }

    fun setLockdown(lockdown: Boolean) {
        _isLockdown.value = lockdown
    }

    fun updateCount(count: Long) {
        _blockedCount.value = count.toInt()
    }

    fun resetCount() {
        _blockedCount.value = 0
    }
}
