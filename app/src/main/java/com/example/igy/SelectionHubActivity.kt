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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        setContent {
            val isDarkMode = IgyPreferences.isDarkMode(this)
            var showContent by remember { mutableStateOf(false) }
            
            LaunchedEffect(Unit) {
                delay(100)
                showContent = true
            }

            IgyTerminalTheme(isDarkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.6f) 
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
                            enter = scaleIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
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
            .fillMaxWidth(0.92f)
            .fillMaxHeight(0.75f)
            .clickable(enabled = false) {}, 
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = creamColor),
        border = BorderStroke(1.dp, Color(0xFF2E8B57).copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // HEADER
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("IGY >> COMMAND_CENTER", 
                        color = Color(0xFF2E8B57), 
                        fontFamily = FontFamily.Monospace, 
                        fontWeight = FontWeight.Bold, 
                        fontSize = 14.sp
                    )
                    Text("SELECT_OPERATION_MODE", color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = onAction) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.Gray)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // GLOBAL VPN QUICK ACTION
            Surface(
                onClick = {
                    activateMode(context, AppMode.CASUAL, isGlobal = true, isStealth = true, targetApp = null)
                    onAction()
                },
                modifier = Modifier.fillMaxWidth().height(55.dp),
                color = Color(0xFF2E8B57).copy(alpha = 0.15f),
                border = BorderStroke(1.dp, Color(0xFF2E8B57)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = Color(0xFF2E8B57))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("ACTIVATE GLOBAL VPN", color = Color(0xFF2E8B57), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // APP LIST WITH STAGGERED ENTRANCE
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(installedApps) { index, app ->
                    var isVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        delay(index * 30L)
                        isVisible = true
                    }
                    
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = slideInVertically { it / 2 } + fadeIn()
                    ) {
                        HubAppItem(app, isDarkMode, 
                            onFocusMode = {
                                activateMode(context, AppMode.FOCUS, isGlobal = false, isStealth = true, targetApp = app.packageName)
                                onAction()
                            },
                            onNormalMode = {
                                activateMode(context, AppMode.FOCUS, isGlobal = false, isStealth = false, targetApp = app.packageName)
                                onAction()
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HubAppItem(app: AppInfo, isDarkMode: Boolean, onFocusMode: () -> Unit, onNormalMode: () -> Unit) {
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    var showMenu by remember { mutableStateOf(false) }
    
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onFocusMode,
                    onLongClick = { showMenu = true }
                )
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberDrawablePainter(app.icon), 
                contentDescription = null, 
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(app.name, color = deepGray, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(app.packageName, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray.copy(alpha = 0.3f))
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(if (isDarkMode) Color(0xFF2D2D2D) else Color.White).border(0.5.dp, Color.Gray)
        ) {
            DropdownMenuItem(
                text = { Text("🚀 Launch Normal Mode (Speed)", fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                onClick = { 
                    showMenu = false
                    onNormalMode() 
                }
            )
            DropdownMenuItem(
                text = { Text("🔒 Launch Focus Mode (VPN)", fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                onClick = { 
                    showMenu = false
                    onFocusMode() 
                }
            )
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

    IgyPreferences.saveMode(context, mode)
    IgyPreferences.setVpnTunnelMode(context, isGlobal)
    IgyPreferences.setStealthMode(context, isStealth)
    IgyPreferences.setAutoStartTriggerEnabled(context, false)
    
    if (targetApp != null) {
        IgyPreferences.saveFocusTarget(context, targetApp)
    }

    val startIntent = Intent(context, IgyVpnService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(startIntent)
    } else {
        context.startService(startIntent)
    }

    if (targetApp != null) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(targetApp)
            if (launchIntent != null) context.startActivity(launchIntent)
        } catch (e: Exception) {}
    }
    
    val msg = if (isStealth) "VPN_FOCUS_ACTIVE" else "SPEED_BOOST_ACTIVE"
    Toast.makeText(context, "IGY >> $msg", Toast.LENGTH_SHORT).show()
}
