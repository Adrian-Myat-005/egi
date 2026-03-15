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

            VroomEngineTheme(isDarkMode) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Background Dim & Dismiss
                    Surface(
                        modifier = Modifier.fillMaxSize().clickable { 
                            showContent = false
                            finish() 
                        },
                        color = Color.Black.copy(alpha = 0.7f)
                    ) {}

                    AnimatedVisibility(
                        visible = showContent,
                        enter = slideInVertically(
                            initialOffsetY = { -it },
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
                        ) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Box(modifier = Modifier.padding(top = 40.dp)) {
                            VroomFocusHub(isDarkMode, onAction = { 
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
fun VroomFocusHub(isDarkMode: Boolean, onAction: () -> Unit) {
    val context = LocalContext.current
    val vroomNavy = Color(0xFF020C1F)
    val vroomBlue = Color(0xFF00BFFF)

    val authData = remember { IgyPreferences.getAuth(context) }
    val isPremium = authData.third
    val isVpnActive = IgyVpnService.isRunning
    val isStealth = IgyPreferences.isStealthMode(context)
    val isGlobal = IgyPreferences.isVpnTunnelGlobal(context)

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

    val currentStealth = IgyPreferences.isStealthMode(context)

    Card(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .wrapContentHeight()
            .clickable(enabled = false) {},
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = vroomNavy),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            
            // 1. HUB HEADER
            VroomHubHeader(isVpnActive, vroomBlue)

            Spacer(modifier = Modifier.height(24.dp))

            // 2. MASTER TILES
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VroomHubTile(
                    label = "BOOST",
                    icon = Icons.Default.Speed,
                    isActive = isVpnActive && !isStealth,
                    modifier = Modifier.weight(1f)
                ) {
                    pendingStealthMode = false
                    isLibraryExpanded = true
                }

                VroomHubTile(
                    label = "GLOBAL",
                    icon = Icons.Default.Language,
                    isActive = isVpnActive && isStealth && isGlobal,
                    isPremium = isPremium,
                    modifier = Modifier.weight(1f)
                ) {
                    IgyPreferences.saveFocusTarget(context, "")
                    activateMode(context, AppMode.CASUAL, isGlobal = true, isStealth = true, targetApp = null)
                    onAction()
                }

                VroomHubTile(
                    label = "FOCUS",
                    icon = Icons.Default.FilterCenterFocus,
                    isActive = isVpnActive && isStealth && !isGlobal,
                    isPremium = isPremium,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isPremium) {
                        pendingStealthMode = true
                        isLibraryExpanded = true
                    } else {
                        Toast.makeText(context, "Premium Required", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. RECENT DOCK
            if (recentApps.isNotEmpty() && !isMultiSelectMode) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("RECENT TARGETS", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (isDeleteMode) {
                        Text("[ CANCEL ]", color = Color.Red, fontSize = 10.sp, modifier = Modifier.clickable { isDeleteMode = false })
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                        } else { appToDelete = app.packageName }
                                    } else {
                                        val stealth = pendingStealthMode ?: currentStealth
                                        activateMode(context, AppMode.FOCUS, isGlobal = false, isStealth = stealth, targetApp = app.packageName)
                                        onAction()
                                    }
                                },
                                onLongClick = { isDeleteMode = true; appToDelete = app.packageName }
                            )
                        ) {
                            Image(
                                painter = rememberDrawablePainter(app.icon),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, if (isSelectedForDelete) Color.Red else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(app.name.take(6), color = if (isSelectedForDelete) Color.Red else Color.White, fontSize = 9.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 4. APP LIBRARY
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SYSTEM LIBRARY", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { 
                    isMultiSelectMode = !isMultiSelectMode 
                    if (isMultiSelectMode) isLibraryExpanded = true
                }) {
                    Icon(if (isMultiSelectMode) Icons.Default.CheckCircle else Icons.Default.List, contentDescription = null, tint = vroomBlue, modifier = Modifier.size(20.dp))
                }
            }

            AnimatedVisibility(visible = isLibraryExpanded) {
                Column {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        itemsIndexed(installedApps) { _, app ->
                            VroomHubAppItem(
                                app = app,
                                isPremium = isPremium,
                                isSelected = selectedApps.contains(app.packageName),
                                isMultiSelectMode = isMultiSelectMode,
                                onToggleSelect = {
                                    val current = selectedApps.toMutableSet()
                                    if (current.contains(app.packageName)) current.remove(app.packageName) else current.add(app.packageName)
                                    selectedApps = current
                                },
                                onActivate = {
                                    val stealth = pendingStealthMode ?: true
                                    activateMode(context, AppMode.FOCUS, isGlobal = false, isStealth = stealth, targetApp = app.packageName)
                                    onAction()
                                }
                            )
                        }
                    }
                    if (isMultiSelectMode && selectedApps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        TactileVroomButton("START PROTECTING (${selectedApps.size} APPS)", color = vroomBlue) {
                            IgyPreferences.saveCasualWhitelist(context, selectedApps)
                            activateMode(context, AppMode.CASUAL, isGlobal = false, isStealth = true, targetApp = null)
                            onAction()
                        }
                    }
                }
            }

            if (!isLibraryExpanded) {
                TextButton(onClick = { isLibraryExpanded = true }) {
                    Text("EXPAND FULL LIBRARY ▾", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun VroomHubHeader(isActive: Boolean, activeColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = CircleShape,
            color = if (isActive) activeColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
            modifier = Modifier.size(12.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(3.dp).background(if (isActive) activeColor else Color.Gray, CircleShape))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text("VROOM ENGINE HUB", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Text(if (isActive) "ENGINE_ACTIVE" else "ENGINE_STANDBY", color = if (isActive) activeColor else Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun VroomHubTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    isPremium: Boolean = true,
    onClick: () -> Unit
) {
    val activeColor = Color(0xFF00BFFF)
    Surface(
        onClick = onClick,
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) activeColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, if (isActive) activeColor else Color.White.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = if (!isPremium) Color.Gray else if (isActive) activeColor else Color.White, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(label, color = if (!isPremium) Color.Gray else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (!isPremium) Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(10.dp))
        }
    }
}

@Composable
fun VroomHubAppItem(
    app: AppInfo, 
    isPremium: Boolean,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onToggleSelect: () -> Unit,
    onActivate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (isMultiSelectMode) onToggleSelect() else onActivate() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painter = rememberDrawablePainter(app.icon), contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(app.packageName, color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, maxLines = 1)
        }
        if (isMultiSelectMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00BFFF)))
        } else if (!isPremium) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
        }
    }
}

private fun activateMode(context: Context, mode: AppMode, isGlobal: Boolean, isStealth: Boolean, targetApp: String?) {
    val (token, _, isPremium) = IgyPreferences.getAuth(context)
    val vpnIntent = android.net.VpnService.prepare(context)
    
    if (token.isEmpty() || vpnIntent != null) {
        val intent = Intent(context, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        return
    }

    if (isStealth && !isPremium) {
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { context.startForegroundService(startIntent) }
    else { context.startService(startIntent) }

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
