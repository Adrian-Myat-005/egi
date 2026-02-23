package com.example.igy

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
        
        // Transparent Overlay Setup
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        setContent {
            val isDarkMode = IgyPreferences.isDarkMode(this)
            var showContent by remember { mutableStateOf(false) }
            
            LaunchedEffect(Unit) {
                delay(50) // Tiny delay for smooth entry
                showContent = true
            }

            IgyTerminalTheme(isDarkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.5f) // Dim background
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize().clickable { 
                            showContent = false
                            finish() 
                        }
                    ) {
                        AnimatedVisibility(
                            visible = showContent,
                            enter = scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)) + fadeIn(),
                            exit = scaleOut() + fadeOut()
                        ) {
                            HubPopup(isDarkMode, onAction = { 
                                showContent = false
                                finish() 
                            })
                        }
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
            .fillMaxWidth(0.9f)
            .fillMaxHeight(0.7f)
            .clickable(enabled = false) {}, 
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = creamColor),
        border = BorderStroke(1.dp, Color(0xFF2E8B57))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // HEADER
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("IGY >> COMMAND_CENTER", 
                    color = Color(0xFF2E8B57), 
                    fontFamily = FontFamily.Monospace, 
                    fontWeight = FontWeight.Bold, 
                    fontSize = 12.sp
                )
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp).clickable { onAction() })
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // TOP BUTTON: GLOBAL VPN
            ActionButton("GLOBAL_VPN (ALL APPS)", Icons.Default.Language, Color(0xFF2E8B57)) {
                activateMode(context, AppMode.CASUAL, isGlobal = true, isStealth = true, targetApp = null)
                onAction()
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            // QUICK_INFO
            Box(modifier = Modifier.fillMaxWidth().background(Color.Gray.copy(alpha = 0.1f)).padding(4.dp)) {
                Text("[ TAP = VPN FOCUS ]   [ HOLD = NORMAL MODE ]", 
                    color = Color.Gray, 
                    fontSize = 9.sp, 
                    modifier = Modifier.align(Alignment.Center),
                    fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(4.dp))

            // APP LIST
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(installedApps) { app ->
                    HubAppItem(app, isDarkMode, 
                        onTap = {
                            // TAP = VPN FOCUS
                            activateMode(context, AppMode.FOCUS, isGlobal = false, isStealth = true, targetApp = app.packageName)
                            onAction()
                        },
                        onLongPress = {
                            // LONG_PRESS = NORMAL MODE (Local Speed)
                            activateMode(context, AppMode.FOCUS, isGlobal = false, isStealth = false, targetApp = app.packageName)
                            onAction()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HubAppItem(app: AppInfo, isDarkMode: Boolean, onTap: () -> Unit, onLongPress: () -> Unit) {
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFFDF5E6)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painter = rememberDrawablePainter(app.icon), contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(app.name, color = deepGray, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(app.packageName, color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun activateMode(context: Context, mode: AppMode, isGlobal: Boolean, isStealth: Boolean, targetApp: String?) {
    val (token, _, _) = IgyPreferences.getAuth(context)
    val vpnIntent = android.net.VpnService.prepare(context)
    
    if (token.isEmpty() || vpnIntent != null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return
    }

    // Save State
    IgyPreferences.saveMode(context, mode)
    IgyPreferences.setVpnTunnelMode(context, isGlobal)
    IgyPreferences.setStealthMode(context, isStealth)
    IgyPreferences.setAutoStartTriggerEnabled(context, false) // Disable monitor if manual choice
    
    if (targetApp != null) {
        IgyPreferences.saveFocusTarget(context, targetApp)
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
    
    Toast.makeText(context, if (isStealth) "VPN_FOCUS_ENGAGED" else "NORMAL_FOCUS_ENGAGED", Toast.LENGTH_SHORT).show()
}
