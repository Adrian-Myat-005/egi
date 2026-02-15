package com.example.egi

import java.nio.ByteBuffer

object PacketUtils {
    fun getDestinationIp(packet: ByteBuffer): String {
        if (packet.remaining() < 20) return "Unknown"
        
        // IPv4 check: Version is in the first 4 bits of the first byte
        val versionAndIHL = packet.get(0).toInt()
        val version = (versionAndIHL shr 4) and 0x0F
        
        if (version != 4) return "IPv6/Other"

        // Destination IP is at offset 16-19 in IPv4 header
        val ip1 = packet.get(16).toInt() and 0xFF
        val ip2 = packet.get(17).toInt() and 0xFF
        val ip3 = packet.get(18).toInt() and 0xFF
        val ip4 = packet.get(19).toInt() and 0xFF
        
        return "$ip1.$ip2.$ip3.$ip4"
    }

    fun getProtocol(packet: ByteBuffer): String {
        if (packet.remaining() < 10) return "???"
        // Protocol is at offset 9 in IPv4 header
        return when (packet.get(9).toInt() and 0xFF) {
            6 -> "TCP"
            17 -> "UDP"
            1 -> "ICMP"
            else -> "OTH"
        }
    }
}
