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
            TrafficEvent.log("USER >> NOTIFICATION_STOP_SIGNAL")
            stopVpn()
            return START_NOT_STICKY
        }

        // 1. MANDATORY: Promote to foreground immediately
        createNotificationChannel()
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "FG promotion failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning) return START_STICKY

        // 2. Start Service Logic
        isRunning = true
        
        if (vpnThread == null || !vpnThread!!.isAlive) {
            vpnThread = Thread(this, "IgyVpnThread")
            vpnThread?.start()
        }

        // 3. Start Background Monitor
        startMonitorLoop()

        return START_STICKY
    }

    private fun startMonitorLoop() {
        serviceScope.launch {
            var lastSubCheck = 0L
            while (isActive && isRunning) {
                if (IgyNetwork.isAvailable()) {
                    try {
                        TrafficEvent.updateCount(IgyNetwork.getNativeBlockedCount())
                    } catch (e: Throwable) {}
                }
                
                val now = System.currentTimeMillis()
                if (now - lastSubCheck >= 4 * 60 * 60 * 1000L) {
                    lastSubCheck = now
                    checkSubscription()
                }
                delay(3000)
            }
        }
    }

    private suspend fun checkSubscription() {
        val (token, _, _) = IgyPreferences.getAuth(this)
        val serverUrl = IgyPreferences.getSyncEndpoint(this) ?: "https://egi-67tg.onrender.com"
        if (token.isNotEmpty()) {
            val config = fetchVpnConfigSync(serverUrl, token, IgyPreferences.getSelectedNodeId(this))
            if (config == "EXPIRED" || config == "UNAUTHORIZED") {
                TrafficEvent.log("CORE >> SUBSCRIPTION_REVOKED")
                stopVpn()
            }
        }
    }

    override fun run() {
        try {
            // A. KEY SYNC (Right before establishment)
            val (token, _, _) = IgyPreferences.getAuth(this)
            val serverUrl = IgyPreferences.getSyncEndpoint(this) ?: "https://egi-67tg.onrender.com"
            val nodeId = IgyPreferences.getSelectedNodeId(this)
            
            if (token.isNotEmpty()) {
                val latestKey = fetchVpnConfigSync(serverUrl, token, nodeId)
                if (latestKey != null && latestKey.startsWith("ss://")) {
                    IgyPreferences.saveOutlineKey(this, latestKey)
                }
            }

            val ssKey = IgyPreferences.getOutlineKey(this)
            
            // Safety: Don't blackhole the user's internet if the key is empty
            if (ssKey.isEmpty()) {
                TrafficEvent.log("CORE >> NO_KEY: SHIELD_ABORTED")
                stopVpn()
                return
            }

            // B. ESTABLISH TUNNEL
            val builder = Builder()
                .setSession("IgyShield")
                .addAddress("10.0.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .setMtu(1280)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

            if (IgyPreferences.getLocalBypass(this)) builder.allowBypass()
            builder.addDnsServer("1.1.1.1")
            
            // Safety: Disallow the app itself to prevent recursive loops
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

            val isStealth = IgyPreferences.isStealthMode(this)
            val isGlobal = IgyPreferences.isVpnTunnelGlobal(this)
            val vipList = IgyPreferences.getVipList(this)

            if (isStealth && !isGlobal && vipList.isNotEmpty()) {
                TrafficEvent.log("SHIELD >> FOCUS_MODE: ACTIVE")
                vipList.forEach { try { builder.addAllowedApplication(it) } catch (e: Exception) {} }
            } else if (!isStealth && vipList.isNotEmpty()) {
                TrafficEvent.log("SHIELD >> BYPASS_MODE: ACTIVE")
                vipList.forEach { try { builder.addDisallowedApplication(it) } catch (e: Exception) {} }
            } else {
                TrafficEvent.log("SHIELD >> VPN_SHIELD: ACTIVE")
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                TrafficEvent.log("CORE >> KERNEL_REJECTED")
                return
            }

            TrafficEvent.setVpnActive(true)
            TrafficEvent.log("CORE >> SHIELD_UP")

            // C. HANDOVER TO NATIVE ENGINE
            val fd = vpnInterface!!.fd
            if (IgyNetwork.isAvailable()) {
                IgyNetwork.setAllowedDomains(IgyPreferences.getAllowedDomains(this))
                IgyNetwork.setOutlineKey(ssKey)
                if (ssKey.isNotEmpty()) {
                    IgyNetwork.runVpnLoop(fd)
                } else {
                    TrafficEvent.log("CORE >> PASSIVE_MODE")
                    IgyNetwork.runPassiveShield(fd)
                }
            } else {
                TrafficEvent.log("CORE >> ENGINE_OFFLINE")
                while (isRunning) { Thread.sleep(2000) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native thread panic", e)
            TrafficEvent.log("CORE >> FATAL_ERROR")
        } finally {
            stopVpn()
        }
    }

    private fun stopVpn() {
        if (!isRunning) {
            TrafficEvent.log("CORE >> ALREADY_OFFLINE")
            return
        }
        isRunning = false
        TrafficEvent.setVpnActive(false)
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        stopSelf()
        TrafficEvent.log("CORE >> SHIELD_DOWN")
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
            } else if (conn.responseCode == 403) return "EXPIRED"
            else if (conn.responseCode == 401) return "UNAUTHORIZED"
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
        val stopIntent = Intent(this, IgyVpnService::class.java).apply { action = ACTION_STOP }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "igy_vpn") else Notification.Builder(this)
        return builder.setContentTitle("Igy Shield Active")
            .setSmallIcon(R.drawable.ic_shield_status) // Custom brand-appropriate icon
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }
}
