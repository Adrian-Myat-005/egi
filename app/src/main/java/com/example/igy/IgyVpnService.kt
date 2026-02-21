package com.example.igy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
import android.util.Log
import kotlinx.coroutines.*

class IgyVpnService : VpnService(), Runnable {
    companion object {
        const val ACTION_STOP = "com.example.igy.STOP"
        private const val TAG = "IgyVpnService"
        @Volatile var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        // 1. Mandatory Foreground Promotion (ASAP)
        createNotificationChannel()
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start fail", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning) return START_STICKY

        // 2. Start Logic
        isRunning = true
        TrafficEvent.setVpnActive(true)
        
        if (vpnThread == null || !vpnThread!!.isAlive) {
            vpnThread = Thread(this, "IgyVpnThread")
            vpnThread?.start()
        }

        return START_STICKY
    }

    override fun run() {
        try {
            // 1. REAL-TIME KEY SYNC (Ensures latest key from Admin Dashboard)
            val (token, _, _) = IgyPreferences.getAuth(this)
            val serverUrl = IgyPreferences.getSyncEndpoint(this) ?: "https://egi-67tg.onrender.com"
            val nodeId = IgyPreferences.getSelectedNodeId(this)
            
            if (token.isNotEmpty()) {
                TrafficEvent.log("CORE >> SYNCING_KEY...")
                val latestKey = fetchVpnConfigSync(serverUrl, token, nodeId)
                if (latestKey != null && latestKey.startsWith("ss://")) {
                    IgyPreferences.saveOutlineKey(this, latestKey)
                    TrafficEvent.log("CORE >> KEY_SYNC_SUCCESS")
                } else {
                    TrafficEvent.log("CORE >> SYNC_FAIL: USING_CACHE")
                }
            }

            val ssKey = IgyPreferences.getOutlineKey(this)
            val builder = Builder()
                .setSession("IgyShield")
                .addAddress("10.0.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .setMtu(1280)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

            if (IgyPreferences.getLocalBypass(this)) builder.allowBypass()
            builder.addDnsServer("1.1.1.1")
            
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                TrafficEvent.log("CORE >> ESTABLISH_REJECTED")
                return
            }

            val fd = vpnInterface!!.fd
            if (IgyNetwork.isAvailable()) {
                IgyNetwork.setOutlineKey(ssKey)
                if (ssKey.isNotEmpty()) {
                    IgyNetwork.runVpnLoop(fd)
                } else {
                    IgyNetwork.runPassiveShield(fd)
                }
            } else {
                TrafficEvent.log("CORE >> NATIVE_ENGINE_MISSING")
                while (isRunning) { Thread.sleep(2000) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Thread panic", e)
            TrafficEvent.log("CORE >> FATAL: ${e.message}")
        } finally {
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        TrafficEvent.setVpnActive(false)
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }

    private fun fetchVpnConfigSync(serverUrl: String, token: String, nodeId: Int): String? {
        try {
            val url = java.net.URL("$serverUrl/api/vpn/config${if (nodeId != -1) "?nodeId=$nodeId" else ""}")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) {
                val res = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                return res.getString("config")
            }
        } catch (e: Exception) {}
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("igy_vpn", "Igy VPN", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(this, 0, Intent(this, IgyVpnService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "igy_vpn") else Notification.Builder(this)
        return builder.setContentTitle("Igy Shield Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Guaranteed safe public icon
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
