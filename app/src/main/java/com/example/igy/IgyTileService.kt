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

class IgyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = IgyVpnService.isRunning
        val (token, _, _) = IgyPreferences.getAuth(this)
        
        // Check Usage Stats Permission for Auto Mode
        if (!hasUsageStatsPermission()) {
            Toast.makeText(this, "AUTO_MODE: USAGE PERMISSION REQUIRED", Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
            return
        }

        if (isRunning) {
            // STOP EVERYTHING
            IgyPreferences.setAutoStartTriggerEnabled(this, false)
            val stopIntent = Intent(this, IgyVpnService::class.java).apply { action = IgyVpnService.ACTION_STOP }
            startService(stopIntent)
            TrafficEvent.log("USER >> SHIELD_OFF")
        } else {
            // START AUTO MONITOR
            val autoStartApps = IgyPreferences.getAutoStartApps(this)
            if (autoStartApps.isEmpty()) {
                Toast.makeText(this, "SELECT TARGET APPS IN SETTINGS FIRST", Toast.LENGTH_LONG).show()
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivityAndCollapse(intent)
                return
            }
            
            // Set Auto Mode Flag
            IgyPreferences.setAutoStartTriggerEnabled(this, true)
            IgyPreferences.setVpnTunnelMode(this, false) // Ensure global is off for auto mode
            
            val startIntent = Intent(this, IgyVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
            TrafficEvent.log("USER >> AUTO_MONITOR_ON")
        }
        updateTileState()
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
                tile.subtitle = "AUTO: STANDBY"
                // Check if VPN is actually tunneling or just monitoring
                if (TrafficEvent.vpnActive.value) {
                    tile.subtitle = "AUTO: PROTECTING"
                }
            } else {
                tile.subtitle = "MANUAL: ACTIVE"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Igy Shield"
            tile.subtitle = "OFF"
        }
        tile.updateTile()
    }
}
