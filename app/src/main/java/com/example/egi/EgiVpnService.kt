package com.example.egi

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
import kotlinx.coroutines.*
import java.io.IOException

class EgiVpnService : VpnService(), Runnable {
    companion object {
        const val ACTION_STOP = "com.example.egi.STOP"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isServiceActive = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TrafficEvent.log("USER >> SHIELD_STOP_CMD")
            stopVpn()
            return START_NOT_STICKY
        }

        if (prepare(this) != null) {
            TrafficEvent.log("CORE >> PERMISSION_MISSING")
            stopSelf()
            return START_NOT_STICKY
        }

        // Check for Always-on / Lockdown
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Note: This only works if we are the current Always-on VPN
            // For general detection, we'll also use the network check in MainActivity
        }

        isServiceActive = true
        createNotificationChannel()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            TrafficEvent.log("CORE >> FG_SERVICE_ERR: ${e.message}")
        }

        if (vpnThread == null || !vpnThread!!.isAlive) {
            vpnThread = Thread(this, "EgiVpnThread")
            vpnThread?.start()
        }

        serviceScope.launch {
            while (isActive) {
                delay(3000)
                if (EgiNetwork.isAvailable()) {
                    TrafficEvent.updateCount(EgiNetwork.getNativeBlockedCount())
                }
            }
        }
        return START_STICKY
    }

    private fun stopVpn() {
        isServiceActive = false
        TrafficEvent.setVpnActive(false)
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

        try {
            TrafficEvent.log("CORE >> INITIALIZING_BUILDER")
            val builder = Builder()
                .setSession("EgiShield")
                .addAddress("192.168.254.1", 30) // Use a less common private range
                .addRoute("0.0.0.0", 0)
                .setMtu(if (isStealth) 1400 else 1500) // Adaptive MTU
                .setBlocking(false)

            val configIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this, 0, configIntent, 
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.setConfigureIntent(pendingIntent)

            if (allowLocal) builder.allowBypass()

            // Use system DNS if not in stealth to avoid 1.1.1.1 bottlenecks
            if (!isStealth) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.forEach { 
                    builder.addDnsServer(it.hostAddress)
                }
            } else {
                builder.addDnsServer("1.1.1.1")
            }

            // NUCLEAR LOGIC:
            // Offline Mode: VIP apps bypass VPN (direct internet). Others go to VPN (blackhole).
            // Stealth Mode: All apps go to VPN (tunneled via SS).
            // Stealth Mode + Focus: Only VIP apps go to VPN. Others bypass (blocked by system if configured).
            if (isStealth && vipList.isNotEmpty()) {
                TrafficEvent.log("SHIELD >> STEALTH_FOCUS: TUNNELING_${vipList.size}_APPS")
                vipList.forEach { pkg ->
                    try { builder.addAllowedApplication(pkg) } catch (e: Exception) {
                        TrafficEvent.log("SHIELD >> PKG_NOT_FOUND: $pkg")
                    }
                }
                // Note: builder.addDisallowedApplication(packageName) is NOT needed here 
                // because addAllowedApplication automatically excludes all others.
            } else {
                if (!isStealth && vipList.isNotEmpty()) {
                    TrafficEvent.log("SHIELD >> NUCLEAR_ACTIVE: BYPASSING_${vipList.size}_APPS")
                    vipList.forEach { pkg ->
                        try { builder.addDisallowedApplication(pkg) } catch (e: Exception) {
                            TrafficEvent.log("SHIELD >> PKG_NOT_FOUND: $pkg")
                        }
                    }
                }
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            }

            TrafficEvent.log("CORE >> ESTABLISHING_INTERFACE")
            vpnInterface = builder.establish()
            
            if (vpnInterface == null) {
                TrafficEvent.log("CORE >> INTERFACE_REJECTED_BY_SYSTEM")
                return
            }
            
            TrafficEvent.setVpnActive(true)
            TrafficEvent.log("CORE >> SHIELD_UP (KEY_SIGN_SHOULD_BE_VISIBLE)")

            val fd = vpnInterface!!.fd
            if (EgiNetwork.isAvailable()) {
                val allowedDomains = EgiPreferences.getAllowedDomains(this)
                EgiNetwork.setAllowedDomains(allowedDomains)
                
                if (isStealth && ssKey.isNotEmpty()) {
                    TrafficEvent.log("SHIELD >> STARTING_STEALTH_CORE")
                    EgiNetwork.runVpnLoop(fd)
                } else {
                    TrafficEvent.log("SHIELD >> STARTING_OFFLINE_SHIELD")
                    EgiNetwork.runPassiveShield(fd)
                }
            } else {
                TrafficEvent.log("CORE >> NATIVE_LIB_LOAD_FAILED")
                while (isServiceActive) {
                    try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
                }
            }

        } catch (e: Exception) {
            TrafficEvent.log("CORE >> FATAL: ${e.message}")
        } finally {
            TrafficEvent.setVpnActive(false)
            closeInterface()
            TrafficEvent.log("CORE >> SHIELD_DOWN")
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
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
            .build()
    }

    private fun closeInterface() {
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
