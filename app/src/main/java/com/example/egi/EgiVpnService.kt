package com.example.egi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.content.pm.ServiceInfo
import kotlinx.coroutines.*
import java.io.IOException

class EgiVpnService : VpnService(), Runnable {
    companion object {
        const val ACTION_STOP = "com.example.egi.STOP"
        const val ACTION_RESTART = "com.example.egi.RESTART"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isServiceActive = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var connectivityManager: ConnectivityManager? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            TrafficEvent.log("NETWORK >> AVAILABLE")
            if (isServiceActive && (vpnThread == null || !vpnThread!!.isAlive)) {
                startVpnThread()
            }
        }

        override fun onLost(network: Network) {
            TrafficEvent.log("NETWORK >> LOST")
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager?.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TrafficEvent.log("USER_COMMAND >> STOP")
            stopVpn()
            return START_NOT_STICKY
        }

        // Permission Pre-flight
        if (prepare(this) != null) {
            TrafficEvent.log("PERMISSION >> REVOKED")
            stopSelf()
            return START_NOT_STICKY
        }

        // Handle Always-on VPN
        if (intent == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TrafficEvent.log("SYSTEM >> ALWAYS_ON_RESTORE")
        }

        isServiceActive = true
        createNotificationChannel()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, createNotification())
        }

        if (vpnThread == null || !vpnThread!!.isAlive) {
            startVpnThread()
        }

        // Watchdog
        serviceScope.launch {
            while (isActive) {
                delay(5000)
                if (isServiceActive && (vpnThread == null || !vpnThread!!.isAlive)) {
                    TrafficEvent.log("WATCHDOG >> RESTART")
                    startVpnThread()
                }
                if (EgiNetwork.isAvailable()) {
                    TrafficEvent.updateCount(EgiNetwork.getNativeBlockedCount())
                }
            }
        }
        return START_STICKY
    }

    private fun startVpnThread() {
        vpnThread?.interrupt()
        TrafficEvent.resetCount()
        vpnThread = Thread(this, "EgiVpnThread")
        vpnThread?.start()
    }

    private fun stopVpn() {
        isServiceActive = false
        serviceScope.cancel()
        vpnThread?.interrupt()
        closeInterface()
        stopForeground(true)
        stopSelf()
    }

    override fun run() {
        val vipList = EgiPreferences.getVipList(this)
        val isStealth = EgiPreferences.isStealthMode(this)
        val ssKey = EgiPreferences.getOutlineKey(this)
        val allowLocal = EgiPreferences.getLocalBypass(this)
        val isGlobal = EgiPreferences.isVpnTunnelGlobal(this)

        try {
            val builder = Builder()
                .setSession("EgiShield")
                // Obscure IP to avoid 10.0.0.x conflicts
                .addAddress("10.255.0.1", 32)
                .addAddress("fdff:egi::1", 128)
                .setMtu(1500)
                .setBlocking(true)

            // KILL SWITCH: Point back to MainActivity for configuration
            val configIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this, 0, configIntent, 
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.setConfigureIntent(pendingIntent)

            if (allowLocal) builder.allowBypass()

            // DNS Selection
            val sharedPrefs = getSharedPreferences("egi_prefs", Context.MODE_PRIVATE)
            sharedPrefs.getString("dns_provider", "1.1.1.1")?.let { dns ->
                builder.addDnsServer(dns)
                // Also add IPv6 DNS to prevent leaks
                if (dns == "1.1.1.1") builder.addDnsServer("2606:4700:4700::1111")
            }

            // Split Tunneling logic
            if (!isGlobal) {
                vipList.forEach { pkg ->
                    try {
                        if (isStealth && ssKey.isNotEmpty()) builder.addAllowedApplication(pkg)
                        else builder.addDisallowedApplication(pkg)
                    } catch (e: Exception) {}
                }
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            } else {
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            }

            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                TrafficEvent.log("CORE >> ESTABLISH_FAILED")
                return
            }
            
            TrafficEvent.setVpnActive(true)

            val fd = vpnInterface!!.fd
            if (EgiNetwork.isAvailable()) {
                try {
                    val allowedDomains = EgiPreferences.getAllowedDomains(this)
                    EgiNetwork.setAllowedDomains(allowedDomains)
                    
                    if (isStealth && ssKey.isNotEmpty()) {
                        TrafficEvent.log("SHIELD >> STEALTH_ON")
                        EgiNetwork.runVpnLoop(fd)
                    } else {
                        TrafficEvent.log("SHIELD >> PASSIVE_ON")
                        EgiNetwork.runPassiveShield(fd)
                    }
                } catch (t: Throwable) {
                    TrafficEvent.log("CORE_ERROR >> NATIVE_CRASH: ${t.message}")
                }
            } else {
                TrafficEvent.log("CORE >> NATIVE_LIB_UNAVAILABLE")
                // Keep the interface open to avoid immediate restart loop
                while (isServiceActive) {
                    try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                }
            }

        } catch (e: Exception) {
            TrafficEvent.log("CORE_ERROR >> ${e.message}")
        } finally {
            TrafficEvent.setVpnActive(false)
            closeInterface()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("egi_vpn", "Egi VPN", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, EgiVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "egi_vpn")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Egi Shield Active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
            .build()
    }

    private fun closeInterface() {
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {}
    }

    override fun onDestroy() {
        connectivityManager?.unregisterNetworkCallback(networkCallback)
        stopVpn()
        super.onDestroy()
    }
}
