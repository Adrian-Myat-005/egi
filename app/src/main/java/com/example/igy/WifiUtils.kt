package com.example.igy

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter

object WifiUtils {
    fun getGatewayIp(context: Context): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wm.dhcpInfo.gateway)
    }

    fun getSubnetPrefix(context: Context): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        return if (ipAddress.isNotEmpty() && ipAddress.contains(".")) {
            ipAddress.substring(0, ipAddress.lastIndexOf(".") + 1)
        } else {
            "192.168.1."
        }
    }
}
