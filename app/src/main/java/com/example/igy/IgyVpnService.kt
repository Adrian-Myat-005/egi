package com.example.igy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.util.TreeMap

class IgyVpnService : VpnService(), Runnable {
    companion object {
        const val ACTION_STOP = "com.example.igy.STOP"
        private const val TAG = "IgyVpnService"
        @Volatile var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    // "Auto VPN" state
    private var isAutoModeActive = false
    private var lastForegroundApp = ""
    private var isTunnelEstablished = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TrafficEvent.log("USER >> STOP_SIGNAL")
            stopVpn()
            return START_NOT_STICKY
        }

        // 1. Promote to Foreground
        createNotificationChannel()
        try {
            val notification = createNotification("Initializing Shield...")
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

        if (isRunning) {
            // If already running, just update the mode/config if needed or ignore
            return START_STICKY
        }

        isRunning = true
        android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, IgyTileService::class.java))

        // Check if we are in "Smart Auto" mode or "Direct" mode
        isAutoModeActive = IgyPreferences.isAutoStartTriggerEnabled(this)
        
        if (isAutoModeActive) {
            TrafficEvent.log("AUTO_PILOT >> ENGAGED")
            startAutoMonitor()
        } else {
            TrafficEvent.log("MANUAL_MODE >> ENGAGED")
            startVpnTunnel()
        }

        return START_STICKY
    }

    private fun startAutoMonitor() {
        // In Auto Mode, we don't start the VPN tunnel immediately.
        // We watch for the target app.
        updateNotification("IGY: Monitor Active (Standby)")
        
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val targetApps = IgyPreferences.getAutoStartApps(this@IgyVpnService)
            
            if (targetApps.isEmpty()) {
                TrafficEvent.log("AUTO_ERR: NO_TARGETS")
                return@launch
            }

            while (isActive && isRunning && isAutoModeActive) {
                try {
                    val time = System.currentTimeMillis()
                    val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time)
                    
                    if (stats != null && stats.isNotEmpty()) {
                        val sortedStats = TreeMap<Long, android.app.usage.UsageStats>()
                        for (usageStats in stats) {
                            sortedStats[usageStats.lastTimeUsed] = usageStats
                        }
                        
                        if (sortedStats.isNotEmpty()) {
                            val currentApp = sortedStats.lastEntry()?.value?.packageName ?: ""
                            if (currentApp != lastForegroundApp) {
                                lastForegroundApp = currentApp
                                handleAppSwitch(currentApp, targetApps)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Permission likely missing
                    TrafficEvent.log("MONITOR >> PERMISSION_MISSING")
                }
                delay(1500) // Poll every 1.5s
            }
        }
    }

    private fun handleAppSwitch(currentApp: String, targetApps: Set<String>) {
        if (targetApps.contains(currentApp)) {
            if (!isTunnelEstablished) {
                TrafficEvent.log("TARGET_DETECTED >> $currentApp")
                updateNotification("IGY Shield: PROTECTING $currentApp")
                startVpnTunnel()
            }
        } else {
            // Optional: Auto-Stop if we leave the app? 
            // The prompt implies: "waking up will always alive until we off smart button again"
            // So we DO NOT stop it automatically. Once awake, it stays awake.
        }
    }

    private fun startVpnTunnel() {
        if (isTunnelEstablished) return
        
        monitorJob?.cancel() // Stop monitoring once we are live (or keep it if we want to dynamic switch)
        
        serviceScope.launch {
            synchronized(this@IgyVpnService) {
                if (vpnThread?.isAlive == true) {
                    try { vpnInterface?.close() } catch (e: Exception) {}
                    vpnThread?.join(1000)
                }
                
                updateNotification("Igy Shield: ACTIVE")
                vpnThread = Thread(this@IgyVpnService, "IgyVpnThread")
                vpnThread?.start()
            }
        }
    }

    override fun run() {
        try {
            isTunnelEstablished = true
            TrafficEvent.setVpnActive(true)
            
            // A. KEY SYNC
            val (token, _, _) = IgyPreferences.getAuth(this)
            val serverUrl = IgyPreferences.getSyncEndpoint(this) ?: "https://egi-67tg.onrender.com"
            
            if (token.isNotEmpty()) {
                val latestKey = fetchVpnConfigSync(serverUrl, token, IgyPreferences.getSelectedNodeId(this))
                if (latestKey != null && latestKey.startsWith("ss://")) {
                    IgyPreferences.saveOutlineKey(this, latestKey)
                }
            }

            val ssKey = IgyPreferences.getOutlineKey(this)
            if (ssKey.isEmpty()) {
                TrafficEvent.log("VPN >> ERR: NO_KEY_FOUND")
                return
            }
            
            // B. ESTABLISH TUNNEL
            val builder = Builder()
                .setSession("IgyShield")
                .addAddress("10.0.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00::1", 128)
                .addRoute("::", 0)
                .setMtu(1280)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

            if (IgyPreferences.getLocalBypass(this)) builder.allowBypass()
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("2606:4700:4700::1111")
            
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

            val isStealth = IgyPreferences.isStealthMode(this) // This is now "Global vs Focus" flag basically
            val isGlobal = IgyPreferences.isVpnTunnelGlobal(this)
            
            // --- THE 4 PILLARS (Re-interpreted for Stability) ---
            // 1. GLOBAL VPN
            if (isGlobal) {
                 TrafficEvent.log("MODE >> GLOBAL_VPN")
                 // Allow everything (default)
            } 
            // 2. AUTO VPN / FOCUS VPN (Split Tunnel)
            else {
                val targetApps = if (isAutoModeActive) {
                    IgyPreferences.getAutoStartApps(this)
                } else {
                    // Normal Focus / VPN Focus
                    val focusTarget = IgyPreferences.getFocusTarget(this)
                    if (!focusTarget.isNullOrEmpty()) setOf(focusTarget) else IgyPreferences.getVipList(this)
                }

                val nonNullApps = targetApps.filterNotNull()
                if (nonNullApps.isNotEmpty()) {
                    TrafficEvent.log("MODE >> SPLIT_TUNNEL (${nonNullApps.size} Apps)")
                    nonNullApps.forEach { pkg ->
                        try { builder.addAllowedApplication(pkg) } catch (e: Exception) {
                            Log.e(TAG, "Failed to allow $pkg", e)
                        }
                    }
                } else {
                    TrafficEvent.log("WARN >> NO_APPS_SELECTED")
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                TrafficEvent.log("CORE >> KERNEL_REJECTED")
                return
            }

            TrafficEvent.log("CONNECTED")

            // C. HANDOVER TO NATIVE ENGINE
            val fd = vpnInterface!!.fd
            if (IgyNetwork.isAvailable()) {
                IgyNetwork.setOutlineKey(ssKey)
                // ALWAYS USE REAL VPN LOOP. NO PASSIVE SHIELD.
                IgyNetwork.runVpnLoop(fd)
            } else {
                TrafficEvent.log("ENGINE_OFFLINE")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native thread panic", e)
            TrafficEvent.log("FATAL_ERROR")
        } finally {
            isTunnelEstablished = false
            stopVpn()
        }
    }

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        isTunnelEstablished = false
        isAutoModeActive = false
        TrafficEvent.setVpnActive(false)
        
        monitorJob?.cancel()
        serviceScope.cancel()

        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        
        android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, IgyTileService::class.java))

        stopForeground(true)
        stopSelf()
        TrafficEvent.log("DISCONNECTED")
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

    private fun createNotification(title: String = "Igy Shield Active"): Notification {
        val stopIntent = Intent(this, IgyVpnService::class.java).apply { action = ACTION_STOP }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags)
        
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "igy_vpn")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle(title)
            .setSmallIcon(R.drawable.ic_shield_status)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)

        return builder.build()
    }
    
    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, createNotification(content))
    }

    override fun onDestroy() {
        isRunning = false
        stopVpn()
        super.onDestroy()
    }
}
