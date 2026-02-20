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
            
            serviceScope.launch {
                var lastSubCheck = 0L
                val CHECK_INTERVAL = 4 * 60 * 60 * 1000L // 4 Hours

                while (isActive) {
                    val now = System.currentTimeMillis()
                    
                    // 1. Update Traffic Stats (Every 3s)
                    if (EgiNetwork.isAvailable()) {
                        TrafficEvent.updateCount(EgiNetwork.getNativeBlockedCount())
                    }

                    // 2. Periodic Subscription Check (Every 4h)
                    if (now - lastSubCheck >= CHECK_INTERVAL) {
                        lastSubCheck = now
                        val (token, _, _) = EgiPreferences.getAuth(this@EgiVpnService)
                        val serverUrl = EgiPreferences.getSyncEndpoint(this@EgiVpnService) ?: "http://10.0.2.2:3000"
                        
                        if (token.isNotEmpty()) {
                            val config = fetchVpnConfigSync(serverUrl, token)
                            if (config == "EXPIRED" || config == "UNAUTHORIZED") {
                                TrafficEvent.log("CORE >> SUBSCRIPTION_EXPIRED: SHUTTING_DOWN")
                                stopVpn()
                                break
                            }
                        }
                    }
                    delay(3000)
                }
            }
        }
        return START_STICKY
    }

    // Synchronous version for internal service check
    private fun fetchVpnConfigSync(serverUrl: String, token: String): String? {
        try {
            val url = java.net.URL("$serverUrl/api/vpn/config")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Authorization", "Bearer $token")
            
            if (conn.responseCode == 200) {
                val res = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                return res.getString("config")
            } else if (conn.responseCode == 403) {
                return "EXPIRED"
            } else if (conn.responseCode == 401) {
                return "UNAUTHORIZED"
            }
        } catch (e: Exception) {}
        return null
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
        val isGlobal = EgiPreferences.isVpnTunnelGlobal(this)
        val ssKey = EgiPreferences.getOutlineKey(this)
        val allowLocal = EgiPreferences.getLocalBypass(this)

        try {
            // Balanced MTU: 1400 is the sweet spot for mobile stability
            val mtu = 1400
            
            TrafficEvent.log("CORE >> INITIALIZING_BUILDER [MTU: $mtu]")
            val builder = Builder()
                .setSession("EgiShield")
                .addAddress("172.19.0.1", 30) 
                .addRoute("0.0.0.0", 0)
                .setMtu(mtu)
                .setBlocking(true) 

            // IPv6 Leak Protection: Force all IPv6 traffic to be handled (and dropped by core)
            try {
                builder.addAddress("fd00::1", 128)
                builder.addRoute("::", 0)
            } catch (e: Exception) {
                TrafficEvent.log("CORE >> IPV6_STRICT_MODE_ERR")
            }

            val configIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this, 0, configIntent, 
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.setConfigureIntent(pendingIntent)

            if (allowLocal) builder.allowBypass()

            // Optimized DNS: 10.0.0.1 is the virtual DNS handled by tun2proxy
            builder.addDnsServer("10.0.0.1")
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")
            builder.addRoute("10.0.0.1", 32)

            // VPN_TUNNEL_LOGIC:
            if (isStealth) {
                if (isGlobal) {
                    // VPN Shield: Full encryption
                    TrafficEvent.log("SHIELD >> MODE: VPN_SHIELD [GLOBAL_ENCRYPTION]")
                    builder.addDisallowedApplication(packageName)
                } else {
                    // Focus Mode: Only target apps
                    if (vipList.isNotEmpty()) {
                        TrafficEvent.log("SHIELD >> MODE: FOCUS_MODE [TUNNELING_${vipList.size}_APPS]")
                        vipList.forEach { pkg ->
                            try { builder.addAllowedApplication(pkg) } catch (e: Exception) {}
                        }
                    } else {
                        TrafficEvent.log("SHIELD >> FOCUS_MODE_EMPTY: FALLING_BACK_TO_GLOBAL")
                        builder.addDisallowedApplication(packageName)
                    }
                }
            } else {
                // Bypass List: Everything protected except targets
                TrafficEvent.log("SHIELD >> MODE: BYPASS_LIST [PROTECTING_ALL_EXCEPT_TARGETS]")
                if (vipList.isNotEmpty()) {
                    vipList.forEach { pkg ->
                        try { builder.addDisallowedApplication(pkg) } catch (e: Exception) {}
                    }
                }
                builder.addDisallowedApplication(packageName)
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
