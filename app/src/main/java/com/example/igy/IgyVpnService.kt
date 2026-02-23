package com.example.igy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private var isAutoModeActive = false
    private var lastForegroundApp = ""
    @Volatile private var isTunnelEstablished = false
    
    private var isScreenOn = true
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> isScreenOn = false
                Intent.ACTION_SCREEN_ON -> isScreenOn = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        promoteToForeground("Shield Active")

        if (isRunning) return START_STICKY
        isRunning = true
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, IgyTileService::class.java))

        isAutoModeActive = IgyPreferences.isAutoStartTriggerEnabled(this)
        
        if (isAutoModeActive) {
            startAutoMonitor()
        } else {
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
        } catch (e: Exception) {}
    }

    private fun startAutoMonitor() {
        updateNotification("🛡️ [IGY] READY: Watching apps")
        
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            
            while (isActive && isRunning && isAutoModeActive) {
                if (!isScreenOn) {
                    delay(3000)
                    continue
                }

                try {
                    val targetApps = IgyPreferences.getAutoStartApps(this@IgyVpnService)
                    if (targetApps.isEmpty()) {
                        delay(5000)
                        continue
                    }

                    val time = System.currentTimeMillis()
                    val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 2, time)
                    
                    if (stats != null && stats.isNotEmpty()) {
                        val sortedStats = TreeMap<Long, android.app.usage.UsageStats>()
                        for (usageStats in stats) {
                            sortedStats[usageStats.lastTimeUsed] = usageStats
                        }
                        
                        val currentApp = sortedStats.lastEntry()?.value?.packageName ?: ""
                        if (currentApp != lastForegroundApp) {
                            val prevApp = lastForegroundApp
                            lastForegroundApp = currentApp
                            
                            // GRACE PERIOD: Avoid jittery restarts if switching fast
                            handleAppSwitchWithGrace(currentApp, prevApp, targetApps)
                        }
                    }
                } catch (e: Exception) {}
                delay(1000)
            }
        }
    }

    private fun handleAppSwitchWithGrace(currentApp: String, prevApp: String, targetApps: Set<String>) {
        serviceScope.launch {
            if (targetApps.contains(currentApp)) {
                if (!isTunnelEstablished) {
                    val appName = getAppName(currentApp)
                    TrafficEvent.log("WAKING_UP >> $appName")
                    tunnelMutex.withLock { establishTunnel() }
                }
            } else {
                // Wait 300ms before killing - maybe user just checked notification or switched back
                delay(300)
                val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val time = System.currentTimeMillis()
                val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 500, time)
                val checkApp = stats?.maxByOrNull { it.lastTimeUsed }?.packageName ?: ""
                
                if (!targetApps.contains(checkApp)) {
                    if (isTunnelEstablished) {
                        TrafficEvent.log("EXITED >> STANDBY")
                        updateNotification("🛡️ [IGY] READY: Watching apps")
                        tunnelMutex.withLock { tearDownTunnelOnly() }
                    }
                }
            }
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) { packageName.split(".").last() }
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
            vpnThread?.join(500)
        }
    }

    override fun run() {
        try {
            val isStealth = IgyPreferences.isStealthMode(this) 
            val isGlobal = IgyPreferences.isVpnTunnelGlobal(this)
            
            // Dynamic Label Logic
            val currentApp = lastForegroundApp
            val appName = if (currentApp.isNotEmpty()) getAppName(currentApp) else ""
            
            val activeLabel = when {
                isStealth && isGlobal -> "🔒 [GLOBAL] VPN ACTIVE"
                isStealth -> if (appName.isNotEmpty()) "🔒 [VPN] >> $appName" else "🔒 [FOCUS] VPN ACTIVE"
                else -> if (appName.isNotEmpty()) "🚀 [BOOST] >> $appName" else "🚀 [SPEED] BOOST ACTIVE"
            }
            updateNotification(activeLabel)
            TrafficEvent.setVpnActive(true)

            // Parallel Key Sync
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

            val builder = Builder()
                .setSession("IgyShield")
                .setMtu(1280)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

            if (isStealth) {
                builder.addAddress("10.0.0.1", 24).addRoute("0.0.0.0", 0)
                builder.addAddress("fd00::1", 128).addRoute("::", 0)
                builder.addDnsServer("1.1.1.1")
                
                if (isGlobal) {
                    try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
                } else {
                    val targetApps = if (isAutoModeActive) IgyPreferences.getAutoStartApps(this) else {
                        val focusTarget = IgyPreferences.getFocusTarget(this)
                        if (!focusTarget.isNullOrEmpty()) setOf(focusTarget) else IgyPreferences.getVipList(this)
                    }
                    targetApps.filterNotNull().forEach { try { builder.addAllowedApplication(it) } catch (e: Exception) {} }
                }
            } else {
                builder.addAddress("10.8.0.1", 32).addRoute("0.0.0.0", 0)
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
        
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        
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
