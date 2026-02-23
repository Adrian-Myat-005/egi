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
import androidx.compose.material.icons.filled.*
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
        
        // Ensure popup appears over everything (Lockscreen compatible)
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
                    color = Color.Black.copy(alpha = 0.6f) 
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
    
    // State to toggle between Main Menu and App Selection
    var currentView by remember { mutableStateOf("MAIN") } // MAIN, APP_SELECT_FOCUS, APP_SELECT_NORMAL
    
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
            .fillMaxWidth(0.85f)
            .wrapContentHeight()
            .clickable(enabled = false) {}, // Prevent closing when clicking card
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = creamColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E8B57))
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            
            // HEADER
            Text("IGY >> COMMAND_CENTER", 
                color = Color(0xFF2E8B57), 
                fontFamily = FontFamily.Monospace, 
                fontWeight = FontWeight.Bold, 
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (currentView == "MAIN") {
                // BUTTON 1: GLOBAL VPN
                ActionButton("GLOBAL VPN", Icons.Default.Language, Color(0xFF2E8B57)) {
                    activateMode(context, AppMode.CASUAL, isGlobal = true, isAuto = false, targetApp = null)
                    onAction()
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                // BUTTON 2: VPN FOCUS
                ActionButton("VPN FOCUS", Icons.Default.FilterCenterFocus, Color(0xFF20B2AA)) {
                    currentView = "APP_SELECT_FOCUS"
                }

                Spacer(modifier = Modifier.height(12.dp))

                // BUTTON 3: NORMAL MODE
                ActionButton("NORMAL MODE", Icons.Default.Shield, Color(0xFFB8860B)) {
                    currentView = "APP_SELECT_NORMAL"
                }
            } else {
                // APP SELECTION LIST
                Text(
                    if (currentView == "APP_SELECT_FOCUS") "SELECT TARGET >> FOCUS" else "SELECT TARGET >> NORMAL", 
                    color = Color.Gray, 
                    fontSize = 10.sp, 
                    fontFamily = FontFamily.Monospace
                )
                
                LazyColumn(modifier = Modifier.height(300.dp).fillMaxWidth()) {
                    items(installedApps) { app ->
                        HubAppRow(app, isDarkMode) {
                            if (currentView == "APP_SELECT_FOCUS") {
                                activateMode(context, AppMode.FOCUS, isGlobal = false, isAuto = false, targetApp = app.packageName)
                            } else {
                                // Normal Mode: Ensure app is in whitelist
                                val currentList = IgyPreferences.getCasualWhitelist(context).toMutableSet()
                                currentList.add(app.packageName)
                                IgyPreferences.saveCasualWhitelist(context, currentList)
                                activateMode(context, AppMode.CASUAL, isGlobal = false, isAuto = false, targetApp = app.packageName)
                            }
                            onAction()
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = { currentView = "MAIN" },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text("BACK", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        color = color.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(modifier = Modifier.width(16.dp))
            Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun HubAppRow(app: AppInfo, isDarkMode: Boolean, onClick: () -> Unit) {
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painter = rememberDrawablePainter(app.icon), contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(app.name, color = deepGray, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

private fun activateMode(context: Context, mode: AppMode, isGlobal: Boolean, isAuto: Boolean, targetApp: String?) {
    val (token, _, _) = IgyPreferences.getAuth(context)
    val vpnIntent = android.net.VpnService.prepare(context)
    
    if (token.isEmpty() || vpnIntent != null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Toast.makeText(context, "SETUP_REQUIRED", Toast.LENGTH_SHORT).show()
        return
    }

    // Save State
    IgyPreferences.saveMode(context, mode)
    IgyPreferences.setVpnTunnelMode(context, isGlobal)
    IgyPreferences.setAutoStartTriggerEnabled(context, isAuto)
    IgyPreferences.setSmartFilterActive(context, false) // Reset legacy flag
    
    if (targetApp != null) {
        IgyPreferences.saveFocusTarget(context, targetApp)
        // For Normal/Focus mode, we might want to also save to VIP list if logic requires
        // But the new service logic uses getFocusTarget() primarily for single-app tunneling
    }

    // Start Service
    val startIntent = Intent(context, IgyVpnService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(startIntent)
    } else {
        context.startService(startIntent)
    }

    // Launch App
    if (targetApp != null) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(targetApp)
            if (launchIntent != null) context.startActivity(launchIntent)
        } catch (e: Exception) {}
    }
    
    Toast.makeText(context, "SHIELD_ENGAGED", Toast.LENGTH_SHORT).show()
}
