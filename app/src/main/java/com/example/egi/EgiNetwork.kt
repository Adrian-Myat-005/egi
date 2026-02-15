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

    fun isAvailable() = isLibLoaded
}
