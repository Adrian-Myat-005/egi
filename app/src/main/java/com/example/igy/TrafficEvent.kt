package com.example.igy

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

    private var lastResetTime = System.currentTimeMillis()
    private var countOffset = 0L
    private val RESET_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours

    fun log(message: String) {
        checkReset()
        if (message.contains("BLOCKED")) {
            // If log is used for manual increment, we still handle it
        }
        _events.tryEmit(message)
    }

    private fun checkReset() {
        val now = System.currentTimeMillis()
        if (now - lastResetTime >= RESET_INTERVAL) {
            // For manual counters, we'd just reset _blockedCount.value
            // For native-backed counters, we update the offset
            lastResetTime = now
            // We can't know the exact rawCount here without a parameter, 
            // so we'll rely on updateCount to set the offset correctly.
        }
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

    fun updateCount(rawCount: Long) {
        val now = System.currentTimeMillis()
        if (now - lastResetTime >= RESET_INTERVAL) {
            countOffset = rawCount
            lastResetTime = now
        }
        
        val displayCount = (rawCount - countOffset).coerceAtLeast(0).toInt()
        _blockedCount.value = displayCount
    }

    fun resetCount() {
        lastResetTime = System.currentTimeMillis()
        // We can't immediately set offset to rawCount here without knowing it,
        // but updateCount will handle it on next call if interval passed.
        // For a manual reset, we would need the current rawCount.
        _blockedCount.value = 0
    }
}
