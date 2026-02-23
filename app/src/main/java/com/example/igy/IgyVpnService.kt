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
    private var sleepJob: Job? = null

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    sleepJob?.cancel()
                    sleepJob = serviceScope.launch {
                        delay(60 * 60 * 1000L) // 1 Hour
                        if (isRunning && IgyPreferences.isAutoStartTriggerEnabled(this@IgyVpnService)) {
                            TrafficEvent.log("GUARD >> SLEEPING_TO_SAVE_BATTERY")
                            stopVpnTunnelOnly()
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    sleepJob?.cancel()
                    if (isRunning && IgyPreferences.isAutoStartTriggerEnabled(this@IgyVpnService)) {
                        if (vpnInterface == null) {
                            TrafficEvent.log("GUARD >> WAKING_UP...")
                            restartVpn()
                        }
                    }
                }
            }
        }
    }

    private fun stopVpnTunnelOnly() {
        try {
            vpnInterface?.close()
            vpnInterface = null
            TrafficEvent.setVpnActive(false)
            updateNotification("GUARD: SLEEPING (SAVE BATTERY)")
        } catch (e: Exception) {}
    }

    private fun restartVpn() {
        if (!isRunning) return
        serviceScope.launch {
            // ATOMIC TUNNELING: Prevent race conditions
            synchronized(this@IgyVpnService) {
                if (vpnThread?.isAlive == true) {
                    isRunning = false
                    try { vpnInterface?.close() } catch (e: Exception) {}
                    // Wait for old thread to die
                    runBlocking { delay(300) }
                }
                isRunning = true
                updateNotification("Igy Shield: ACTIVE")
                vpnThread = Thread(this@IgyVpnService, "IgyVpnThread")
                vpnThread?.start()
            }
        }
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, createNotification(content))
    }

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
        android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, IgyTileService::class.java))
        
        // Register Screen Listener
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

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
            
            // Safety: Disallow the app itself to prevent recursive loops
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

            val isStealth = IgyPreferences.isStealthMode(this)
            val isGlobal = IgyPreferences.isVpnTunnelGlobal(this)
            val isMasterGuard = IgyPreferences.isAutoStartTriggerEnabled(this)
            val isSmartFilterMode = IgyPreferences.isSmartFilterActive(this)
            val autoStartApps = IgyPreferences.getAutoStartApps(this)

            // --- THE FOUR PILLARS ---
            when {
                // 1. SMART FILTER (Pillar 4): Activated by Tile Tap
                isMasterGuard && isSmartFilterMode && autoStartApps.isNotEmpty() -> {
                    TrafficEvent.log("SMART_FILTER >> ACTIVE")
                    autoStartApps.forEach { pkg ->
                        try { builder.addAllowedApplication(pkg) } catch (e: Exception) {}
                    }
                }
                // 2. NORMAL FOCUS (Pillar 1): Set via Hub/App
                isMasterGuard && !isStealth && !isSmartFilterMode -> {
                    TrafficEvent.log("NORMAL_FOCUS >> ACTIVE")
                    val vipList = IgyPreferences.getVipList(this)
                    vipList.forEach { 
                        try { builder.addDisallowedApplication(it) } catch (e: Exception) {} 
                    }
                }
                // 3. VPN FOCUS (Pillar 3): Set via Hub/App
                isMasterGuard && isStealth && !isGlobal -> {
                    TrafficEvent.log("VPN_FOCUS >> ACTIVE")
                    val vipList = IgyPreferences.getVipList(this)
                    val uids = mutableListOf<Long>()
                    vipList.forEach { pkg ->
                        try {
                            builder.addAllowedApplication(pkg)
                            val uid = packageManager.getPackageUid(pkg, 0)
                            uids.add(uid.toLong())
                        } catch (e: Exception) {}
                    }
                    if (IgyNetwork.isAvailable()) {
                        IgyNetwork.setAllowedUids(uids.toLongArray())
                    }
                }
                // 4. GLOBAL VPN (Pillar 2): Set via Hub/App
                isMasterGuard && isStealth && isGlobal -> {
                    TrafficEvent.log("VPN >> GLOBAL_PROTECTION")
                    if (IgyNetwork.isAvailable()) {
                        IgyNetwork.setAllowedUids(longArrayOf())
                    }
                }
                else -> {
                    TrafficEvent.log("VPN >> STANDBY_MODE")
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                TrafficEvent.log("CORE >> KERNEL_REJECTED")
                return
            }

            TrafficEvent.setVpnActive(true)
            TrafficEvent.log("CONNECTED")

            // C. HANDOVER TO NATIVE ENGINE
            val fd = vpnInterface!!.fd
            if (IgyNetwork.isAvailable()) {
                IgyNetwork.setAllowedDomains(IgyPreferences.getAllowedDomains(this))
                IgyNetwork.setOutlineKey(ssKey)

                if (!isStealth) {
                    // NORMAL FOCUS: Always use Passive Shield (to swallow background traffic)
                    TrafficEvent.log("NORMAL_FOCUS >> ENGAGED")
                    IgyNetwork.runPassiveShield(fd)
                } else if (ssKey.isNotEmpty()) {
                    // VPN MODES (Global/Focus): Use VpnLoop if key is present
                    IgyNetwork.runVpnLoop(fd)
                } else {
                    // Fallback to Passive Shield if no key is found
                    TrafficEvent.log("VPN >> PASSIVE_MODE: NO_KEY")
                    IgyNetwork.runPassiveShield(fd)
                }
            } else {
                TrafficEvent.log("ENGINE_OFFLINE")
                while (isRunning) { Thread.sleep(2000) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native thread panic", e)
            TrafficEvent.log("FATAL_ERROR")
        } finally {
            stopVpn()
        }
    }

    private fun stopVpn() {
        if (!isRunning) {
            TrafficEvent.log("ALREADY_OFFLINE")
            return
        }
        isRunning = false
        TrafficEvent.setVpnActive(false)
        IgyPreferences.setSmartFilterActive(this, false) // Ensure flag is reset
        
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        sleepJob?.cancel()

        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        
        android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, IgyTileService::class.java))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        stopSelf()
        TrafficEvent.log("DISCONNECTED")
    }

    private fun fetchVpnConfigSync(serverUrl: String, token: String, nodeId: Int): String? {
        try {
            val url = java.net.URL("$serverUrl/api/vpn/config${if (nodeId != -1) "?nodeId=$nodeId" else ""}")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
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

    private fun createNotification(title: String = "Igy Shield Active"): Notification {
        val stopIntent = Intent(this, IgyVpnService::class.java).apply { action = ACTION_STOP }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags)
        
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "igy_vpn")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val stopAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            Notification.Action.Builder(null, "STOP", stopPendingIntent).build()
        } else {
            null
        }

        builder.setContentTitle(title)
            .setSmallIcon(R.drawable.ic_shield_status)
            .setOngoing(true)
        
        if (stopAction != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            builder.addAction(stopAction)
        } else {
            @Suppress("DEPRECATION")
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
        }

        return builder.build()
    }

    override fun onDestroy() {
        isRunning = false
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }
}
