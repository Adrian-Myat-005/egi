package com.example.egi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (EgiPreferences.getAutoStart(context)) {
                val vpnIntent = VpnService.prepare(context)
                if (vpnIntent == null) {
                    ContextCompat.startForegroundService(context, Intent(context, EgiVpnService::class.java))
                }
            }
        }
    }
}
