package com.example.igy

import android.util.Log
import android.os.Build

object IgyNetwork {
    private var isLibLoaded = false
    private const val TAG = "IgyNetwork"

    init {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        if (abi.contains("arm64") || abi.contains("aarch64")) {
            try {
                System.loadLibrary("igy_core")
                isLibLoaded = true
                Log.i(TAG, "Native engine loaded successfully for $abi")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native library missing for $abi")
            } catch (t: Throwable) {
                Log.e(TAG, "Native init fatal: ${t.message}")
            }
        } else {
            Log.e(TAG, "CRITICAL: Device ABI $abi is NOT supported by current build.")
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
