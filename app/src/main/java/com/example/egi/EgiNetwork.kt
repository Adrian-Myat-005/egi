package com.example.egi

object EgiNetwork {
    init {
        System.loadLibrary("egi_core")
    }

    external fun measureNetworkStats(): String
    external fun scanSubnet(baseIp: String): String
}
