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
import kotlinx.coroutines.*
import java.io.IOException
import com.example.igy.R

class IgyVpnService : VpnService(), Runnable {
    companion object {
        const val ACTION_STOP = "com.example.igy.STOP"
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
            val iconRes = if (applicationInfo.icon != 0) applicationInfo.icon else android.R.drawable.sym_def_app_icon
            val notification = createNotification(iconRes)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            TrafficEvent.log("CORE >> FG_SERVICE_ERR: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (prepare(this) != null) {
            TrafficEvent.log("CORE >> PERMISSION_MISSING")
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
                var lastSubCheck = 0L
                val CHECK_INTERVAL = 4 * 60 * 60 * 1000L // 4 Hours

                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (IgyNetwork.isAvailable()) {
                        TrafficEvent.updateCount(IgyNetwork.getNativeBlockedCount())
                    }
                    if (now - lastSubCheck >= CHECK_INTERVAL) {
                        lastSubCheck = now
                        checkSubscription()
                    }
                    delay(3000)
                }
            }
        }
    }

    private suspend fun checkSubscription() {
        val (token, _, _) = IgyPreferences.getAuth(this)
        val serverUrl = IgyPreferences.getSyncEndpoint(this) ?: "https://egi-67tg.onrender.com"
        if (token.isNotEmpty()) {
            val config = fetchVpnConfigSync(serverUrl, token)
            if (config == "EXPIRED" || config == "UNAUTHORIZED") {
                TrafficEvent.log("CORE >> SUBSCRIPTION_EXPIRED")
                cleanupAndStop()
            }
        }
    }

    private fun fetchVpnConfigSync(serverUrl: String, token: String): String? {
        try {
            val url = java.net.URL("$serverUrl/api/vpn/config")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) {
                return org.json.JSONObject(conn.inputStream.bufferedReader().readText()).getString("config")
            } else if (conn.responseCode == 403) return "EXPIRED"
            else if (conn.responseCode == 401) return "UNAUTHORIZED"
        } catch (e: Exception) {}
        return null
    }

    private fun cleanupAndStop() {
        isServiceActive = false
        TrafficEvent.setVpnActive(false)
        serviceScope.cancel()
        try { vpnThread?.interrupt() } catch (e: Exception) {}
        closeInterface()
        stopForeground(true)
        stopSelf()
    }

    override fun run() {
        try {
            establishVpn()
        } catch (e: Exception) {
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

        // Resolve Host
        try {
            if (ssKey.startsWith("ss://")) {
                val uri = java.net.URI(ssKey.split("#")[0])
                val host = uri.host
                if (host != null && !host.matches(Regex("^[0-9.]+$"))) {
                    val address = java.net.InetAddress.getByName(host)
                    ssKey = ssKey.replace(host, address.hostAddress ?: host)
                }
            }
        } catch (e: Exception) { TrafficEvent.log("CORE >> DNS_ERR: ${e.message}") }

        val builder = Builder()
            .setSession("IgyShield")
            .addAddress("172.19.0.1", 30) 
            .addRoute("0.0.0.0", 0)
            .setMtu(1280)
            .setBlocking(true) 

        val configIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, configIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        builder.setConfigureIntent(pendingIntent)

        if (allowLocal) builder.allowBypass()
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")
        builder.addDisallowedApplication(packageName)

        if (isStealth && !isGlobal && vipList.isNotEmpty()) {
            vipList.forEach { try { builder.addAllowedApplication(it) } catch (e: Exception) {} }
        } else if (!isStealth && vipList.isNotEmpty()) {
            vipList.forEach { try { builder.addDisallowedApplication(it) } catch (e: Exception) {} }
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) return

        TrafficEvent.setVpnActive(true)
        val fd = vpnInterface!!.fd
        if (IgyNetwork.isAvailable()) {
            IgyNetwork.setAllowedDomains(IgyPreferences.getAllowedDomains(this))
            IgyNetwork.setOutlineKey(ssKey)
            if (ssKey.isNotEmpty()) IgyNetwork.runVpnLoop(fd)
            else IgyNetwork.runPassiveShield(fd)
        } else {
            TrafficEvent.log("CORE >> ENGINE_MISSING")
            while (isServiceActive) { Thread.sleep(2000) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("igy_vpn", "Igy VPN", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(iconRes: Int): Notification {
        val stopIntent = Intent(this, IgyVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "igy_vpn") else Notification.Builder(this)
        return builder.setContentTitle("Igy Shield Active").setSmallIcon(iconRes).setOngoing(true)
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
