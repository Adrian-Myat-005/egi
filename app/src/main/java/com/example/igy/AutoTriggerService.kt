package com.example.igy

import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.util.*

class AutoTriggerService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    
    // Track if we auto-started the VPN to avoid closing it if manually started
    @Volatile private var wasAutoStarted = false 

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job == null || !job!!.isActive) {
            TrafficEvent.log("AUTO >> MONITOR_INITIATED")
            startMonitoring()
        }
        return START_STICKY // Ensure service restarts if killed
    }

    private fun startMonitoring() {
        job = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            
            while (isActive) {
                if (powerManager.isInteractive) {
                    val currentApp = getForegroundApp(usageStatsManager)
                    val triggerApps = IgyPreferences.getAutoStartApps(applicationContext)
                    val isVpnRunning = IgyVpnService.isRunning

                    if (currentApp != null && currentApp != packageName) {
                        if (triggerApps.contains(currentApp)) {
                            // --- TARGET APP DETECTED ---
                            if (!isVpnRunning) {
                                TrafficEvent.log("AUTO >> TARGET_DETECTED: $currentApp")
                                startVpn()
                                wasAutoStarted = true
                            }
                        } else {
                            // --- OTHER APP OR HOME ---
                            if (isVpnRunning && wasAutoStarted) {
                                TrafficEvent.log("AUTO >> TARGET_LEFT: STOPPING_TUNNEL")
                                stopVpn()
                                wasAutoStarted = false // CRITICAL: Reset state for next open
                            } else if (!isVpnRunning) {
                                // Ensure state is reset even if VPN was closed manually
                                wasAutoStarted = false
                            }
                        }
                    }
                }
                delay(1200) // Optimal balance between battery and speed
            }
        }
    }

    private fun getForegroundApp(usm: UsageStatsManager): String? {
        val time = System.currentTimeMillis()
        // Increase time window to 15 seconds to avoid empty stats on quick switches
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 15, time)
        if (stats != null && stats.isNotEmpty()) {
            val sortedStats = stats.sortedByDescending { it.lastTimeUsed }
            return sortedStats[0].packageName
        }
        return null
    }

    private fun startVpn() {
        try {
            // Check if already authorized
            if (android.net.VpnService.prepare(this) == null) {
                val intent = Intent(this, IgyVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                TrafficEvent.log("AUTO >> ERR: VPN_REAUTHORIZATION_NEEDED")
            }
        } catch (e: Exception) {
            Log.e("AutoTrigger", "Start fail", e)
        }
    }

    private fun stopVpn() {
        try {
            val intent = Intent(this, IgyVpnService::class.java).apply {
                action = IgyVpnService.ACTION_STOP
            }
            startService(intent)
        } catch (e: Exception) {
            Log.e("AutoTrigger", "Stop fail", e)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        job = null
        TrafficEvent.log("AUTO >> MONITOR_STOPPED")
        super.onDestroy()
    }
}
        serviceScope.cancel()
        super.onDestroy()
    }
}
