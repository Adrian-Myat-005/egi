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
import kotlinx.coroutines.delay

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
    
    var isMultiSelectEnabled by remember { mutableStateOf(false) }
    val selectedApps = remember { mutableStateListOf<String>() }
    var searchQuery by remember { mutableStateOf("") }
    
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

    val filteredApps = remember(searchQuery) {
        if (searchQuery.isEmpty()) installedApps
        else installedApps.filter { it.name.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.85f)
            .clickable(enabled = false) {}, 
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = creamColor),
        border = BorderStroke(1.dp, Color(0xFF2D42FF).copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // HEADER
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("IGY >> COMMAND_CENTER", color = Color(0xFF2D42FF), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("SELECT_OPERATION_MODE", color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MULTI", color = if(isMultiSelectEnabled) Color(0xFF2D42FF) else Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = isMultiSelectEnabled,
                        onCheckedChange = { 
                            isMultiSelectEnabled = it 
                            if (!it) selectedApps.clear()
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF2D42FF), checkedTrackColor = Color(0xFF2D42FF).copy(alpha = 0.3f))
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            // SEARCH BAR
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                placeholder = { Text("Search app...", fontSize = 12.sp, color = Color.Gray) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, size(18.dp), tint = Color.Gray) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF2D42FF),
                    unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f)
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // GLOBAL VPN QUICK ACTION
            if (!isMultiSelectEnabled && searchQuery.isEmpty()) {
                Surface(
                    onClick = {
                        activateMode(context, AppMode.CASUAL, isGlobal = true, isStealth = true, targetApp = null)
                        onAction()
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    color = Color(0xFF2D42FF).copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, Color(0xFF2D42FF)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = Color(0xFF2D42FF))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("ACTIVATE GLOBAL VPN", color = Color(0xFF2D42FF), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // APP LIST
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(filteredApps) { index, app ->
                    HubAppItem(
                        app = app,
                        isDarkMode = isDarkMode,
                        isMultiSelect = isMultiSelectEnabled,
                        isSelected = selectedApps.contains(app.packageName),
                        onToggleSelect = {
                            if (selectedApps.contains(app.packageName)) selectedApps.remove(app.packageName)
                            else selectedApps.add(app.packageName)
                        },
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

            // BLAST FOOTER
            AnimatedVisibility(visible = isMultiSelectEnabled && selectedApps.isNotEmpty(), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            IgyPreferences.saveCasualWhitelist(context, selectedApps.toSet())
                            activateMode(context, AppMode.CASUAL, isGlobal = false, isStealth = false, targetApp = null)
                            onAction()
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB8860B).copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("🚀 BOOST ALL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    
                    Button(
                        onClick = {
                            IgyPreferences.saveCasualWhitelist(context, selectedApps.toSet())
                            activateMode(context, AppMode.CASUAL, isGlobal = false, isStealth = true, targetApp = null)
                            onAction()
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D42FF).copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("🔒 VPN ALL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun Modifier.size(dp: androidx.compose.ui.unit.Dp): Modifier = this.size(dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HubAppItem(
    app: AppInfo, 
    isDarkMode: Boolean, 
    isMultiSelect: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onFocusMode: () -> Unit, 
    onNormalMode: () -> Unit
) {
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    var showMenu by remember { mutableStateOf(false) }
    
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (isMultiSelect) onToggleSelect() else onFocusMode() },
                    onLongClick = { if (!isMultiSelect) showMenu = true }
                )
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberDrawablePainter(app.icon), 
                contentDescription = null, 
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, color = deepGray, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(app.packageName, color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            
            if (isMultiSelect) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF2D42FF))
                )
            } else {
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray.copy(alpha = 0.2f))
            }
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
}
