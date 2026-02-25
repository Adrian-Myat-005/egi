package com.example.igy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.WindowManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.delay

class SelectionHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

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
                Box(modifier = Modifier.fillMaxSize()) {
                    // Background Dim & Dismiss
                    Surface(
                        modifier = Modifier.fillMaxSize().clickable { 
                            showContent = false
                            finish() 
                        },
                        color = Color.Black.copy(alpha = 0.5f)
                    ) {}

                    AnimatedVisibility(
                        visible = showContent,
                        enter = slideInVertically(
                            initialOffsetY = { -it },
                            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)
                        ) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Box(modifier = Modifier.padding(top = 24.dp)) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HubPopup(isDarkMode: Boolean, onAction: () -> Unit) {
    val context = LocalContext.current
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val gold = Color(0xFFB8860B)
    val cyan = Color(0xFF20B2AA)
    val blue = Color(0xFF2D42FF)

    val authData = remember { IgyPreferences.getAuth(context) }
    val isPremium = authData.third
    val isVpnActive = IgyVpnService.isRunning
    val isStealth = IgyPreferences.isStealthMode(context)
    val isGlobal = IgyPreferences.isVpnTunnelGlobal(context)

    // Breathing Aura Logic
    val infiniteTransition = rememberInfiniteTransition(label = "Aura")
    val auraAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(animation = tween(2000), repeatMode = RepeatMode.Reverse),
        label = "Alpha"
    )
    
    val auraColor = when {
        !isVpnActive -> Color.Transparent
        !isStealth -> gold
        else -> cyan
    }

    var isLibraryExpanded by remember { mutableStateOf(false) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedApps by remember { mutableStateOf(setOf<String>()) }
    var pendingStealthMode by remember { mutableStateOf<Boolean?>(null) }
    var recentAppsVersion by remember { mutableStateOf(0) }
    var isDeleteMode by remember { mutableStateOf(false) }
    var appToDelete by remember { mutableStateOf<String?>(null) }

    val installedApps = remember {
        try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PackageManager.MATCH_ALL else 0
            pm.queryIntentActivities(intent, flags)
                .map { 
                    AppInfo(
                        name = it.loadLabel(pm).toString(),
                        packageName = it.activityInfo.packageName,
                        icon = it.loadIcon(pm)
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.name }
        } catch (e: Exception) {
            emptyList<AppInfo>()
        }
    }

    val recentApps = remember(installedApps, recentAppsVersion) {
        val pkgs = IgyPreferences.getRecentApps(context)
        pkgs.mapNotNull { pkg ->
            installedApps.find { it.packageName == pkg }
        }
    }

    // Use current stealth/global state as base for recent app clicks
    val currentStealth = IgyPreferences.isStealthMode(context)

    Card(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .wrapContentHeight()
            .drawBehind {
                if (isVpnActive) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(auraColor.copy(alpha = auraAlpha), Color.Transparent),
                            center = center,
                            radius = size.maxDimension * 0.8f
                        )
                    )
                }
            }
            .clickable(enabled = false) {},
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = creamColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(1.dp, gold.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            
            // 1. DYNAMIC STATUS HEADER (Heartbeat)
            HeartbeatHeader(isVpnActive, gold, isDarkMode)

            Spacer(modifier = Modifier.height(24.dp))

            // 2. TOP TIER: MASTER PILLARS
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TactileTile(
                    label = "BOOST",
                    icon = Icons.Default.Speed,
                    color = gold,
                    isActive = isVpnActive && !isStealth,
                    modifier = Modifier.weight(1f),
                    isDarkMode = isDarkMode
                ) {
                    pendingStealthMode = false
                    isLibraryExpanded = true
                    Toast.makeText(context, "SELECT BOOST APP BELOW", Toast.LENGTH_SHORT).show()
                }

                TactileTile(
                    label = "GLOBAL",
                    icon = Icons.Default.Language,
                    color = blue,
                    isActive = isVpnActive && isStealth && isGlobal,
                    isPremium = isPremium,
                    modifier = Modifier.weight(1f),
                    isDarkMode = isDarkMode
                ) {
                    IgyPreferences.saveFocusTarget(context, "") // Clear target for global mode
                    activateMode(context, AppMode.CASUAL, isGlobal = true, isStealth = true, targetApp = null)
                    onAction()
                }

                TactileTile(
                    label = "FOCUS",
                    icon = Icons.Default.FilterCenterFocus,
                    color = cyan,
                    isActive = isVpnActive && isStealth && !isGlobal,
                    isPremium = isPremium,
                    modifier = Modifier.weight(1f),
                    isDarkMode = isDarkMode
                ) {
                    if (isPremium) {
                        pendingStealthMode = true
                        isLibraryExpanded = true
                        Toast.makeText(context, "SELECT FOCUS APP BELOW", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "PREMIUM_REQUIRED", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. MIDDLE TIER: APP DOCK
            if (recentApps.isNotEmpty() && !isMultiSelectMode) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isDeleteMode) "TAP_ICON_TO_REMOVE" else "RECENT_TARGETS (Hold to Edit)", color = gold.copy(alpha = 0.6f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    if (isDeleteMode) {
                        Text(
                            "[ CANCEL ]", 
                            color = Color.Red, 
                            fontSize = 9.sp, 
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable { 
                                isDeleteMode = false 
                                appToDelete = null
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recentApps) { app ->
                        val isSelectedForDelete = appToDelete == app.packageName
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (isDeleteMode) {
                                        if (isSelectedForDelete) {
                                            IgyPreferences.removeRecentApp(context, app.packageName)
                                            recentAppsVersion++
                                            appToDelete = null
                                            if (IgyPreferences.getRecentApps(context).isEmpty()) isDeleteMode = false
                                        } else {
                                            appToDelete = app.packageName
                                        }
                                    } else {
                                        val stealth = pendingStealthMode ?: currentStealth
                                        activateMode(context, AppMode.FOCUS, isGlobal = false, isStealth = stealth, targetApp = app.packageName)
                                        onAction()
                                    }
                                },
                                onLongClick = {
                                    if (!isDeleteMode) {
                                        isDeleteMode = true
                                        appToDelete = app.packageName
                                    }
                                }
                            )
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Image(
                                    painter = rememberDrawablePainter(app.icon),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            width = if (isSelectedForDelete) 2.dp else 1.dp, 
                                            color = if (isSelectedForDelete) Color.Red else wheat(isDarkMode), 
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .graphicsLayer(alpha = if (isDeleteMode && !isSelectedForDelete) 0.5f else 1f)
                                )
                                if (isDeleteMode) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Red,
                                        modifier = Modifier.size(14.dp).offset(x = 4.dp, y = (-4).dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.padding(2.dp))
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(app.name.take(6), color = if (isSelectedForDelete) Color.Red else deepGray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // 4. BOTTOM TIER: THE LIBRARY (List)
            Row(
                modifier = Modifier.fillMaxWidth(), 
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isMultiSelectMode) "MULTI_SELECT_MODE_ACTIVE" else "SYSTEM_APP_LIBRARY",
                    color = gold.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(onClick = { 
                    isMultiSelectMode = !isMultiSelectMode
                    if (!isMultiSelectMode) selectedApps = emptySet()
                    else isLibraryExpanded = true
                }) {
                    Icon(
                        if (isMultiSelectMode) Icons.Default.Checklist else Icons.Default.List, 
                        contentDescription = "Multi Select",
                        tint = if (isMultiSelectMode) blue else gold
                    )
                }
            }

            AnimatedVisibility(visible = isLibraryExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        itemsIndexed(installedApps) { _, app ->
                            HubAppItem(
                                app = app,
                                isDarkMode = isDarkMode,
                                isPremium = isPremium,
                                isSelected = selectedApps.contains(app.packageName),
                                isMultiSelectMode = isMultiSelectMode,
                                onToggleSelect = {
                                    val current = selectedApps.toMutableSet()
                                    if (current.contains(app.packageName)) current.remove(app.packageName) else current.add(app.packageName)
                                    selectedApps = current
                                },
                                onFocusMode = {
                                    val stealth = pendingStealthMode ?: true
                                    activateMode(context, AppMode.FOCUS, isGlobal = false, isStealth = stealth, targetApp = app.packageName)
                                    onAction()
                                },
                                onNormalMode = {
                                    activateMode(context, AppMode.FOCUS, isGlobal = false, isStealth = false, targetApp = app.packageName)
                                    onAction()
                                }
                            )
                        }
                    }
                    
                    if (isMultiSelectMode && selectedApps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                // Multi-select always uses CASUAL whitelist for now or Auto-start list
                                IgyPreferences.saveCasualWhitelist(context, selectedApps)
                                activateMode(context, AppMode.CASUAL, isGlobal = false, isStealth = true, targetApp = null)
                                onAction()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = blue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("START PROTECTION (${selectedApps.size} APPS)", fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            if (!isLibraryExpanded) {
                TextButton(onClick = { isLibraryExpanded = true }) {
                    Text("EXPAND_FULL_LIBRARY ▾", color = gold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun HeartbeatHeader(isActive: Boolean, gold: Color, isDarkMode: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.2f else 1f,
        animationSpec = infiniteRepeatable(animation = tween(800, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "Scale"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(16.dp).graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)) {
                drawCircle(color = if (isActive) gold.copy(alpha = 0.2f) else Color.Transparent)
            }
            Canvas(modifier = Modifier.size(6.dp)) {
                drawCircle(color = if (isActive) gold else Color.Gray)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text("IGY >> COMMAND_CENTER", color = gold, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(if (isActive) "CORE_STATUS: SHIELD_ACTIVE" else "CORE_STATUS: STANDBY", color = if (isActive) gold.copy(alpha = 0.7f) else Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun TactileTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    isPremium: Boolean = true,
    isDarkMode: Boolean,
    onClick: () -> Unit
) {
    val cardBg = if (isActive) color.copy(alpha = 0.1f) else (if (isDarkMode) Color(0xFF2D2D2D) else Color.White)
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val borderColor = if (isActive) color else (if (isPremium) color.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.2f))

    Surface(
        onClick = onClick,
        modifier = modifier.height(95.dp),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(if (isActive) 2.dp else 1.dp, borderColor),
        shadowElevation = if (isActive) 0.dp else 4.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize().drawBehind {
                if (!isActive && isPremium) {
                    // 3D Shadow Effect
                    drawPath(
                        path = Path().apply {
                            moveTo(0f, size.height)
                            lineTo(size.width, size.height)
                            lineTo(size.width, size.height - 4.dp.toPx())
                            lineTo(0f, size.height - 4.dp.toPx())
                            close()
                        },
                        color = Color.Black.copy(alpha = 0.1f)
                    )
                }
            },
            contentAlignment = Alignment.Center
        ) {
            if (!isPremium) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.03f)), contentAlignment = Alignment.TopEnd) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(14.dp).padding(4.dp))
                }
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, tint = if (isPremium) color else Color.Gray, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(label, color = if (isPremium) deepGray else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                if (!isPremium) {
                    Text("PREMIUM", color = Color.Gray, fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HubAppItem(
    app: AppInfo, 
    isDarkMode: Boolean, 
    isPremium: Boolean,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onFocusMode: () -> Unit, 
    onNormalMode: () -> Unit
) {
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val blue = Color(0xFF2D42FF)
    var showMenu by remember { mutableStateOf(false) }
    
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { 
                        if (isMultiSelectMode) onToggleSelect()
                        else if (isPremium) onFocusMode() 
                        else onNormalMode() 
                    },
                    onLongClick = { if (!isMultiSelectMode) showMenu = true }
                )
                .background(if (isSelected) blue.copy(alpha = 0.1f) else Color.Transparent)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberDrawablePainter(app.icon), 
                contentDescription = null, 
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, color = if (isSelected) blue else deepGray, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(app.packageName, color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            
            if (isMultiSelectMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            } else {
                if (!isPremium) Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray.copy(alpha = 0.3f), modifier = Modifier.size(14.dp))
                else Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray.copy(alpha = 0.2f))
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(if (isDarkMode) Color(0xFF2D2D2D) else Color.White).border(0.5.dp, Color.Gray)
        ) {
            DropdownMenuItem(
                text = { Text("🚀 Launch Normal Mode (Free)", fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                onClick = { 
                    showMenu = false
                    onNormalMode() 
                }
            )
            DropdownMenuItem(
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔒 Launch Focus Mode (VPN)", fontSize = 12.sp, fontFamily = FontFamily.Monospace) 
                        if (!isPremium) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                        }
                    }
                },
                onClick = { 
                    showMenu = false
                    onFocusMode() 
                }
            )
        }
    }
}

private fun activateMode(context: Context, mode: AppMode, isGlobal: Boolean, isStealth: Boolean, targetApp: String?) {
    val (token, _, isPremium) = IgyPreferences.getAuth(context)
    val vpnIntent = android.net.VpnService.prepare(context)
    
    if (token.isEmpty() || vpnIntent != null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return
    }

    if (isStealth && !isPremium) {
        Toast.makeText(context, "PREMIUM_REQUIRED", Toast.LENGTH_SHORT).show()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("FORCE_ACCOUNT", true)
        }
        context.startActivity(intent)
        return
    }

    IgyPreferences.saveMode(context, mode)
    IgyPreferences.setVpnTunnelMode(context, isGlobal)
    IgyPreferences.setStealthMode(context, isStealth)
    
    if (targetApp != null) {
        IgyPreferences.saveFocusTarget(context, targetApp)
        IgyPreferences.addRecentApp(context, targetApp)
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
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        } catch (e: Exception) {}
    }
}

fun wheat(isDarkMode: Boolean) = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
