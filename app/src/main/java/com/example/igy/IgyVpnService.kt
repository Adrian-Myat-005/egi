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
import java.io.IOException

class IgyVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "com.example.igy.STOP"
        private const val TAG = "IgyVpnService"
        @Volatile var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        // 1. IMMEDIATE FOREGROUND PROMOTION (Android 14 Requirement)
        try {
            createNotificationChannel()
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical: startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // 2. PREVENT DOUBLE BOOT
        if (isRunning) return START_STICKY

        // 3. LAUNCH HARDENED VPN TASK
        isRunning = true
        vpnJob = serviceScope.launch {
            try {
                runVpnTask()
            } catch (e: Exception) {
                Log.e(TAG, "VPN Task Panic", e)
                TrafficEvent.log("CORE >> TASK_PANIC: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { stopVpn() }
            }
        }

        return START_STICKY
    }

    private suspend fun runVpnTask() = withContext(Dispatchers.IO) {
        val ssKey = IgyPreferences.getOutlineKey(this@IgyVpnService)
        
        // Resolve DNS if needed
        var targetKey = ssKey
        try {
            if (ssKey.startsWith("ss://")) {
                val uri = java.net.URI(ssKey.split("#")[0])
                val host = uri.host
                if (host != null && !host.matches(Regex("^[0-9.]+$"))) {
                    val address = java.net.InetAddress.getByName(host)
                    targetKey = ssKey.replace(host, address.hostAddress ?: host)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "DNS resolution skip", e) }

        // BUILDER STABILITY
        val builder = Builder()
            .setSession("IgyShield")
            .addAddress("10.0.0.1", 24)
            .addRoute("0.0.0.0", 0)
            .setMtu(1280)
            .setConfigureIntent(
                PendingIntent.getActivity(this@IgyVpnService, 0, 
                Intent(this@IgyVpnService, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )

        if (IgyPreferences.getLocalBypass(this@IgyVpnService)) builder.allowBypass()
        builder.addDnsServer("1.1.1.1")
        
        try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

        // ESTABLISH WITH GUARD
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Establish failed", e)
            null
        }

        if (vpnInterface == null) {
            TrafficEvent.log("CORE >> ESTABLISH_REJECTED")
            return@withContext
        }

        TrafficEvent.setVpnActive(true)
        TrafficEvent.log("CORE >> SHIELD_UP")

        // DETACH FD: Transfers ownership to native, prevents Java closing it
        val fd = vpnInterface!!.detachFd()
        
        // Let OS Settle
        delay(250)

        if (IgyNetwork.isAvailable()) {
            try {
                IgyNetwork.setOutlineKey(targetKey)
                if (targetKey.isNotEmpty()) {
                    IgyNetwork.runVpnLoop(fd)
                } else {
                    IgyNetwork.runPassiveShield(fd)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Native execution error", e)
            }
        } else {
            TrafficEvent.log("CORE >> ENGINE_MISSING")
            while (isRunning) { delay(2000) }
        }
    }

    private fun stopVpn() {
        isRunning = false
        TrafficEvent.setVpnActive(false)
        vpnJob?.cancel()
        closeInterface()
        stopForeground(true)
        stopSelf()
        TrafficEvent.log("CORE >> SHIELD_DOWN")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("igy_vpn", "Igy VPN", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(this, 0, 
            Intent(this, IgyVpnService::class.java).apply { action = ACTION_STOP }, 
            PendingIntent.FLAG_IMMUTABLE)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "igy_vpn") else Notification.Builder(this)
        
        return builder.setContentTitle("Igy Shield Protected")
            .setSmallIcon(android.R.drawable.stat_sys_vp_vpn) // Universal System Icon
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
            .build()
    }

    private fun closeInterface() {
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }
}
