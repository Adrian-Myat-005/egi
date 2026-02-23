package com.example.igy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter

class SelectionHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure popup appears over everything
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                          android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                          android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                          android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        setContent {
            val isDarkMode = IgyPreferences.isDarkMode(this)
            IgyTerminalTheme(isDarkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.5f) // Dimmed background
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize().clickable { finish() }
                    ) {
                        HubPopup(isDarkMode, onAction = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
fun HubPopup(isDarkMode: Boolean, onAction: () -> Unit) {
    val context = LocalContext.current
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    
    val installedApps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { 
                AppInfo(
                    name = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(pm)
                )
            }
            .sortedBy { it.name }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.8f)
            .clickable(enabled = false) {}, // Prevent closing when clicking card
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = creamColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E8B57))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // HEADER
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(Color(0xFF2E8B57).copy(alpha = 0.1f))
                    .border(0.5.dp, Color(0xFF2E8B57)),
                contentAlignment = Alignment.Center
            ) {
                Text("IGY >> COMMAND_CENTER", color = Color(0xFF2E8B57), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            // TWO COLUMNS
            Row(modifier = Modifier.weight(1f)) {
                // --- LEFT COLUMN: NORMAL FOCUS ---
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(0.5.dp, wheat)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().background(deepGray.copy(alpha = 0.05f)).padding(8.dp), contentAlignment = Alignment.Center) {
                        Text("NORMAL_FOCUS", color = Color(0xFFB8860B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(installedApps) { app ->
                            HubAppRow(app, isDarkMode) {
                                activateMode(context, AppMode.FOCUS, isStealth = false, isGlobal = false, app.packageName)
                                onAction()
                            }
                        }
                    }
                }

                // --- RIGHT COLUMN: VPN MODES ---
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(0.5.dp, wheat)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().background(deepGray.copy(alpha = 0.05f)).padding(8.dp), contentAlignment = Alignment.Center) {
                        Text("VPN_MODES", color = Color(0xFF20B2AA), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    
                    // GLOBAL VPN BUTTON
                    Surface(
                        onClick = { 
                            activateMode(context, AppMode.CASUAL, isStealth = true, isGlobal = true, null)
                            onAction()
                        },
                        modifier = Modifier.fillMaxWidth().padding(4.dp).height(80.dp),
                        color = Color(0xFF2E8B57).copy(alpha = 0.1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E8B57)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = Color(0xFF2E8B57))
                            Text("GLOBAL_VPN", color = Color(0xFF2E8B57), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text("VPN_FOCUS_LIST", color = Color.Gray, fontSize = 9.sp, modifier = Modifier.padding(4.dp), fontFamily = FontFamily.Monospace)
                    
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(installedApps) { app ->
                            HubAppRow(app, isDarkMode) {
                                activateMode(context, AppMode.FOCUS, isStealth = true, isGlobal = false, app.packageName)
                                onAction()
                            }
                        }
                    }
                }
            }

            // FOOTER: MASTER KILL
            Surface(
                onClick = { 
                    context.startService(Intent(context, IgyVpnService::class.java).apply { action = IgyVpnService.ACTION_STOP })
                    IgyPreferences.setAutoStartTriggerEnabled(context, false)
                    onAction()
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                color = Color.Red.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = Color.Red)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("STOP_ALL_SHIELDS", color = Color.Red, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun HubAppRow(app: AppInfo, isDarkMode: Boolean, onClick: () -> Unit) {
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painter = rememberDrawablePainter(app.icon), contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(app.name, color = deepGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

private fun activateMode(context: Context, mode: AppMode, isStealth: Boolean, isGlobal: Boolean, targetApp: String?) {
    val (token, _, _) = IgyPreferences.getAuth(context)
    val vpnIntent = android.net.VpnService.prepare(context)
    
    if (token.isEmpty() || vpnIntent != null) {
        // Missing Auth or Permission: Redirect to Main
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val msg = if (token.isEmpty()) "PLEASE LOGIN FIRST" else "VPN PERMISSION REQUIRED"
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        return
    }

    IgyPreferences.saveMode(context, mode)
    IgyPreferences.setStealthMode(context, isStealth)
    IgyPreferences.setVpnTunnelMode(context, isGlobal)
    IgyPreferences.setAutoStartTriggerEnabled(context, true)
    IgyPreferences.setSmartFilterActive(context, false)
    
    if (targetApp != null) {
        if (mode == AppMode.FOCUS) {
            IgyPreferences.saveFocusTarget(context, targetApp)
        }
    }

    // Start Service
    val startIntent = Intent(context, IgyVpnService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(startIntent)
    } else {
        context.startService(startIntent)
    }

    // Launch App if specified
    if (targetApp != null) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(targetApp)
            if (launchIntent != null) context.startActivity(launchIntent)
        } catch (e: Exception) {}
    }
    
    Toast.makeText(context, "SHIELD_ENGAGED", Toast.LENGTH_SHORT).show()
}
