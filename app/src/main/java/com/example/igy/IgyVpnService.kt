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

    // "Auto VPN" state
    private var isAutoModeActive = false
    private var lastForegroundApp = ""
    @Volatile private var isTunnelEstablished = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TrafficEvent.log("USER >> STOP_SIGNAL")
            stopVpn()
            return START_NOT_STICKY
        }

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

        if (isRunning) return START_STICKY

        isRunning = true
        android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, IgyTileService::class.java))

        isAutoModeActive = IgyPreferences.isAutoStartTriggerEnabled(this)
        
        if (isAutoModeActive) {
            TrafficEvent.log("DYNAMIC_SHIELD >> ARMED")
            startAutoMonitor()
        } else {
            TrafficEvent.log("MANUAL_MODE >> ENGAGED")
            startVpnTunnel()
        }

        return START_STICKY
    }

    private fun startAutoMonitor() {
        val targetApps = IgyPreferences.getAutoStartApps(this)
        val targetName = if (targetApps.size == 1) targetApps.first() else "${targetApps.size} APPS"
        updateNotification("READY >> Watching for $targetName")
        
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            
            while (isActive && isRunning && isAutoModeActive) {
                try {
                    val currentTargets = IgyPreferences.getAutoStartApps(this@IgyVpnService)
                    if (currentTargets.isEmpty()) {
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
                            handleAppSwitch(currentApp, currentTargets)
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
                        updateNotification("SHIELDING >> $currentApp (ALLOCATED)")
                        establishTunnel()
                    }
                } else {
                    if (isTunnelEstablished) {
                        TrafficEvent.log("EXITED >> $currentApp")
                        val targetName = if (targetApps.size == 1) targetApps.first() else "${targetApps.size} APPS"
                        updateNotification("READY >> Watching for $targetName")
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
            TrafficEvent.setVpnActive(true)
            
            val isStealth = IgyPreferences.isStealthMode(this) // True = VPN, False = Normal/Local
            val isGlobal = IgyPreferences.isVpnTunnelGlobal(this)
            
            // B. ESTABLISH TUNNEL
            val builder = Builder()
                .setSession("IgyShield")
                .setMtu(1280)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

            // --- THE ARCHITECT'S LOGIC ---
            
            if (isStealth) {
                // MODES: GLOBAL VPN or VPN FOCUS
                builder.addAddress("10.0.0.1", 24)
                builder.addRoute("0.0.0.0", 0)
                builder.addAddress("fd00::1", 128)
                builder.addRoute("::", 0)
                builder.addDnsServer("1.1.1.1")
                
                if (isGlobal) {
                    TrafficEvent.log("MODE >> GLOBAL_VPN")
                    try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
                } else {
                    val targetApps = if (isAutoModeActive) IgyPreferences.getAutoStartApps(this) else {
                        val focusTarget = IgyPreferences.getFocusTarget(this)
                        if (!focusTarget.isNullOrEmpty()) setOf(focusTarget) else IgyPreferences.getVipList(this)
                    }
                    targetApps.filterNotNull().forEach { try { builder.addAllowedApplication(it) } catch (e: Exception) {} }
                }
            } else {
                // MODE: NORMAL FOCUS (LOCAL ENHANCEMENT - NO VPN SERVER)
                TrafficEvent.log("MODE >> NORMAL_ENHANCEMENT")
                
                // Address/Route required to make the TUN active
                builder.addAddress("10.8.0.1", 32)
                builder.addRoute("0.0.0.0", 0)
                
                // CRITICAL: Block EVERYTHING except the target apps
                val targetApps = if (isAutoModeActive) IgyPreferences.getAutoStartApps(this) else {
                    val focusTarget = IgyPreferences.getFocusTarget(this)
                    if (!focusTarget.isNullOrEmpty()) setOf(focusTarget) else IgyPreferences.getVipList(this)
                }
                
                // Allow the target app to BYPASS the VPN (Full ISP Speed)
                targetApps.filterNotNull().forEach { try { builder.addDisallowedApplication(it) } catch (e: Exception) {} }
                // Allow this app to bypass (Syncing/Logs)
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                isTunnelEstablished = false
                return
            }

            // C. HANDOVER
            val fd = vpnInterface!!.fd
            if (IgyNetwork.isAvailable()) {
                if (isStealth) {
                    // Use Shadowsocks for VPN modes
                    val ssKey = IgyPreferences.getOutlineKey(this)
                    if (ssKey.isNotEmpty()) {
                        IgyNetwork.setOutlineKey(ssKey)
                        IgyNetwork.runVpnLoop(fd)
                    } else {
                        TrafficEvent.log("VPN_ERR >> NO_KEY")
                        IgyNetwork.runPassiveShield(fd) // Kill traffic if no key
                    }
                } else {
                    // Use Passive Shield as a "Black Hole" for background apps in Normal Mode
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
