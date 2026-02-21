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

class IgyVpnService : VpnService(), Runnable {
    companion object {
        const val ACTION_STOP = "com.example.igy.STOP"
        private const val TAG = "IgyVpnService"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isServiceActive = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TrafficEvent.log("USER >> SHIELD_STOP_CMD")
            cleanupAndStop()
            return START_NOT_STICKY
        }

        try {
            createNotificationChannel()
            // Small icon MUST be a template drawable (white on transparent)
            val iconRes = android.R.drawable.ic_menu_compass 
            val notification = createNotification(iconRes)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Foreground error", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (prepare(this) != null) {
            TrafficEvent.log("CORE >> PERMISSION_REQUIRED")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isServiceActive) {
            isServiceActive = true
            startVpnThread()
        }

        return START_STICKY
    }

    private fun startVpnThread() {
        if (vpnThread == null || !vpnThread!!.isAlive) {
            vpnThread = Thread(this, "IgyVpnThread")
            vpnThread?.start()
            
            serviceScope.launch {
                while (isActive) {
                    if (IgyNetwork.isAvailable()) {
                        try {
                            TrafficEvent.updateCount(IgyNetwork.getNativeBlockedCount())
                        } catch (e: Throwable) {}
                    }
                    delay(3000)
                }
            }
        }
    }

    private fun cleanupAndStop() {
        if (!isServiceActive && vpnInterface == null) return
        isServiceActive = false
        TrafficEvent.setVpnActive(false)
        serviceScope.cancel()
        try { vpnThread?.interrupt() } catch (e: Exception) {}
        vpnThread = null
        closeInterface()
        stopForeground(true)
        stopSelf()
    }

    override fun run() {
        try {
            establishVpn()
        } catch (e: Exception) {
            Log.e(TAG, "VPN Thread FATAL", e)
            TrafficEvent.log("CORE >> FATAL: ${e.message}")
        } finally {
            if (isServiceActive) cleanupAndStop()
        }
    }

    private fun establishVpn() {
        val vipList = IgyPreferences.getVipList(this)
        val isStealth = IgyPreferences.isStealthMode(this)
        val isGlobal = IgyPreferences.isVpnTunnelGlobal(this)
        var ssKey = IgyPreferences.getOutlineKey(this)
        val allowLocal = IgyPreferences.getLocalBypass(this)

        // DNS Resolve
        try {
            if (ssKey.startsWith("ss://")) {
                val uri = java.net.URI(ssKey.split("#")[0])
                val host = uri.host
                if (host != null && !host.matches(Regex("^[0-9.]+$"))) {
                    val address = java.net.InetAddress.getByName(host)
                    ssKey = ssKey.replace(host, address.hostAddress ?: host)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "DNS resolve fail", e) }

        val builder = Builder()
            .setSession("IgyShield")
            .addAddress("10.0.0.1", 24) 
            .addRoute("0.0.0.0", 0)
            .setMtu(1280)

        val configIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, configIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        builder.setConfigureIntent(pendingIntent)

        if (allowLocal) builder.allowBypass()
        builder.addDnsServer("1.1.1.1")
        builder.addDnsServer("8.8.8.8")
        
        try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

        if (isStealth && !isGlobal && vipList.isNotEmpty()) {
            vipList.forEach { try { builder.addAllowedApplication(it) } catch (e: Exception) {} }
        } else if (!isStealth && vipList.isNotEmpty()) {
            vipList.forEach { try { builder.addDisallowedApplication(it) } catch (e: Exception) {} }
        }

        // CRITICAL GUARD: establish() MUST be caught specifically
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Establish failed", e)
            null
        }
        
        if (vpnInterface == null) {
            TrafficEvent.log("CORE >> INTERFACE_FAIL")
            return
        }

        TrafficEvent.setVpnActive(true)
        TrafficEvent.log("CORE >> SHIELD_UP")
        
        // DETACH FD: This is the secret to stability.
        // It transfers total ownership of the FD to Rust and prevents Java GC closing it.
        val fd = vpnInterface!!.detachFd()
        
        if (IgyNetwork.isAvailable()) {
            try {
                IgyNetwork.setAllowedDomains(IgyPreferences.getAllowedDomains(this))
                IgyNetwork.setOutlineKey(ssKey)
                if (ssKey.isNotEmpty()) {
                    IgyNetwork.runVpnLoop(fd)
                } else {
                    IgyNetwork.runPassiveShield(fd)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Native crash", e)
                TrafficEvent.log("NATIVE >> ENGINE_PANIC")
            }
        } else {
            TrafficEvent.log("CORE >> ENGINE_MISSING")
            while (isServiceActive) { 
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("igy_vpn", "Igy VPN", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(iconRes: Int): Notification {
        val stopIntent = Intent(this, IgyVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "igy_vpn") else Notification.Builder(this)
        return builder.setContentTitle("Igy Shield Active")
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent).build()
    }

    private fun closeInterface() {
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
    }

    override fun onDestroy() {
        cleanupAndStop()
        super.onDestroy()
    }
}
