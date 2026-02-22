package com.example.igy

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import java.util.*

class AutoTriggerService : Service() {
    companion object {
        private const val ACTION_HEARTBEAT = "com.example.igy.HEARTBEAT"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null
    private var lastEventCheckTime = System.currentTimeMillis()
    
    @Volatile private var wasAutoStarted = false 
    @Volatile private var isScreenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    TrafficEvent.log("AUTO >> SCREEN_OFF: MONITOR_SLEEPING")
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOn = true
                    TrafficEvent.log("AUTO >> SCREEN_UNLOCKED: MONITOR_WAKING")
                    lastEventCheckTime = System.currentTimeMillis() 
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        scheduleHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob == null || !monitorJob!!.isActive) {
            TrafficEvent.log("AUTO >> MONITOR_READY")
            startSmartMonitoring()
        }
        return START_STICKY
    }

    private fun scheduleHeartbeat() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AutoTriggerService::class.java).apply { action = ACTION_HEARTBEAT }
        val pendingIntent = PendingIntent.getService(
            this, 999, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Heartbeat every 15 minutes to stay alive on aggressive OS
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            pendingIntent
        )
    }

    private fun cancelHeartbeat() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AutoTriggerService::class.java).apply { action = ACTION_HEARTBEAT }
        val pendingIntent = PendingIntent.getService(this, 999, intent, PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntent)
    }

    private fun startSmartMonitoring() {
        monitorJob = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            
            while (isActive) {
                if (isScreenOn) {
                    val currentApp = getForegroundAppFromEvents(usageStatsManager)
                    if (currentApp != null && currentApp != packageName) {
                        handleTargetApp(currentApp)
                    }
                    delay(1000) 
                } else {
                    delay(5000) 
                }
            }
        }
    }

    private fun getForegroundAppFromEvents(usm: UsageStatsManager): String? {
        val endTime = System.currentTimeMillis()
        val events = usm.queryEvents(lastEventCheckTime, endTime)
        val event = UsageEvents.Event()
        var lastPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPackage = event.packageName
            }
        }
        
        lastEventCheckTime = endTime
        return lastPackage
    }

    private fun handleTargetApp(packageName: String) {
        val triggerApps = IgyPreferences.getAutoStartApps(applicationContext)
        val isVpnRunning = IgyVpnService.isRunning

        if (triggerApps.contains(packageName)) {
            if (!isVpnRunning) {
                TrafficEvent.log("AUTO >> WAKING_UP_FOR: $packageName")
                startVpn()
                wasAutoStarted = true
            }
        } else {
            if (isVpnRunning && wasAutoStarted) {
                TrafficEvent.log("AUTO >> TARGET_LEFT: ENTERING_SLEEP")
                stopVpn()
                wasAutoStarted = false
            } else if (!isVpnRunning) {
                wasAutoStarted = false
            }
        }
    }

    private fun startVpn() {
        if (android.net.VpnService.prepare(this) == null) {
            val intent = Intent(this, IgyVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, IgyVpnService::class.java).apply {
            action = IgyVpnService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onDestroy() {
        cancelHeartbeat()
        unregisterReceiver(screenReceiver)
        serviceScope.cancel()
        monitorJob = null
        TrafficEvent.log("AUTO >> MONITOR_OFFLINE")
        super.onDestroy()
    }
}
