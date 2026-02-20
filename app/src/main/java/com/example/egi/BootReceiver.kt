package com.example.egi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val (token, _, _) = EgiPreferences.getAuth(context)
            val serverUrl = EgiPreferences.getSyncEndpoint(context) ?: "http://10.0.2.2:3000"

            if (token.isNotEmpty()) {
                // Background sync before starting service
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val url = java.net.URL("$serverUrl/api/vpn/config")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.setRequestProperty("Authorization", "Bearer $token")
                        
                        if (conn.responseCode == 200) {
                            val res = JSONObject(conn.inputStream.bufferedReader().readText())
                            val config = res.getString("config")
                            EgiPreferences.saveOutlineKey(context, config)
                            TrafficEvent.log("BOOT >> KEY_SYNC_SUCCESS")
                        }
                    } catch (e: Exception) {
                        TrafficEvent.log("BOOT >> SYNC_FAILED: ${e.message}")
                    } finally {
                        startVpnIfEnabled(context)
                    }
                }
            } else {
                startVpnIfEnabled(context)
            }
        }
    }

    private fun startVpnIfEnabled(context: Context) {
        if (EgiPreferences.getAutoStart(context)) {
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent == null) {
                ContextCompat.startForegroundService(context, Intent(context, EgiVpnService::class.java))
            }
        }
    }
}
