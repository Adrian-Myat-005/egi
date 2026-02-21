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
                // Cons: Battery drain. Saving: Only poll if screen is ON
                if (powerManager.isInteractive) {
                    val currentApp = getForegroundApp(usageStatsManager)
                    if (currentApp != null && currentApp != packageName) {
                        val triggerApps = IgyPreferences.getAutoStartApps(applicationContext)
                        if (triggerApps.contains(currentApp)) {
                            if (!IgyVpnService.isRunning) {
                                TrafficEvent.log("AUTO >> TRIGGERED_BY: $currentApp")
                                startVpn()
                            }
                        }
                    }
                }
                delay(2000) // Poll every 2 seconds for efficiency
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
        try {
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

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
