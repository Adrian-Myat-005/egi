package com.example.igy

import android.util.Log

object IgyNetwork {
    private var isLibLoaded = false

    init {
        try {
            System.loadLibrary("igy_core")
            isLibLoaded = true
        } catch (e: Throwable) {
            Log.e("IgyNetwork", "NATIVE_LOAD_FAILED: ${e.message}")
        }
    }

    external fun measureNetworkStats(targetIp: String): String?
    external fun runVpnLoop(fd: Int)
    external fun runPassiveShield(fd: Int)
    external fun getNativeBlockedCount(): Long
    external fun getCoreHealth(): String?
    external fun getEnergySavings(): String?
    external fun setBandwidthLimit(limitMbps: Int)
    external fun toggleStealthMode(enabled: Boolean)
    external fun setOutlineKey(key: String)
    external fun setAllowedDomains(domains: String)
    external fun setAllowedUids(uids: LongArray)

    fun isAvailable() = isLibLoaded

    @JvmStatic
    fun nativeLog(msg: String) {
        TrafficEvent.log("NATIVE >> $msg")
    }
}
