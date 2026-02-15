package com.example.egi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.util.Log

class WifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
            @Suppress("DEPRECATION")
            val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
            if (networkInfo != null && networkInfo.isConnected) {
                if (EgiPreferences.isGeofencingEnabled(context)) {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val info = wifiManager.connectionInfo
                    val ssid = info?.ssid?.replace("\"", "") ?: ""
                    
                    if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                        val trustedSsids = EgiPreferences.getTrustedSSIDs(context)
                        if (!trustedSsids.contains(ssid)) {
                            Log.d("EgiGeofence", "Untrusted SSID detected: $ssid. Activating Silent Shield.")
                            val vpnIntent = Intent(context, EgiVpnService::class.java)
                            context.startService(vpnIntent)
                        } else {
                            Log.d("EgiGeofence", "Trusted SSID detected: $ssid. Shield remains in standby.")
                        }
                    }
                }
            }
        }
    }
}
