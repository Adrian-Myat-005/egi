package com.example.egi

class EgiNetwork {
    companion object {
        init {
            System.loadLibrary("egi_core")
        }
    }

    external fun measureNetworkStats(): String
}
