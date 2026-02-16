package com.example.egi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (EgiPreferences.getAutoStart(context)) {
                val vpnIntent = VpnService.prepare(context)
                if (vpnIntent == null) {
                    context.startService(Intent(context, EgiVpnService::class.java))
                }
            }
        }
    }
}
