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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.igy.R
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
    private var watchdogJob: Job? = null
    private var animationJob: Job? = null
    private val tunnelMutex = Mutex()

    private var isAutoModeActive = false
    private var lastForegroundApp = ""
    @Volatile private var isTunnelEstablished = false
    
    private var isScreenOn = true
    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager }

    private var islandView: View? = null
    private var switchJob: Job? = null
    private val windowManager by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            TrafficEvent.log("CORE >> NETWORK_RESTORED")
            if (isRunning) {
                if (!isTunnelEstablished && !isAutoModeActive) {
                    startVpnTunnel()
                } else if (isTunnelEstablished) {
                    TrafficEvent.log("CORE >> NETWORK_ROTATION_DETECTED")
                    serviceScope.launch {
                        tunnelMutex.withLock {
                            tearDownTunnelOnly()
                            delay(1000)
                            establishTunnel()
                        }
                    }
                }
            }
        }
        override fun onLost(network: android.net.Network) {
            TrafficEvent.log("CORE >> NETWORK_LOST")
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> isScreenOn = false
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    // Poke the watchdog on wake
                    startWatchdog()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        // --- CLEAN SLATE LOGIC ---
        if (isRunning && intent?.action != "RESTART") {
            TrafficEvent.log("CORE >> RESTARTING_FOR_NEW_MODE")
            serviceScope.launch {
                tunnelMutex.withLock {
                    tearDownTunnelOnly()
                    delay(500)
                    startVpnProcess()
                }
            }
            return START_STICKY
        }

        createNotificationChannel()
        promoteToForeground("Shield Active")

        if (!isRunning) {
            isRunning = true
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenReceiver, filter)
            startWatchdog()
        }

        android.service.quicksettings.TileService.requestListeningState(this, android.content.ComponentName(this, IgyTileService::class.java))

        startVpnProcess()
        return START_STICKY
    }

    override fun onRevoke() {
        TrafficEvent.log("CORE >> VPN_REVOKED_BY_SYSTEM")
        stopVpn()
        super.onRevoke()
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive && isRunning) {
                delay(5000) // Responsive check every 5s
                
                if (isTunnelEstablished) {
                    // 1. FAST CHECK: Native status & VPN Health
                    val healthJson = try { IgyNetwork.getCoreHealth() } catch (e: Exception) { null }
                    val json = if (healthJson != null) org.json.JSONObject(healthJson) else null
                    val coreStatus = json?.optString("status") ?: "UNKNOWN"
                    val vpnHealth = json?.optInt("vpn_health", 0) ?: 0 // 1=Healthy, 2=Stalled

                    if (coreStatus == "ERROR" || vpnHealth == 2) {
                        TrafficEvent.log("WATCHDOG >> ${if (vpnHealth == 2) "VPN_STALLED" else "CORE_ERROR"}_RECOVERING")
                        tunnelMutex.withLock {
                            tearDownTunnelOnly()
                            delay(1000)
                            establishTunnel()
                        }
                        continue
                    }

                    // 2. THOROUGH CHECK: Ping test
                    if (coreStatus == "RUNNING") {
                        val isHealthy = withContext(Dispatchers.IO) {
                            try {
                                val statsJson = IgyNetwork.measureNetworkStats("1.1.1.1")
                                if (!statsJson.isNullOrEmpty()) {
                                    val json = org.json.JSONObject(statsJson)
                                    val ping = json.optInt("ping", -1)
                                    ping != -1 && ping < 3500 // 3.5s timeout
                                } else false
                            } catch (e: Exception) { false }
                        }

                        if (!isHealthy) {
                            TrafficEvent.log("WATCHDOG >> CONNECTION_STALLED_RECOVERING")
                            tunnelMutex.withLock {
                                tearDownTunnelOnly()
                                delay(500)
                                establishTunnel()
                            }
                        }
                    }
                } else if (!isAutoModeActive) {
                    TrafficEvent.log("WATCHDOG >> TUNNEL_DOWN_RECOVERING")
                    startVpnTunnel()
                }
            }
        }
    }

    private fun startVpnProcess() {
        val isGlobal = IgyPreferences.isVpnTunnelGlobal(this)
        val focusTarget = IgyPreferences.getFocusTarget(this) ?: ""
        isAutoModeActive = IgyPreferences.isAutoStartTriggerEnabled(this)
        
        if (isGlobal) {
            TrafficEvent.log("CORE >> GLOBAL_MODE_OVERRIDE")
            startVpnTunnel()
        } else if (focusTarget.isNotEmpty()) {
            TrafficEvent.log("CORE >> MANUAL_TARGET_OVERRIDE")
            startVpnTunnel()
        } else if (isAutoModeActive) {
            startAutoMonitor()
        } else {
            startVpnTunnel()
        }
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
                delay(500) // Increased responsiveness
            }
        }
    }

    private fun handleAppSwitchWithGrace(currentApp: String, prevApp: String, targetApps: Set<String>) {
        switchJob?.cancel()
        switchJob = serviceScope.launch {
            if (targetApps.contains(currentApp)) {
                val appName = getAppName(currentApp)
                TrafficEvent.log("AUTO_START >> IGNITING_FOR: $appName")
                
                // SPEED BOOT: Parallel health check and tunnel preparation
                val healthCheck = async(Dispatchers.IO) {
                    if (isTunnelEstablished) {
                        try {
                            val statsJson = IgyNetwork.measureNetworkStats("1.1.1.1")
                            val ping = if (!statsJson.isNullOrEmpty()) org.json.JSONObject(statsJson).optInt("ping", -1) else -1
                            ping != -1
                        } catch (e: Exception) { false }
                    } else false
                }

                if (!isTunnelEstablished || vpnThread?.isAlive != true || vpnInterface == null || !healthCheck.await()) {
                    TrafficEvent.setConnectionState(ConnectionState.CONNECTING)
                    tunnelMutex.withLock { 
                        if (isTunnelEstablished) tearDownTunnelOnly()
                        establishTunnel() 
                    }
                } else {
                    TrafficEvent.setConnectionState(ConnectionState.CONNECTED)
                    showIslandPopup("Vroom Connected: $appName", isConnect = true)
                }
            } else {
                // Polished Exit: 500ms grace period to allow app switching (e.g., checking notifications)
                delay(500)
                val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val time = System.currentTimeMillis()
                val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000, time)
                val activePackage = stats?.maxByOrNull { it.lastTimeUsed }?.packageName ?: ""
                
                if (!targetApps.contains(activePackage)) {
                    tunnelMutex.withLock {
                        if (isTunnelEstablished) {
                            TrafficEvent.log("AUTO_START >> IDLE_MODE")
                            showIslandPopup("Vroom: Idle", isConnect = false)
                            updateNotification("🛡️ [IGY] READY: Watching apps")
                            tearDownTunnelOnly()
                        }
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

    private suspend fun establishTunnel() = withContext(NonCancellable) {
        if (isTunnelEstablished) return@withContext
        
        TrafficEvent.setConnectionState(ConnectionState.CONNECTING)
        startLoadingAnimation("🛡️ [IGY] INITIALIZING HANDSHAKE")

        if (vpnThread?.isAlive == true) {
            try { vpnInterface?.close() } catch (e: Exception) {}
            withContext(Dispatchers.IO) { vpnThread?.join(500) }
            vpnThread = null
        }
        
        isTunnelEstablished = true
        vpnThread = Thread(this@IgyVpnService, "IgyVpnThread")
        vpnThread?.start()
    }

    private suspend fun tearDownTunnelOnly() = withContext(NonCancellable) {
        isTunnelEstablished = false
        TrafficEvent.setVpnActive(false)
        TrafficEvent.setConnectionState(ConnectionState.IDLE)
        stopLoadingAnimation()
        IgyNetwork.stopCore() // Atomic kill signal to native engine
        
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {}
        
        withContext(Dispatchers.IO) {
            // Deterministic wait for native thread to exit
            var joinAttempts = 0
            while (vpnThread?.isAlive == true && joinAttempts < 15) {
                vpnThread?.join(100)
                joinAttempts++
            }
            if (vpnThread?.isAlive == true) {
                TrafficEvent.log("CORE >> WARNING: ZOMBIE_THREAD_PERSISTS")
            }
        }
        vpnThread = null
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
            
            stopLoadingAnimation()
            updateNotification(activeLabel)
            TrafficEvent.setVpnActive(true)
            TrafficEvent.setConnectionState(ConnectionState.CONNECTED)
            if (appName.isNotEmpty()) showIslandPopup("Connected to $appName", isConnect = true)

            // LOCAL-FIRST KEY STRATEGY (Instant Connection)
            val (token, _, _) = IgyPreferences.getAuth(this)
            val serverUrl = IgyPreferences.getSyncEndpoint(this) ?: "https://egi-67tg.onrender.com"
            var activeKey = IgyPreferences.getOutlineKey(this)

            // ASYNC REFRESH: Always update in background, never block the tunnel
            if (token.isNotEmpty()) {
                serviceScope.launch {
                    try {
                        val latestKey = fetchVpnConfigSync(serverUrl, token, IgyPreferences.getSelectedNodeId(this@IgyVpnService))
                        if (latestKey != null && latestKey.startsWith("ss://")) {
                            IgyPreferences.saveOutlineKey(this@IgyVpnService, latestKey)
                            TrafficEvent.log("VPN >> KEY_REFRESHED")
                        }
                    } catch (e: Exception) {
                        TrafficEvent.log("VPN >> KEY_REFRESH_SILENT_FAIL")
                    }
                }
            }

            val builder = Builder()
                .setSession("IgyShield")
                .setMtu(1280)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

            if (isStealth) {
                if (activeKey.isEmpty()) {
                    TrafficEvent.log("VPN >> ABORT: NO_KEY_FOUND")
                    return
                }

                // Use a more common non-conflicting range
                builder.addAddress("172.19.0.1", 24).addRoute("0.0.0.0", 0)
                builder.addAddress("fd00:1::1", 128).addRoute("::", 0)
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("8.8.8.8")
                
                if (isGlobal) {
                    try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
                } else {
                    val manualTarget = IgyPreferences.getFocusTarget(this) ?: ""
                    val targetApps = if (manualTarget.isNotEmpty()) {
                        setOf(manualTarget)
                    } else if (isAutoModeActive) {
                        IgyPreferences.getAutoStartApps(this)
                    } else {
                        IgyPreferences.getVipList(this)
                    }
                    // STRICT SPLIT TUNNEL: Only selected apps get VPN
                    targetApps.filterNotNull().forEach { try { builder.addAllowedApplication(it) } catch (e: Exception) {} }
                    // Also allow the app itself to bypass for key refreshes
                    try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
                }
            } else {
                builder.addAddress("172.19.0.1", 24).addRoute("0.0.0.0", 0)
                val manualTarget = IgyPreferences.getFocusTarget(this) ?: ""
                val targetApps = if (manualTarget.isNotEmpty()) {
                    setOf(manualTarget)
                } else if (isAutoModeActive) {
                    IgyPreferences.getAutoStartApps(this)
                } else {
                    IgyPreferences.getVipList(this)
                }
                // BOOST MODE: Passive shield allows selected apps to bypass
                targetApps.filterNotNull().forEach { try { builder.addDisallowedApplication(it) } catch (e: Exception) {} }
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                TrafficEvent.log("CORE >> ERR: INTERFACE_ESTABLISH_FAILED")
                return
            }

            val fd = vpnInterface!!.fd
            if (IgyNetwork.isAvailable()) {
                if (isStealth) {
                    IgyNetwork.setOutlineKey(activeKey)
                    IgyNetwork.runVpnLoop(fd)
                } else {
                    IgyNetwork.runPassiveShield(fd)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN Panic", e)
            TrafficEvent.log("CORE >> PANIC: ${e.message}")
        } finally {
            isTunnelEstablished = false
            TrafficEvent.setVpnActive(false)
            TrafficEvent.setConnectionState(ConnectionState.IDLE)
            try { vpnInterface?.close() } catch (e: Exception) {}
            vpnInterface = null
        }
    }

    private fun fetchVpnConfigSync(serverUrl: String, token: String, nodeId: Int): String? {
        try {
            val url = java.net.URL("$serverUrl/api/vpn/config${if (nodeId != -1) "?nodeId=$nodeId" else ""}")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
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
        
        showIslandPopup("Shield Deactivated", isConnect = false)
        
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        
        monitorJob?.cancel()
        watchdogJob?.cancel()
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
        removeIsland()
        stopVpn()
        super.onDestroy()
    }

    private fun showIslandPopup(message: String, isConnect: Boolean) {
        if (!android.provider.Settings.canDrawOverlays(this)) return

        serviceScope.launch(Dispatchers.Main) {
            removeIsland()

            val isStealth = IgyPreferences.isStealthMode(this@IgyVpnService)
            val islandColor = if (isConnect) {
                if (isStealth) 0xFF20B2AA.toInt() else 0xFF2D42FF.toInt()
            } else {
                0xFFFF0000.toInt() // Red for disconnected
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 40
                windowAnimations = android.R.style.Animation_Toast
            }

            try {
                val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                val view = inflater.inflate(R.layout.island_popup, null)
                
                val textView = view.findViewById<TextView>(R.id.island_text)
                val dotView = view.findViewById<View>(R.id.island_dot)
                
                textView.text = message
                dotView.background.setTint(islandColor)

                windowManager.addView(view, params)
                islandView = view
                
                delay(2500)
                removeIsland()
            } catch (e: Exception) {}
        }
    }

    private fun removeIsland() {
        islandView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            islandView = null
        }
    }

    private fun startLoadingAnimation(baseTitle: String) {
        animationJob?.cancel()
        animationJob = serviceScope.launch {
            val frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
            var i = 0
            while (isActive) {
                updateNotification("$baseTitle ${frames[i % frames.size]}")
                i++
                delay(100)
            }
        }
    }

    private fun stopLoadingAnimation() {
        animationJob?.cancel()
        animationJob = null
    }
}
