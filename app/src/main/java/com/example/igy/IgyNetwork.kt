package com.example.igy

object IgyNetwork {
    private var isLibLoaded = false

    init {
        try {
            System.loadLibrary("igy_core")
            isLibLoaded = true
        } catch (t: Throwable) {
            // Native library failed to load
        }
    }

    external fun measureNetworkStats(targetIp: String): String
    external fun scanSubnet(baseIp: String): String
    external fun kickDevice(targetIp: String, targetMac: String): Boolean
    external fun runVpnLoop(fd: Int)
    external fun runPassiveShield(fd: Int)
    external fun getNativeBlockedCount(): Long
    external fun getCoreHealth(): String
    external fun getEnergySavings(): String
    external fun setBandwidthLimit(limitMbps: Int)
    external fun toggleStealthMode(enabled: Boolean)
    external fun setOutlineKey(key: String)
    external fun setAllowedDomains(domains: String)

    fun isAvailable() = isLibLoaded

    @JvmStatic
    fun nativeLog(msg: String) {
        TrafficEvent.log("NATIVE >> $msg")
    }
}
