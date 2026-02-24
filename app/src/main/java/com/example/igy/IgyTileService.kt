package com.example.igy

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.net.VpnService
import android.widget.Toast
import android.app.AppOpsManager
import android.os.Process
import kotlinx.coroutines.launch

class IgyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = IgyVpnService.isRunning
        val (token, _, isPremium) = IgyPreferences.getAuth(this)
        
        // --- INSTANT-LOCK: GHOST PING SERVER ---
        val serverUrl = IgyPreferences.getSyncEndpoint(this) ?: "https://egi-67tg.onrender.com"
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try { java.net.URL("$serverUrl/api/ping").openConnection().apply { connectTimeout = 2000 }.inputStream } catch (e: Exception) {}
        }

        // Check for Auth and Basic Permission
        val vpnIntent = android.net.VpnService.prepare(this)
        if (token.isEmpty() || vpnIntent != null) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
            Toast.makeText(this, "PLEASE SETUP IGY SHIELD FIRST", Toast.LENGTH_LONG).show()
            return
        }

        if (isRunning) {
            // STOP EVERYTHING
            val stopIntent = Intent(this, IgyVpnService::class.java).apply { action = IgyVpnService.ACTION_STOP }
            startService(stopIntent)
            TrafficEvent.log("USER >> SHIELD_OFF")
        } else {
            // One-Tap Intelligence Switch
            val isAutoModeSettingEnabled = IgyPreferences.isAutoStartTriggerEnabled(this)
            
            if (isAutoModeSettingEnabled) {
                // PREMIUM CHECK FOR MONITOR
                if (!isPremium) {
                    Toast.makeText(this, "PREMIUM_REQUIRED FOR AUTO_PROTECT", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    startActivityAndCollapse(intent)
                    return
                }

                // AUTO MODE ON: Start Background Monitor
                if (!hasUsageStatsPermission()) {
                    val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivityAndCollapse(intent)
                    return
                }
                
                IgyPreferences.setVpnTunnelMode(this, false) 
                IgyPreferences.setStealthMode(this, true)
                
                startIgyService()
                TrafficEvent.log("USER >> MONITOR_ACTIVE")
            } else {
                // PREMIUM CHECK FOR GLOBAL VPN
                if (!isPremium) {
                    Toast.makeText(this, "PREMIUM_REQUIRED FOR VPN_TUNNEL", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    startActivityAndCollapse(intent)
                    return
                }

                // AUTO MODE OFF: Start Global VPN (True Encryption)
                IgyPreferences.setVpnTunnelMode(this, true) 
                IgyPreferences.setStealthMode(this, true)
                IgyPreferences.saveMode(this, AppMode.CASUAL)
                
                startIgyService()
                TrafficEvent.log("USER >> GLOBAL_VPN_ACTIVE")
            }
        }
        updateTileState()
    }

    private fun startIgyService() {
        val startIntent = Intent(this, IgyVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = IgyVpnService.isRunning
        val isAuto = IgyPreferences.isAutoStartTriggerEnabled(this)
        
        if (isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Igy Shield"
            if (isAuto) {
                tile.subtitle = if (TrafficEvent.vpnActive.value) "AUTO: PROTECTING" else "AUTO: STANDBY"
            } else {
                tile.subtitle = if (IgyPreferences.isVpnTunnelGlobal(this)) "GLOBAL VPN" else "FOCUS VPN"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Igy Shield"
            tile.subtitle = "OFF"
        }
        tile.updateTile()
    }
}
