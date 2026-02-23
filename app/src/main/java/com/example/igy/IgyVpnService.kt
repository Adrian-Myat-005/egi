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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val tunnelMutex = Mutex()

    // State Tracking
    private var isAutoModeActive = false
    private var lastForegroundApp = ""
    @Volatile private var isTunnelEstablished = false
    private var currentModeTitle = "Igy Shield"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TrafficEvent.log("USER >> STOP_SIGNAL")
            stopVpn()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        promoteToForeground("Initializing...")

        if (isRunning) return START_STICKY

        isRunning = true
        android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, IgyTileService::class.java))

        isAutoModeActive = IgyPreferences.isAutoStartTriggerEnabled(this)
        
        if (isAutoModeActive) {
            TrafficEvent.log("AUTO_MONITOR >> START")
            startAutoMonitor()
        } else {
            TrafficEvent.log("MANUAL_MODE >> START")
            startVpnTunnel()
        }

        return START_STICKY
    }

    private fun promoteToForeground(title: String) {
        try {
            val notification = createNotification(title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "FG promotion failed", e)
        }
    }

    private fun startAutoMonitor() {
        updateNotification("IGY: Waiting for target app...")
        
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            
            while (isActive && isRunning && isAutoModeActive) {
                try {
                    val targetApps = IgyPreferences.getAutoStartApps(this@IgyVpnService)
                    if (targetApps.isEmpty()) {
                        delay(2000)
                        continue
                    }

                    val time = System.currentTimeMillis()
                    val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 5, time)
                    
                    if (stats != null && stats.isNotEmpty()) {
                        val sortedStats = TreeMap<Long, android.app.usage.UsageStats>()
                        for (usageStats in stats) {
                            sortedStats[usageStats.lastTimeUsed] = usageStats
                        }
                        
                        val currentApp = sortedStats.lastEntry()?.value?.packageName ?: ""
                        if (currentApp != lastForegroundApp) {
                            lastForegroundApp = currentApp
                            handleAppSwitch(currentApp, targetApps)
                        }
                    }
                } catch (e: Exception) {}
                delay(500)
            }
        }
    }

    private fun handleAppSwitch(currentApp: String, targetApps: Set<String>) {
        serviceScope.launch {
            tunnelMutex.withLock {
                if (targetApps.contains(currentApp)) {
                    if (!isTunnelEstablished) {
                        TrafficEvent.log("WAKING_UP >> $currentApp")
                        establishTunnel()
                    }
                } else {
                    if (isTunnelEstablished) {
                        TrafficEvent.log("EXITED >> SLEEP_MODE")
                        updateNotification("IGY: Waiting for target app...")
                        tearDownTunnelOnly()
                    }
                }
            }
        }
    }

    private fun startVpnTunnel() {
        serviceScope.launch {
            tunnelMutex.withLock {
                establishTunnel()
            }
        }
    }

    private fun establishTunnel() {
        if (isTunnelEstablished) return
        
        if (vpnThread?.isAlive == true) {
            try { vpnInterface?.close() } catch (e: Exception) {}
            vpnThread = null
        }
        
        isTunnelEstablished = true
        vpnThread = Thread(this, "IgyVpnThread")
        vpnThread?.start()
    }

    private suspend fun tearDownTunnelOnly() {
        isTunnelEstablished = false
        TrafficEvent.setVpnActive(false)
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {}
        
        withContext(Dispatchers.IO) {
            vpnThread?.join(300)
        }
    }

    override fun run() {
        try {
            val isStealth = IgyPreferences.isStealthMode(this) 
            val isGlobal = IgyPreferences.isVpnTunnelGlobal(this)
            
            // 1. SET NOTIFICATION LABEL
            val activeLabel = when {
                isStealth && isGlobal -> "🔒 [GLOBAL] VPN ACTIVE"
                isStealth -> "🔒 [FOCUS] VPN ACTIVE"
                else -> "🚀 [SPEED] BOOST ACTIVE"
            }
            updateNotification(activeLabel)
            TrafficEvent.setVpnActive(true)

            // 2. PARALLEL KEY SYNC
            val (token, _, _) = IgyPreferences.getAuth(this)
            val serverUrl = IgyPreferences.getSyncEndpoint(this) ?: "https://egi-67tg.onrender.com"
            val ssKey = IgyPreferences.getOutlineKey(this)

            if (token.isNotEmpty()) {
                serviceScope.launch {
                    val latestKey = fetchVpnConfigSync(serverUrl, token, IgyPreferences.getSelectedNodeId(this@IgyVpnService))
                    if (latestKey != null && latestKey.startsWith("ss://")) {
                        IgyPreferences.saveOutlineKey(this@IgyVpnService, latestKey)
                    }
                }
            }

            // 3. BUILDER
            val builder = Builder()
                .setSession("IgyShield")
                .setMtu(1280)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

            if (isStealth) {
                // MODES: VPN (Global or Focus)
                builder.addAddress("10.0.0.1", 24).addRoute("0.0.0.0", 0)
                builder.addAddress("fd00::1", 128).addRoute("::", 0)
                builder.addDnsServer("1.1.1.1")
                
                if (isGlobal) {
                    // NO FILTERS = GLOBAL
                    TrafficEvent.log("CORE >> GLOBAL_VPN_ESTABLISHED")
                    try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
                } else {
                    // SPLIT TUNNEL FOR FOCUS
                    val targetApps = if (isAutoModeActive) IgyPreferences.getAutoStartApps(this) else {
                        val focusTarget = IgyPreferences.getFocusTarget(this)
                        if (!focusTarget.isNullOrEmpty()) setOf(focusTarget) else IgyPreferences.getVipList(this)
                    }
                    targetApps.filterNotNull().forEach { try { builder.addAllowedApplication(it) } catch (e: Exception) {} }
                }
            } else {
                // MODE: NORMAL ENHANCEMENT (SPEED BOOST)
                builder.addAddress("10.8.0.1", 32).addRoute("0.0.0.0", 0)
                
                // Block background apps (route to TUN), Let selected app BYPASS (Raw ISP Speed)
                val targetApps = if (isAutoModeActive) IgyPreferences.getAutoStartApps(this) else {
                    val focusTarget = IgyPreferences.getFocusTarget(this)
                    if (!focusTarget.isNullOrEmpty()) setOf(focusTarget) else IgyPreferences.getVipList(this)
                }
                targetApps.filterNotNull().forEach { try { builder.addDisallowedApplication(it) } catch (e: Exception) {} }
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                isTunnelEstablished = false
                return
            }

            // 4. HANDOVER TO NATIVE
            val fd = vpnInterface!!.fd
            if (IgyNetwork.isAvailable()) {
                if (isStealth) {
                    val activeKey = IgyPreferences.getOutlineKey(this)
                    if (activeKey.isNotEmpty()) {
                        IgyNetwork.setOutlineKey(activeKey)
                        IgyNetwork.runVpnLoop(fd)
                    } else {
                        IgyNetwork.runPassiveShield(fd)
                    }
                } else {
                    // Normal Mode: Native engine acts as a packet sink for background noise
                    IgyNetwork.runPassiveShield(fd)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN Panic", e)
        } finally {
            isTunnelEstablished = false
            TrafficEvent.setVpnActive(false)
            try { vpnInterface?.close() } catch (e: Exception) {}
        }
    }

    private fun fetchVpnConfigSync(serverUrl: String, token: String, nodeId: Int): String? {
        try {
            val url = java.net.URL("$serverUrl/api/vpn/config${if (nodeId != -1) "?nodeId=$nodeId" else ""}")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) {
                return org.json.JSONObject(conn.inputStream.bufferedReader().readText()).getString("config")
            }
        } catch (e: Exception) {}
        return null
    }

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        isAutoModeActive = false
        
        monitorJob?.cancel()
        serviceScope.launch {
            tunnelMutex.withLock {
                tearDownTunnelOnly()
                serviceScope.cancel()
            }
        }
        
        android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, IgyTileService::class.java))
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("igy_vpn", "Igy VPN", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String): Notification {
        val stopIntent = Intent(this, IgyVpnService::class.java).apply { action = ACTION_STOP }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags)
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "igy_vpn")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle(title)
            .setSmallIcon(R.drawable.ic_shield_status)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
            .build()
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
