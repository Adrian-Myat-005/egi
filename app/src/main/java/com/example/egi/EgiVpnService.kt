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
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

class EgiVpnService : VpnService(), Runnable {
    companion object {
        const val ACTION_STOP = "com.example.egi.STOP"
        const val ACTION_RESTART = "com.example.egi.RESTART"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

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
            vpnThread = Thread(this, "EgiVpnThread")
            vpnThread?.start()
        }
        return START_STICKY
    }

    private fun stopVpn() {
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
                .setMtu(1500)

            // Apply DNS Settings
            if (dnsProvider != null) {
                Log.d("EgiVpnService", "EGI >> Applying DNS: $dnsProvider")
                builder.addDnsServer(dnsProvider)
                // Secondary lookup
                when (dnsProvider) {
                    "1.1.1.1" -> builder.addDnsServer("1.0.0.1")
                    "8.8.8.8" -> builder.addDnsServer("8.8.4.4")
                    "94.140.14.14" -> builder.addDnsServer("94.140.15.15")
                }
            } else {
                Log.d("EgiVpnService", "EGI >> DNS: System Default (Passthrough)")
            }

            // Split Tunneling Inversion: VIPs bypass the VPN (0ms lag)
            Log.d("EgiVpnService", "EGI >> Routing VIPs: ${vipList.size} apps")
            vipList.forEach { packageName ->
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.e("EgiVpnService", "Failed to disallow $packageName", e)
                }
            }

            // Always disallow self to prevent UI lag
            builder.addDisallowedApplication("com.example.egi")

            // The Trap: Capture ALL other traffic
            builder.addRoute("0.0.0.0", 0)

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e("EgiVpnService", "Failed to establish VPN interface")
                return
            }

            val inputStream = FileInputStream(vpnInterface?.fileDescriptor)
            val packet = ByteBuffer.allocate(32767)
            var packetCounter = 0

            while (!Thread.interrupted()) {
                val length = try { inputStream.read(packet.array()) } catch (e: Exception) { -1 }
                if (length > 0) {
                    packetCounter++
                    // Sample packets (1 out of every 50) to avoid flooding the UI
                    if (packetCounter % 50 == 0) {
                        packet.limit(length)
                        val destIp = PacketUtils.getDestinationIp(packet)
                        val proto = PacketUtils.getProtocol(packet)
                        TrafficEvent.log("[BLOCKED] >> $proto -> $destIp")
                    }
                    packet.clear()
                } else if (length == -1) {
                    break // Interface closed
                }
            }
        } catch (e: Exception) {
            Log.e("EgiVpnService", "Error in VPN loop", e)
        } finally {
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
