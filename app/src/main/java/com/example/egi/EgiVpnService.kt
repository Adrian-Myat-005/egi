package com.example.egi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class EgiVpnService : VpnService(), Runnable {
    companion object {
        const val ACTION_STOP = "com.example.egi.STOP"
        const val ACTION_RESTART = "com.example.egi.RESTART"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RESTART) {
            closeInterface() // Builder only applies on establish()
        }

        createNotificationChannel()
        startForeground(1, createNotification())

        if (vpnThread == null || !vpnThread!!.isAlive) {
            TrafficEvent.resetCount()
            vpnThread = Thread(this, "EgiVpnThread")
            vpnThread?.start()
            
            // Heartbeat Reporter: Poll native atomic counter
            serviceScope.launch {
                while (isActive) {
                    delay(1000)
                    if (EgiNetwork.isAvailable()) {
                        TrafficEvent.updateCount(EgiNetwork.getNativeBlockedCount())
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun stopVpn() {
        serviceScope.cancel()
        vpnThread?.interrupt()
        closeInterface()
        stopForeground(true)
        stopSelf()
    }

    override fun run() {
        val sharedPrefs = getSharedPreferences("egi_prefs", Context.MODE_PRIVATE)
        val dnsProvider = sharedPrefs.getString("dns_provider", null)
        val vipList = EgiPreferences.getVipList(this)

        try {
            val builder = Builder()
                .setSession("EgiShield")
                .addAddress("10.0.0.2", 32)
                .addAddress("fd00::2", 128)
                .setMtu(1500)

            // Apply DNS Settings
            if (dnsProvider != null) {
                builder.addDnsServer(dnsProvider)
                // Secondary & IPv6 lookups for total leak protection
                when (dnsProvider) {
                    "1.1.1.1" -> {
                        builder.addDnsServer("1.0.0.1")
                        builder.addDnsServer("2606:4700:4700::1111")
                        builder.addDnsServer("2606:4700:4700::1001")
                    }
                    "8.8.8.8" -> {
                        builder.addDnsServer("8.8.4.4")
                        builder.addDnsServer("2001:4860:4860::8888")
                        builder.addDnsServer("2001:4860:4860::8844")
                    }
                    "94.140.14.14" -> {
                        builder.addDnsServer("94.140.15.15")
                        builder.addDnsServer("2a10:50c0::ad1:ff")
                        builder.addDnsServer("2a10:50c0::ad2:ff")
                    }
                }
            }

            // Split Tunneling Configuration
            val isGlobal = EgiPreferences.isVpnTunnelGlobal(this)
            
            if (!isGlobal) {
                // SELECTED MODE: Only allow VIP apps through VPN
                vipList.forEach { packageName ->
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (e: Exception) {
                        // Ignore missing apps
                    }
                }
            } else {
                // GLOBAL MODE: Tunnel everything EXCEPT specific bypasses (if any needed)
                // We keep the "Disallowed" list empty or specific for things we NEVER want to tunnel
                try {
                    builder.addDisallowedApplication("com.example.egi") // Always exclude self
                } catch (e: Exception) { }
            }

            // The Trap: Capture ALL other traffic (IPv4 & IPv6)
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)

            try {
                vpnInterface = builder.establish()
            } catch (e: Exception) {
                Log.e("EgiVpnService", "CRITICAL: Kernel denied VPN slot", e)
                TrafficEvent.log("ERROR >> SLOT_DENIED")
                Thread.sleep(1000) // Don't spam retries
                return
            }

            if (vpnInterface == null) {
                TrafficEvent.log("ERROR >> PERMISSION_MISSING")
                return
            }

            TrafficEvent.setVpnActive(true)
            TrafficEvent.log("SHIELD_ENGAGED >> SYSTEM_SILENCED")

            // PASSIVE SHIELD: Delegate loop to Rust for Zero-Copy and Near-Zero Heat
            if (EgiNetwork.isAvailable()) {
                val fd = vpnInterface!!.fd
                EgiNetwork.runVpnLoop(fd)
            } else {
                val inputStream = FileInputStream(vpnInterface?.fileDescriptor)
                val packet = ByteBuffer.allocate(4096)
                while (!Thread.interrupted()) {
                    val length = try { inputStream.read(packet.array()) } catch (e: Exception) { -1 }
                    if (length <= 0) break
                    packet.clear()
                }
            }
        } catch (e: Exception) {
            Log.e("EgiVpnService", "Error in VPN loop", e)
        } finally {
            TrafficEvent.setVpnActive(false)
            TrafficEvent.log("SHIELD_DISENGAGED")
            closeInterface()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "egi_vpn",
                "Egi VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "egi_vpn")
                .setContentTitle("Egi Focus Mode Active")
                .setContentText("Black-holing distractions...")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Egi Focus Mode Active")
                .setContentText("Black-holing distractions...")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        }
    }

    private fun closeInterface() {
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.e("EgiVpnService", "Error closing interface", e)
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
