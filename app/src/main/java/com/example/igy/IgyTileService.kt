package com.example.igy

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.net.VpnService

class IgyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isSmartTrigger = IgyPreferences.isAutoStartTriggerEnabled(this)
        val isRunning = IgyVpnService.isRunning
        val (token, _, _) = IgyPreferences.getAuth(this)
        
        if (isSmartTrigger && isRunning) {
            // Tap: Toggle Off
            IgyPreferences.setAutoStartTriggerEnabled(this, false)
            val stopIntent = Intent(this, IgyVpnService::class.java).apply { action = IgyVpnService.ACTION_STOP }
            startService(stopIntent)
            TrafficEvent.log("USER >> 24/7_GUARD_OFF")
        } else {
            // Tap: Toggle On
            val vpnIntent = android.net.VpnService.prepare(this)
            if (vpnIntent != null || token.isEmpty()) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivityAndCollapse(intent)
            } else {
                val autoStartApps = IgyPreferences.getAutoStartApps(this)
                if (autoStartApps.isEmpty()) {
                    android.widget.Toast.makeText(this, "PLEASE SELECT TARGET APPS IN SETTINGS", android.widget.Toast.LENGTH_LONG).show()
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivityAndCollapse(intent)
                    return
                }
                IgyPreferences.setAutoStartTriggerEnabled(this, true)
                IgyPreferences.setSmartFilterActive(this, true)
                IgyPreferences.setStealthMode(this, false)
                IgyPreferences.setVpnTunnelMode(this, false)
                
                val startIntent = Intent(this, IgyVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent)
                } else {
                    startService(startIntent)
                }
                TrafficEvent.log("USER >> 24/7_GUARD_ON")
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isTunnelActive = TrafficEvent.vpnActive.value
        val isSmartTrigger = IgyPreferences.isAutoStartTriggerEnabled(this)
        
        // Tile reflects the GUARD state primarily
        if (isSmartTrigger) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Igy Shield: ACTIVE"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // ACTIVE means the guard is on. If tunnel is off, it's in the 1-hour sleep mode.
                tile.subtitle = if (isTunnelActive) "GUARD: ON" else "GUARD: SLEEP"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Igy Shield"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "GUARD: OFF"
            }
        }
        tile.updateTile()
    }
}
