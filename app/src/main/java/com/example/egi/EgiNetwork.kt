package com.example.egi

object EgiNetwork {
    init {
        System.loadLibrary("egi_core")
    }

    external fun measureNetworkStats(targetIp: String): String
    external fun scanSubnet(baseIp: String): String
}
