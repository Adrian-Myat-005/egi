package com.example.egi

object EgiNetwork {
    private var isLibLoaded = false

    init {
        try {
            System.loadLibrary("egi_core")
            isLibLoaded = true
        } catch (t: Throwable) {
            // Native library failed to load
        }
    }

    external fun measureNetworkStats(targetIp: String): String
    external fun scanSubnet(baseIp: String): String
    external fun kickDevice(targetIp: String, targetMac: String): Boolean
    external fun runVpnLoop(fd: Int)
    external fun getNativeBlockedCount(): Long
    external fun getEnergySavings(): String
    external fun setBandwidthLimit(limitMbps: Int)
    external fun toggleStealthMode(enabled: Boolean)
    external fun setOutlineKey(key: String)

    fun isAvailable() = isLibLoaded
}
