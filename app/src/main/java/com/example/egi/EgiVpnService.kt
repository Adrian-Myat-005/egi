package com.example.egi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

class EgiVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, createNotification())

        if (vpnThread == null) {
            vpnThread = Thread(this, "EgiVpnThread")
            vpnThread?.start()
        }
        return START_STICKY
    }

    override fun run() {
        try {
            vpnInterface = Builder()
                .setSession("EgiShield")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .establish()

            val inputStream = FileInputStream(vpnInterface?.fileDescriptor)
            val packet = ByteBuffer.allocate(32767)

            while (!Thread.interrupted()) {
                val length = inputStream.read(packet.array())
                if (length > 0) {
                    Log.d("EgiVpnService", "Packet: $length bytes")
                    packet.clear()
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
                .setContentTitle("Egi Shield Active")
                .setContentText("Monitoring network traffic...")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Egi Shield Active")
                .setContentText("Monitoring network traffic...")
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
        vpnThread?.interrupt()
        closeInterface()
        super.onDestroy()
    }
}
