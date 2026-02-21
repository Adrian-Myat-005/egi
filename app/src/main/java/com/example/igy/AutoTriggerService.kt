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
    private var wasAutoStarted = false // Track if we started the VPN

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job == null || !job!!.isActive) {
            startMonitoring()
        }
        return START_STICKY
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
                            // TARGET APP OPENED
                            if (!isVpnRunning) {
                                TrafficEvent.log("AUTO >> TRIGGERED_BY: $currentApp")
                                startVpn()
                                wasAutoStarted = true
                            }
                        } else {
                            // OTHER APP OR HOME OPENED
                            if (isVpnRunning && wasAutoStarted) {
                                TrafficEvent.log("AUTO >> TARGET_CLOSED: STOPPING_SHIELD")
                                stopVpn()
                                wasAutoStarted = false
                            }
                        }
                    }
                }
                delay(1500) // Slightly faster loop for better response
            }
        }
    }

    private fun getForegroundApp(usm: UsageStatsManager): String? {
        val time = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time)
        if (stats != null && stats.isNotEmpty()) {
            val sortedStats = stats.sortedByDescending { it.lastTimeUsed }
            return sortedStats[0].packageName
        }
        return null
    }

    private fun startVpn() {
        // 1. Check if VPN is actually authorized (User might have revoked it)
        val vpnIntent = android.net.VpnService.prepare(this)
        if (vpnIntent != null) {
            TrafficEvent.log("AUTO >> ERR: VPN_UNAUTHORIZED")
            // In a real app, we'd show a notification here.
            return
        }

        try {
            TrafficEvent.log("AUTO >> INITIALIZING_SHIELD_TUNNEL")
            val intent = Intent(this, IgyVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("AutoTrigger", "Failed to start VPN", e)
        }
    }

    private fun stopVpn() {
        try {
            val intent = Intent(this, IgyVpnService::class.java).apply {
                action = IgyVpnService.ACTION_STOP
            }
            startService(intent)
        } catch (e: Exception) {
            Log.e("AutoTrigger", "Failed to stop VPN", e)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
