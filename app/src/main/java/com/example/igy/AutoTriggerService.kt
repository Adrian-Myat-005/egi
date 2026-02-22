package com.example.igy

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.*

class AutoTriggerService : Service() {
    companion object {
        const val ACTION_STOP_MONITOR = "com.example.igy.STOP_MONITOR"
        const val ACTION_HEARTBEAT = "com.example.igy.HEARTBEAT"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    
    private val NOTIFICATION_ID = 888
    private val CHANNEL_ID = "igy_auto_monitor"
    
    @Volatile private var wasAutoStarted = false 
    private var lastEventCheckTime = System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scheduleHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITOR) {
            TrafficEvent.log("USER >> MONITOR_DISABLED_VIA_NOTIF")
            IgyPreferences.setAutoStartTriggerEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote to foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createMonitoringNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createMonitoringNotification())
        }
        
        if (job == null || !job!!.isActive) {
            TrafficEvent.log("AUTO >> INSTANT_MONITOR_ACTIVE")
            startInstantMonitoring()
        }
        return START_STICKY 
    }

    private fun startInstantMonitoring() {
        job = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            
            while (isActive) {
                if (powerManager.isInteractive) {
                    val currentApp = getForegroundAppInstant(usageStatsManager)
                    if (currentApp != null) {
                        handleAppSwitch(currentApp)
                    }
                    delay(1000) // Fast enough for instant feel, slow enough for battery
                } else {
                    delay(5000) // Screen off: slow down
                }
            }
        }
    }

    private fun handleAppSwitch(packageName: String) {
        if (packageName == this.packageName) return
        
        val triggerApps = IgyPreferences.getAutoStartApps(applicationContext)
        val isVpnRunning = IgyVpnService.isRunning

        if (triggerApps.contains(packageName)) {
            if (!isVpnRunning) {
                TrafficEvent.log("AUTO-START >> APP_DETECTED: $packageName")
                updateNotification("Waking up for $packageName...", true)
                startVpn()
                wasAutoStarted = true
            } else {
                updateNotification(null, false)
            }
        } else {
            if (isVpnRunning && wasAutoStarted) {
                TrafficEvent.log("AUTO-START >> TARGET_LEFT: ENTERING_SLEEP")
                stopVpn()
                wasAutoStarted = false
                updateNotification("Entering Sleep Mode...", true)
            } else if (!isVpnRunning) {
                wasAutoStarted = false
                updateNotification(null, false)
            }
        }
    }

    private fun getForegroundAppInstant(usm: UsageStatsManager): String? {
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

    private fun updateNotification(status: String?, showProgress: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = if (status == null) createMonitoringNotification() else createStatusNotification(status, showProgress)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createMonitoringNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, AutoTriggerService::class.java).apply { action = ACTION_STOP_MONITOR }
        val stopPendingIntent = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Igy Shield Monitor")
            .setContentText("Ghost mode active")
            .setSmallIcon(R.drawable.ic_dog_status)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pendingIntent)
            .addAction(0, "DISABLE MONITOR", stopPendingIntent)
            .build()
    }

    private fun createStatusNotification(status: String, showProgress: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Igy Shield")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_dog_status)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        
        if (showProgress) builder.setProgress(0, 0, true)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto-Start Monitor",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the VPN ready to wake up instantly."
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun scheduleHeartbeat() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AutoTriggerService::class.java).apply { action = ACTION_HEARTBEAT }
        val pendingIntent = PendingIntent.getService(this, 999, intent, PendingIntent.FLAG_IMMUTABLE)
        
        // Heartbeat every 15 minutes to stay alive on aggressive OS
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            pendingIntent
        )
    }

    private fun startVpn() {
        if (android.net.VpnService.prepare(this) == null) {
            val intent = Intent(this, IgyVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, IgyVpnService::class.java).apply { action = IgyVpnService.ACTION_STOP }
        startService(intent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        job = null
        TrafficEvent.log("AUTO >> MONITOR_OFFLINE")
        super.onDestroy()
    }
}
