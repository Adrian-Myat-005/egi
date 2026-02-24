package com.example.igy

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

enum class Screen {
    TERMINAL, ACCOUNT, SETTINGS, AUTO_START_PICKER
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.statusBarColor = Color.Black.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            var isDarkMode by remember { mutableStateOf(IgyPreferences.isDarkMode(this)) }
            IgyTerminalTheme(isDarkMode) {
                MainContent(isDarkMode, onThemeChange = { 
                    isDarkMode = it
                    IgyPreferences.setDarkMode(this, it)
                })
            }
        }
    }
}

@Composable
fun MainContent(isDarkMode: Boolean, onThemeChange: (Boolean) -> Unit) {
    var currentScreen by remember { mutableStateOf(Screen.TERMINAL) }
    var dnsLogMessage by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverUrl = remember { IgyPreferences.getSyncEndpoint(context) ?: "https://egi-67tg.onrender.com" }
    val authData = remember { mutableStateOf(IgyPreferences.getAuth(context)) }
    val (_, _, isPremium) = authData.value

    // Refresh auth on screen return
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.TERMINAL) {
            authData.value = IgyPreferences.getAuth(context)
        }
    }

    // --- PRE-WARM SERVER (Render Wake-up) ---
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("$serverUrl/api/ping")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.responseCode // Triggers the wake-up request
                TrafficEvent.log("CORE >> PRE_WARM_SIGNAL_SENT")
            } catch (e: Exception) {
                // Ignore errors; this is just a best-effort wake-up
            }
        }
    }

    if (showLogs) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            containerColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6) ,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxSize().padding(8.dp),
            text = { TerminalLog(isDarkMode, onClose = { showLogs = false }) },
            confirmButton = {
                TextButton(onClick = { showLogs = false }) {
                    Text("X", color = Color.Red, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState != Screen.TERMINAL) {
                (slideInHorizontally { width -> width } + fadeIn()).togetherWith(slideOutHorizontally { width -> -width } + fadeOut())
            } else {
                (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(slideOutHorizontally { width -> width } + fadeOut())
            }.using(SizeTransform(clip = false))
        },
        label = "ScreenTransition"
    ) { screen ->
        when (screen) {
            Screen.TERMINAL -> TerminalDashboard(isDarkMode, isPremium,
                onOpenHub = { 
                    context.startActivity(Intent(context, SelectionHubActivity::class.java))
                },
                onOpenAccount = { currentScreen = Screen.ACCOUNT },
                onOpenSettings = { currentScreen = Screen.SETTINGS },
                onShowLogs = { showLogs = true }
            )
            Screen.ACCOUNT -> TerminalAccountScreen(isDarkMode, onBack = { currentScreen = Screen.TERMINAL })
            Screen.SETTINGS -> TerminalSettingsScreen(isDarkMode, isPremium, onThemeChange, 
                onBack = { currentScreen = Screen.TERMINAL }, 
                onOpenAutoStartPicker = { currentScreen = Screen.AUTO_START_PICKER },
                onOpenAccount = { currentScreen = Screen.ACCOUNT }
            )
            Screen.AUTO_START_PICKER -> AutoStartPickerScreen(isDarkMode, onBack = { currentScreen = Screen.SETTINGS })
        }
    }
}

@Composable
fun TerminalSettingsScreen(isDarkMode: Boolean, isPremium: Boolean, onThemeChange: (Boolean) -> Unit, onBack: () -> Unit, onOpenAutoStartPicker: () -> Unit, onOpenAccount: () -> Unit) {
    val context = LocalContext.current
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    val scope = rememberCoroutineScope()
    
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val currentVersion = packageInfo.versionName ?: "1.0"
    
    var updateStatus by remember { mutableStateOf("V$currentVersion (LATEST)") }
    var isChecking by remember { mutableStateOf(false) }

    // Column
    Column(
        modifier = Modifier.fillMaxSize().background(creamColor).padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("IGY >> SYSTEM_SETTINGS", color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        // --- SECTION 1: PERMISSION & SYSTEM HEALTH ---
        SettingsHeader("1. PERMISSION & SYSTEM HEALTH")
        
        // 1.1 VPN Permission
        val isVpnPrepared = android.net.VpnService.prepare(context) == null
        var isVpnPermLoading by remember { mutableStateOf(false) }
        PermissionItem("VPN Service Access", isVpnPrepared, isDarkMode, isVpnPermLoading) {
            scope.launch {
                isVpnPermLoading = true
                delay(300)
                val intent = android.net.VpnService.prepare(context)
                if (intent != null) context.startActivity(intent)
                isVpnPermLoading = false
            }
        }

        // 1.2 Battery Optimization
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
        var isBattPermLoading by remember { mutableStateOf(false) }
        PermissionItem("Unrestricted Battery", isIgnoringBattery, isDarkMode, isBattPermLoading) {
            scope.launch {
                isBattPermLoading = true
                delay(300)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                isBattPermLoading = false
            }
        }

        // 1.4 Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            var isNotifPermLoading by remember { mutableStateOf(false) }
            PermissionItem("System Notifications", hasNotifPermission, isDarkMode, isNotifPermLoading) {
                scope.launch {
                    isNotifPermLoading = true
                    delay(300)
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                    isNotifPermLoading = false
                }
            }
        }

        // 1.5 App Usage Access (For Auto-Connect)
        var isUsagePermLoading by remember { mutableStateOf(false) }
        val hasUsageStats = remember { 
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        }
        
        PermissionItem("App Usage Access", hasUsageStats, isDarkMode, isUsagePermLoading) {
            scope.launch {
                isUsagePermLoading = true
                delay(300)
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                context.startActivity(intent)
                isUsagePermLoading = false
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- SECTION 2: VPN & NETWORK CONTROL ---
        SettingsHeader("2. VPN & NETWORK CONTROL")
        
        // --- NEW: SMART TILE INSTALLER ---
        TileInstallerSection(isDarkMode)
        
        SettingsToggle("Dark Theme", isDarkMode) {
            onThemeChange(it)
        }
        var localBypass by remember { mutableStateOf(IgyPreferences.getLocalBypass(context)) }
        SettingsToggle("Local Network Access", localBypass) {
            localBypass = it
            IgyPreferences.setLocalBypass(context, it)
        }
        
        // --- TACTICAL TIP: ALWAYS-ON ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .background(cardBg.copy(alpha = 0.5f))
                .border(0.5.dp, Color(0xFFB8860B), RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Column {
                Text("TACTICAL_TIP: FULL_SECURITY", color = Color(0xFFB8860B), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("Enable 'Always-on VPN' and 'Block connections without VPN' in system settings to prevent data leaks.", 
                    color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }

        var isAlwaysOnLoading by remember { mutableStateOf(false) }
        TactileButton("Always-On VPN Setup", isDarkMode = isDarkMode, isLoading = isAlwaysOnLoading, onClick = {
            scope.launch {
                isAlwaysOnLoading = true
                delay(300)
                try {
                    val intent = Intent("android.net.vpn.SETTINGS")
                    context.startActivity(intent)
                } catch (e: Exception) {
                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                }
                isAlwaysOnLoading = false
            }
        })

        var autoStartTrigger by remember { mutableStateOf(IgyPreferences.isAutoStartTriggerEnabled(context)) }

        val vpnLauncherForAuto = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                autoStartTrigger = true
                IgyPreferences.setAutoStartTriggerEnabled(context, true)
                if (IgyVpnService.isRunning) {
                    Toast.makeText(context, "RESTART SHIELD TO APPLY", Toast.LENGTH_SHORT).show()
                }
                TrafficEvent.log("USER >> AUTO_START_READY_WITH_PERM")
            } else {
                autoStartTrigger = false
                IgyPreferences.setAutoStartTriggerEnabled(context, false)
                Toast.makeText(context, "VPN PERMISSION REQUIRED", Toast.LENGTH_SHORT).show()
            }
        }

        SettingsToggle("Auto-Connect-VPN", autoStartTrigger) { enabled ->
            if (enabled && !isPremium) {
                Toast.makeText(context, "PREMIUM_REQUIRED", Toast.LENGTH_SHORT).show()
                onOpenAccount()
                return@SettingsToggle
            }
            if (enabled) {
                // --- PRE-FLIGHT VPN CHECK ---
                val vpnIntent = android.net.VpnService.prepare(context)
                if (vpnIntent != null) {
                    vpnLauncherForAuto.launch(vpnIntent)
                } else {
                    autoStartTrigger = true
                    IgyPreferences.setAutoStartTriggerEnabled(context, true)
                    if (IgyVpnService.isRunning) {
                        Toast.makeText(context, "RESTART SHIELD TO APPLY", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                autoStartTrigger = false
                IgyPreferences.setAutoStartTriggerEnabled(context, false)
                if (IgyVpnService.isRunning) {
                    Toast.makeText(context, "RESTART SHIELD TO APPLY", Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (autoStartTrigger) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else true

            var isBattLoading by remember { mutableStateOf(false) }
            TactileButton(
                text = if (isIgnoringBattery) "Battery-Saver: OK" else "Battery-Saver: Restricted",
                isDarkMode = isDarkMode,
                isLoading = isBattLoading,
                onClick = {
                    scope.launch {
                        isBattLoading = true
                        delay(300)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBattery) {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        } else {
                            Toast.makeText(context, "BATTERY_MANAGEMENT: UNRESTRICTED", Toast.LENGTH_SHORT).show()
                        }
                        isBattLoading = false
                    }
                }
            )

            var isAutoAppsLoading by remember { mutableStateOf(false) }
            TactileButton("Auto-Connect-VPN-Apps", isDarkMode = isDarkMode, isLoading = isAutoAppsLoading, onClick = {
                scope.launch {
                    isAutoAppsLoading = true
                    delay(300)
                    onOpenAutoStartPicker()
                    isAutoAppsLoading = false
                }
            })
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- SECTION 3: SOFTWARE UPDATE ---
        SettingsHeader("3. SOFTWARE UPDATE")
        Text("BUILD_VERSION: $currentVersion", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("STATUS: $updateStatus", color = if (updateStatus.contains("FOUND")) Color(0xFF2D42FF) else Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(12.dp))
        
        TactileButton(
            text = if (isChecking) "Scanning..." else "Check for Updates",
            isDarkMode = isDarkMode,
            isLoading = isChecking,
            onClick = {
                if (isChecking) return@TactileButton
                scope.launch {
                    isChecking = true
                    updateStatus = "SCANNING_REPOSITORIES..."
                    val latestVersion = checkForGithubUpdate(currentVersion)
                    isChecking = false
                    
                    if (latestVersion != null) {
                        updateStatus = "UPDATE_FOUND: V$latestVersion"
                        Toast.makeText(context, "NEW_VERSION_AVAILABLE", Toast.LENGTH_LONG).show()
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Amyat604/Igy-Shield/releases/latest"))
                        context.startActivity(intent)
                    } else {
                        updateStatus = "YOU_ARE_ON_LATEST"
                    }
                }
            }
        )

        Spacer(modifier = Modifier.weight(1f))
        Text("Back", color = deepGray, modifier = Modifier.clickable { onBack() }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

private suspend fun checkForGithubUpdate(currentVersion: String): String? = withContext(Dispatchers.IO) {
    try {
        val url = java.net.URL("https://api.github.com/repos/Amyat604/Igy-Shield/releases/latest")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "Igy-Shield-App")

        if (conn.responseCode == 200) {
            val res = JSONObject(conn.inputStream.bufferedReader().readText())
            val latestTag = res.getString("tag_name").replace("v", "").trim()
            if (latestTag != currentVersion.trim()) {
                return@withContext latestTag
            }
        }
    } catch (e: Exception) {
        TrafficEvent.log("UPDATE >> ERR: ${e.message}")
    }
    null
}

@Composable
fun SettingsHeader(title: String) {
    Text("> $title", color = Color(0xFFB8860B), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), fontFamily = FontFamily.Monospace)
}

@Composable
fun SettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun PermissionItem(label: String, granted: Boolean, isDarkMode: Boolean, isLoading: Boolean = false, onClick: () -> Unit) {
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            delay(200)
            showLoading = true
        } else {
            showLoading = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(cardBg)
            .border(0.5.dp, wheat)
            .clickable(enabled = !isLoading) { onClick() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = deepGray)
            Text(
                if (granted) "STATUS: GRANTED" else "STATUS: RESTRICTED",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (granted) Color(0xFF2D42FF) else Color.Red
            )
        }
        if (showLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = if (granted) Color(0xFF2D42FF) else Color(0xFFB8860B), strokeWidth = 2.dp)
        } else {
            Text(
                if (granted) "[ OK ]" else "[ CONFIGURE ]",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (granted) Color(0xFF2D42FF) else Color(0xFFB8860B)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TactileButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false,
    contentColor: Color? = null,
    elevation: Dp = 4.dp,
    isLoading: Boolean = false
) {
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            delay(200)
            showLoading = true
        } else {
            showLoading = false
        }
    }
    
    Surface(
        onClick = { if (!isLoading) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(50.dp),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = elevation,
        color = cardBg,
        border = BorderStroke(1.dp, wheat)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = contentColor ?: deepGray,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor ?: deepGray
                )
            }
        }
    }
}

@Composable
fun TerminalAccountScreen(isDarkMode: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(IgyPreferences.getSyncEndpoint(context) ?: "https://egi-67tg.onrender.com") }
    var authData by remember { mutableStateOf(IgyPreferences.getAuth(context)) }
    val (savedToken, savedUser, isPremium) = authData
    var status by remember { mutableStateOf(if (savedToken.isEmpty()) "UNAUTHORIZED" else "LOGGED_IN: $savedUser") }
    val scope = rememberCoroutineScope()

    // Countdown State
    var countdown by remember { mutableStateOf(0) }
    var isAuthenticating by remember { mutableStateOf(false) }

    LaunchedEffect(isAuthenticating) {
        if (isAuthenticating) {
            countdown = 30
            while (countdown > 0 && isAuthenticating) {
                delay(1000)
                countdown--
            }
        }
    }

    // Region Selector State
    var regions by remember { mutableStateOf(listOf<JSONObject>()) }
    var selectedNodeId by remember { mutableStateOf(IgyPreferences.getSelectedNodeId(context)) }

    LaunchedEffect(savedToken) {
        if (savedToken.isNotEmpty() && isPremium) {
            val fetchedRegions = fetchRegions(serverUrl, savedToken)
            regions = fetchedRegions
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(creamColor).padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("IGY >> SYSTEM_AUTHENTICATION", color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = if (isAuthenticating) "Waking up server ($countdown s)..." else "STATUS: $status",
            color = if (isPremium) Color(0xFF2D42FF) else Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        if (isPremium && !isAuthenticating) Text("PREMIUM_ACCESS: GRANTED", color = Color(0xFF2D42FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        
        Spacer(modifier = Modifier.height(32.dp))

        if (savedToken.isEmpty()) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("USERNAME", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth().background(cardBg),
                enabled = !isAuthenticating,
                textStyle = androidx.compose.ui.text.TextStyle(color = deepGray, fontFamily = FontFamily.Monospace)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("PASSWORD", color = Color.Gray) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().background(cardBg),
                enabled = !isAuthenticating,
                textStyle = androidx.compose.ui.text.TextStyle(color = deepGray, fontFamily = FontFamily.Monospace)
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            TactileButton(
                text = "Login",
                isDarkMode = isDarkMode,
                isLoading = isAuthenticating,
                contentColor = Color(0xFF2D42FF),
                onClick = {
                    if (isAuthenticating) return@TactileButton
                    scope.launch {
                        try {
                            isAuthenticating = true
                            val result = performAuth(serverUrl, username.trim(), password, false)
                            if (result != null) {
                                IgyPreferences.saveAuth(context, result.token, result.username, result.isPremium, result.expiry)
                                authData = IgyPreferences.getAuth(context)
                                status = "LOGGED_IN: ${result.username}"
                                // PRE-SYNC KEY
                                val currentId = IgyPreferences.getSelectedNodeId(context)
                                val key = fetchVpnConfig(serverUrl, result.token, currentId)
                                if (key != null) IgyPreferences.saveOutlineKey(context, key)
                            } else {
                                status = "LOGIN_FAILED: RECHECK_DATA"
                                Toast.makeText(context, "LOGIN_FAILED", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            status = "ERROR: SERVER_TIMEOUT"
                            Toast.makeText(context, "ERROR: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            isAuthenticating = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            if (isPremium && regions.isNotEmpty()) {
                Text("Server Locations", color = deepGray, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                
                regions.forEach { region ->
                    val id = region.getInt("id")
                    val name = region.getString("regionName")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(if (selectedNodeId == id) Color.White else Color.Transparent)
                            .border(0.5.dp, if (selectedNodeId == id) deepGray else wheat)
                            .clickable {
                                if (isAuthenticating) return@clickable
                                selectedNodeId = id
                                IgyPreferences.setSelectedNodeId(context, id)
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(name, color = if (selectedNodeId == id) deepGray else Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        if (selectedNodeId == id) Text("Active", color = Color(0xFF2D42FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(if (selectedNodeId == -1) Color.White else Color.Transparent)
                        .border(0.5.dp, if (selectedNodeId == -1) deepGray else wheat)
                        .clickable {
                            if (isAuthenticating) return@clickable
                            selectedNodeId = -1
                            IgyPreferences.setSelectedNodeId(context, -1)
                        }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Default Server", color = if (selectedNodeId == -1) deepGray else Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (selectedNodeId == -1) Text("Selected", color = Color(0xFF2D42FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            TactileButton(
                text = "Logout",
                isDarkMode = isDarkMode,
                contentColor = Color.Red,
                onClick = {
                    if (isAuthenticating) return@TactileButton
                    IgyPreferences.clearAuth(context)
                    authData = IgyPreferences.getAuth(context)
                    status = "UNAUTHORIZED"
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        TactileButton(
            text = "Premium",
            isDarkMode = isDarkMode,
            contentColor = Color(0xFFB8860B),
            elevation = 4.dp,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/Amyat604"))
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(32.dp))
        Text("Back", color = deepGray, modifier = Modifier.clickable { onBack() }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

private suspend fun fetchRegions(serverUrl: String, token: String): List<JSONObject> = withContext(Dispatchers.IO) {
    try {
        val url = java.net.URL("$serverUrl/api/vpn/regions")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (conn.responseCode == 200) {
            val res = JSONArray(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            val list = mutableListOf<JSONObject>()
            for (i in 0 until res.length()) { list.add(res.getJSONObject(i)) }
            return@withContext list
        }
    } catch (e: Exception) {}
    emptyList()
}

data class AuthResult(val token: String, val username: String, val isPremium: Boolean, val expiry: Long)

private suspend fun performAuth(serverUrl: String, user: String, pass: String, isRegister: Boolean): AuthResult? = withContext(Dispatchers.IO) {
    try {
        val url = java.net.URL("$serverUrl/api/auth/${if (isRegister) "register" else "login"}")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30000 // 30s wakeup allowance
        conn.readTimeout = 30000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        
        val body = JSONObject().apply {
            put("username", user)
            put("password", pass)
        }
        conn.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        
        if (conn.responseCode == 200) {
            val res = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            val userObj = res.optJSONObject("user") ?: return@withContext null
            return@withContext AuthResult(
                res.optString("token", ""),
                userObj.optString("username", "Unknown"),
                userObj.optBoolean("isPremium", false),
                userObj.optLong("expiry", 0L)
            )
        } else {
            val errorText = try { 
                val errorStream = conn.errorStream ?: conn.inputStream
                val json = JSONObject(errorStream.bufferedReader(Charsets.UTF_8).readText())
                json.optString("error", "UNKNOWN_ERROR")
            } catch (e: Exception) { "CODE_${conn.responseCode}" }
            TrafficEvent.log("AUTH >> FAIL: $errorText")
        }
    } catch (e: Exception) {
        TrafficEvent.log("AUTH >> ERR: ${e.message}")
    }
    null
}

private suspend fun fetchVpnConfig(serverUrl: String, token: String, nodeId: Int): String? = withContext(Dispatchers.IO) {
    try {
        val url = java.net.URL("$serverUrl/api/vpn/config${if (nodeId != -1) "?nodeId=$nodeId" else ""}")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.setRequestProperty("Authorization", "Bearer $token")
        
        if (conn.responseCode == 200) {
            val res = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            return@withContext res.getString("config")
        } else {
            val errorText = try { 
                val json = JSONObject(conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "{}")
                json.optString("error", "CONFIG_FETCH_FAILED")
            } catch (e: Exception) { "CODE_${conn.responseCode}" }
            TrafficEvent.log("VPN >> ERR: $errorText")
        }
    } catch (e: Exception) {
        TrafficEvent.log("VPN >> FATAL: ${e.message}")
    }
    null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalDashboard(
    isDarkMode: Boolean,
    isPremium: Boolean,
    onOpenHub: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowLogs: () -> Unit
) {
    val context = LocalContext.current
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    val gold = Color(0xFFB8860B)
    val scope = rememberCoroutineScope()
    val isSecure by TrafficEvent.vpnActive.collectAsState()
    var isBooting by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isStealthMode by remember { mutableStateOf(IgyPreferences.isStealthMode(context)) }
    var isVpnTunnelGlobal by remember { mutableStateOf(IgyPreferences.isVpnTunnelGlobal(context)) }

    // Connecting Pulse Animation
    val connectingTransition = rememberInfiniteTransition(label = "ConnectingPulse")
    val connectingPulseScale by connectingTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ConnectingPulseScale"
    )

    var showManual by remember { mutableStateOf(false) }
    var currentSsid by remember { mutableStateOf<String?>(null) }
    var isBatteryOptimized by remember { mutableStateOf(false) }
    var isStrictBlocking by remember { mutableStateOf(false) }
    var showLockdownDialog by remember { mutableStateOf(false) }

    // Reset booting state when VPN becomes active
    LaunchedEffect(isSecure) {
        if (isSecure) {
            isBooting = false
        }
    }

    // Detect if 'Block connections without VPN' is active
    LaunchedEffect(isSecure) {
        while (true) {
            if (!isSecure) {
                val isBlocked = withContext(Dispatchers.IO) {
                    try {
                        val socket = java.net.Socket()
                        socket.connect(java.net.InetSocketAddress("1.1.1.1", 53), 1500)
                        socket.close()
                        false
                    } catch (e: Exception) {
                        true
                    }
                }
                isStrictBlocking = isBlocked
            } else {
                isStrictBlocking = false
            }
            delay(5000)
        }
    }

    // Check battery optimization status
    LaunchedEffect(Unit) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        while (true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                isBatteryOptimized = pm.isIgnoringBatteryOptimizations(context.packageName)
            }
            delay(10000)
        }
    }

    // Simplified HUD Loop (Pings 1.1.1.1 for Internet Quality)
    var currentPing by remember { mutableStateOf(0) }
    val animatedPing by animateIntAsState(targetValue = currentPing, animationSpec = tween(300), label = "PingAnim")
    var currentJitter by remember { mutableStateOf(0) }
    val blockedCount by TrafficEvent.blockedCount.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            if (IgyNetwork.isAvailable()) {
                try {
                    val statsJson = withContext(Dispatchers.IO) {
                        IgyNetwork.measureNetworkStats("1.1.1.1")
                    }
                    if (!statsJson.isNullOrEmpty()) {
                        val json = JSONObject(statsJson)
                        currentPing = json.optInt("ping", -1)
                        currentJitter = json.optInt("jitter", 0)
                    }
                } catch (e: Exception) {
                    currentPing = -1
                }
            }
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            TrafficEvent.log("USER >> PERMISSION_GRANTED")
            startIgyVpnService(context)
            isBooting = false
        } else {
            TrafficEvent.log("USER >> PERMISSION_DENIED")
            isBooting = false
            Toast.makeText(context, "KERNEL ACCESS DENIED", Toast.LENGTH_SHORT).show()
        }
    }

    // Permission request for Notifications (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            TrafficEvent.log("USER >> NOTIF_PERMISSION_DECLINED")
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // WiFi Info Polling
    LaunchedEffect(Unit) {
        val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        while (true) {
            @Suppress("DEPRECATION")
            val ssid = wifiManager.connectionInfo.ssid.replace("\"", "")
            if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                currentSsid = ssid
            } else {
                currentSsid = null
            }
            delay(5000)
        }
    }

    // Heartbeat Pulse Animation for Active State
    val activeTransition = rememberInfiniteTransition(label = "ActivePulse")
    val activePulseScale by activeTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSecure) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ActivePulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(creamColor)
            .padding(16.dp)
    ) {
        // --- TOP SECTION: MODERN HEADER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // WiFi Status Chip
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, wheat),
                modifier = Modifier.height(32.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync, // Placeholder for Wifi icon
                        contentDescription = "Wifi",
                        tint = if (currentSsid != null) Color(0xFF20B2AA) else Color(0xFFB8860B),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (currentSsid != null) currentSsid!! else "No WiFi",
                        color = if (currentSsid != null) Color(0xFF20B2AA) else Color(0xFFB8860B),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Action Icons Row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var isSettingsLoading by remember { mutableStateOf(false) }
                IconButton(onClick = {
                    scope.launch {
                        isSettingsLoading = true
                        delay(300)
                        onOpenSettings()
                        isSettingsLoading = false
                    }
                }) {
                    if (isSettingsLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFF4682B4), strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings", tint = Color(0xFF4682B4))
                    }
                }

                var isAccountLoading by remember { mutableStateOf(false) }
                IconButton(onClick = {
                    scope.launch {
                        isAccountLoading = true
                        delay(300)
                        onOpenAccount()
                        isAccountLoading = false
                    }
                }) {
                    if (isAccountLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFFDAA520), strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Filled.Person, contentDescription = "Account", tint = Color(0xFFDAA520))
                    }
                }

                var isManualLoading by remember { mutableStateOf(false) }
                IconButton(onClick = {
                    scope.launch {
                        isManualLoading = true
                        delay(300)
                        showManual = true
                        isManualLoading = false
                    }
                }) {
                    if (isManualLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = deepGray, strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Filled.Info, contentDescription = "Help", tint = deepGray)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            StatsTile("PING", if (currentPing == -1) "--" else "$animatedPing ms", 1f, if (currentPing < 80) Color(0xFF2D42FF) else Color.Red, isDarkMode)
            StatsTile("JITTER", "$currentJitter ms", 1f, Color(0xFF20B2AA), isDarkMode)
            StatsTile("STATUS", if (isSecure) "CONNECTED" else "STANDBY", 1.4f, if (isSecure) Color(0xFF2D42FF) else Color.Gray, isDarkMode)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(cardBg)
                .border(0.5.dp, wheat),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer(scaleX = activePulseScale, scaleY = activePulseScale)) {
                Text(
                    text = "Blocked Tracking",
                    color = deepGray.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = String.format("%,d", blockedCount),
                    color = if (isSecure) Color(0xFF2D42FF) else deepGray.copy(alpha = 0.3f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                if (isStrictBlocking) {
                    Text(
                        "STRICT_LOCKDOWN_DETECTED",
                        color = Color.Red,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { showLockdownDialog = true }
                    )
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().height(65.dp)) {
                var isHubLoading by remember { mutableStateOf(false) }
                GridButton(
                    text = "IGY >> COMMAND_CENTER",
                    isDarkMode = isDarkMode,
                    modifier = Modifier.weight(1f),
                    color = gold,
                    isLoading = isHubLoading
                ) {
                    scope.launch {
                        isHubLoading = true
                        delay(300)
                        onOpenHub()
                        isHubLoading = false
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(55.dp)) {
                var isLogLoading by remember { mutableStateOf(false) }
                GridButton(
                    text = "Activity Log",
                    isDarkMode = isDarkMode,
                    modifier = Modifier.weight(1f),
                    isLoading = isLogLoading
                ) {
                    scope.launch {
                        isLogLoading = true
                        delay(300)
                        onShowLogs()
                        isLogLoading = false
                    }
                }
                GridButton(
                    text = if (isBatteryOptimized) "Battery: OK" else "Battery: FIX",
                    isDarkMode = isDarkMode,
                    modifier = Modifier.weight(1f),
                    color = if (isBatteryOptimized) Color(0xFF2D42FF) else Color.Red
                ) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        }
                    } else {
                        Toast.makeText(context, "BATTERY_MANAGEMENT: UNRESTRICTED", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(55.dp)) {
                GridButton(
                    text = "System Refresh",
                    isDarkMode = isDarkMode,
                    modifier = Modifier.weight(1f),
                    isLoading = isRefreshing
                ) { 
                    TrafficEvent.log("CORE >> REFRESHING_STACK...")
                    TrafficEvent.updateCount(0)
                    scope.launch {
                        isRefreshing = true
                        IgyPreferences.setSelectedNodeId(context, -1)
                        delay(1000)
                        isRefreshing = false
                        TrafficEvent.log("CORE >> REFRESH_SUCCESS")
                        Toast.makeText(context, "SYSTEM_REFRESHED", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // --- MAIN CONNECT BUTTON ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp) 
                .padding(top = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            // Pulse Effect Layer
            Box(
                modifier = Modifier
                    .size(if (isBooting) 90.dp else 80.dp)
                    .graphicsLayer(scaleX = if (isBooting) connectingPulseScale else if (isSecure) activePulseScale else 1f, scaleY = if (isBooting) connectingPulseScale else if (isSecure) activePulseScale else 1f)
                    .background(
                        color = if (isSecure) Color(0xFF2D42FF).copy(alpha = 0.2f) else Color.Transparent,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )

            // Main Button Surface
            Surface(
                onClick = {
                    handleExecuteToggle(context, isBooting, isStealthMode, isVpnTunnelGlobal, onOpenHub, vpnLauncher) { isBooting = it }
                },
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer(scaleX = activePulseScale, scaleY = activePulseScale),
                shape = CircleShape,
                color = if (isSecure) Color(0xFF2D42FF) else Color.White,
                border = BorderStroke(2.dp, if (isSecure) Color.Transparent else wheat),
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isBooting) {
                        CircularProgressIndicator(color = if (isSecure) Color.White else Color(0xFF2D42FF), modifier = Modifier.size(40.dp))
                    } else {
                        Icon(
                            imageVector = if (isSecure) Icons.Default.Shield else Icons.Default.Shield,
                            contentDescription = "Shield",
                            tint = if (isSecure) Color.White else Color(0xFF2D42FF),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}
                    .graphicsLayer(scaleX = if (isBooting) connectingPulseScale else if (isSecure) activePulseScale else 1f, scaleY = if (isBooting) connectingPulseScale else if (isSecure) activePulseScale else 1f),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = cardBg,
                border = BorderStroke(2.dp, if (isSecure) Color(0xFF2D42FF) else wheat),
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isBooting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color(0xFF2D42FF),
                            strokeWidth = 3.dp
                        )
                    } else {
                        // Power Icon or Text
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Sync, // Placeholder for Power icon
                                contentDescription = "Power",
                                tint = if (isSecure) Color(0xFF2D42FF) else if (isStrictBlocking && !isStealthMode) Color.Red else Color.Gray,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isSecure) "ON" else "OFF",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSecure) Color(0xFF2D42FF) else Color.Gray
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Status Text Below Button
        Text(
            text = when {
                isBooting -> "Initializing..."
                isSecure -> "SECURE CONNECTION ESTABLISHED"
                isStrictBlocking && !isStealthMode -> "LOCKED: CONFIG VPN"
                else -> "TAP TO CONNECT"
            },
            color = if (isSecure) Color(0xFF2D42FF) else deepGray,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showManual) { TacticalManual(onDismiss = { showManual = false }) }
}

@Composable
fun RowScope.StatsTile(label: String, value: String, weightRatio: Float, valueColor: Color, isDarkMode: Boolean) {
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    
    Card(
        modifier = Modifier
            .weight(weightRatio)
            .fillMaxHeight()
            .padding(4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(0.5.dp, wheat),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(label, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, color = valueColor, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowScope.GridButton(
    text: String,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF2D42FF),
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "ButtonScale")

    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            delay(200)
            showLoading = true
        } else {
            showLoading = false
        }
    }

    Card(
        onClick = { if (!isLoading) onClick() },
        modifier = modifier
            .fillMaxHeight()
            .padding(4.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, wheat),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().padding(8.dp)
        ) {
            if (showLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = color,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = text,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

private fun startIgyVpnService(context: Context) {
    try {
        val startIntent = Intent(context, IgyVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }
    } catch (e: Exception) {
        TrafficEvent.log("CORE >> START_FAIL")
    }
}

private fun handleExecuteToggle(
    context: Context,
    isBooting: Boolean,
    isStealthMode: Boolean,
    isGlobal: Boolean,
    onOpenHub: () -> Unit,
    vpnLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    setBooting: (Boolean) -> Unit
) {
    if (isBooting) return
    
    val currentIsRunning = IgyVpnService.isRunning
    if (currentIsRunning) {
        TrafficEvent.log("USER >> REQUEST_SHUTDOWN")
        context.startService(Intent(context, IgyVpnService::class.java).apply { action = IgyVpnService.ACTION_STOP })
        return
    }

    // Validation
    val vipList = IgyPreferences.getVipList(context)
    if (!isGlobal && vipList.isEmpty() && isStealthMode) {
        Toast.makeText(context, "PICK A FOCUS APP!", Toast.LENGTH_SHORT).show()
        onOpenHub()
        return
    }

    setBooting(true)
    TrafficEvent.log("USER >> INITIATING_BOOT")

    CoroutineScope(Dispatchers.Main).launch {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            startIgyVpnService(context)
            // Sync Tile immediately
            android.service.quicksettings.TileService.requestListeningState(context, android.content.ComponentName(context, IgyTileService::class.java))
        }
        delay(1500)
        setBooting(false)
    }
}

@Composable
fun TileInstallerSection(isDarkMode: Boolean) {
    val context = LocalContext.current
    var showAnimation by remember { mutableStateOf(false) }
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White

    Column(modifier = Modifier.fillMaxWidth()) {
        TactileButton(
            text = "Install Smart Button (One-Tap)",
            isDarkMode = isDarkMode,
            contentColor = Color(0xFF2D42FF),
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val statusBarManager = context.getSystemService(android.app.StatusBarManager::class.java)
                    val componentName = android.content.ComponentName(context, IgyTileService::class.java)
                    val icon = android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_shield_status)
                    statusBarManager.requestAddTileService(
                        componentName,
                        "Igy Shield",
                        icon,
                        { it.run() },
                        { _ -> }
                    )
                } else {
                    showAnimation = !showAnimation
                }
            }
        )

        if (showAnimation) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, Color(0xFFB8860B).copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("MANUAL INSTALLATION GUIDE", color = Color(0xFFB8860B), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(16.dp))
                    TileInstallationAnimation(isDarkMode)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("1. Swipe down twice from top status bar.\n2. Click the Pencil (Edit) icon.\n3. Find 'Igy Shield' and drag it up to your active buttons.", 
                        color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun TileInstallationAnimation(isDarkMode: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "TileAnim")
    
    val fingerY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3000
                0f at 0
                0f at 500
                100f at 1500
                100f at 3000
            }
        ), label = "FingerMove"
    )

    val iconAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3000
                0.3f at 1500
                1f at 2000
                1f at 3000
            }
        ), label = "IconPop"
    )

    Box(modifier = Modifier.size(200.dp, 120.dp).background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.TopCenter) {
        // Status Bar Representation
        Box(modifier = Modifier.fillMaxWidth().height(20.dp).background(Color.Gray.copy(alpha = 0.2f)))
        
        // Notification Panel
        Box(modifier = Modifier.size(160.dp, fingerY.dp).background(Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)))
        
        // Igy Icon dragging
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            tint = Color(0xFF2D42FF).copy(alpha = iconAlpha),
            modifier = Modifier.size(24.dp).offset(y = (fingerY + 10).dp)
        )

        // Hand/Finger
        Icon(
            imageVector = Icons.Default.Person, // Using person as proxy for finger icon
            contentDescription = null,
            tint = if (isDarkMode) Color.White else Color.Black,
            modifier = Modifier.size(32.dp).offset(y = fingerY.dp)
        )
    }
}

@Composable
fun TacticalManual(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        title = { Text("IGY >> QUICK_START_GUIDE", color = Color.Cyan, fontFamily = FontFamily.Monospace, fontSize = 16.sp) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    ManualSection("1. HOW TO CONNECT", "EN: Simply click Connect to start. If it's your first time, click Account to sign in.\nMM: Connect ကိုနှိပ်ပြီး စသုံးနိုင်ပါပြီ။ အကောင့်မရှိသေးရင် Account ထဲမှာ အကောင့်ဝင်ပါ။")
                    ManualSection("2. THREE MODES EXPLAINED", "• [VPN]: Encrypts ALL device traffic. Best for full privacy.\n• [VPN Focus]: ONLY encrypts traffic of apps you pick. Best for speed & target apps.\n• [Normal Focus]: ACCELERATE your VIP apps by blocking all background data thieves for maximum speed.\nMM: ဖုန်းတစ်ခုလုံးသုံးမလား (VPN)၊ app တစ်ခုချင်းသုံးမလား (VPN Focus) (သို့မဟုတ်) အင်တာနက်မြန်အောင် လုပ်မလား (Normal Focus) စိတ်ကြိုက်ရွေးပါ။")
                    ManualSection("3. FOR BEST PERFORMANCE", "EN: Go to Settings -> Enable 'Always-on VPN' in Android settings to prevent disconnects.\nMM: ဖုန်း Settings ထဲမှာ Always-on VPN ကို ဖွင့်ထားပေးရင် ပိုမြန်ပြီး ပိုတည်ငြိမ်ပါတယ်။")
                    ManualSection("4. NEED HELP?", "EN: If the internet stops working, click the refresh button on the main screen.\nMM: အင်တာနက်မရတော့ရင် refresh ခလုတ်ကို နှိပ်ပေးပါ။")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK", color = Color(0xFF2D42FF), fontFamily = FontFamily.Monospace) }
        }
    )
}

@Composable
fun ManualSection(title: String, desc: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("> $title", color = Color(0xFF2D42FF), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(desc, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Divider(color = Color.DarkGray, thickness = 0.5.dp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun IgyTerminalTheme(isDarkMode: Boolean, content: @Composable () -> Unit) {
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    
    val colorScheme = if (isDarkMode) {
        darkColorScheme(primary = Color.White, onPrimary = Color.Black, surface = Color(0xFF1A1A1A), onSurface = Color.White, background = Color(0xFF1A1A1A), onBackground = Color.White, secondary = Color.Gray, outline = Color(0xFF333333))
    } else {
        lightColorScheme(primary = Color.White, onPrimary = deepGray, surface = creamColor, onSurface = deepGray, background = creamColor, onBackground = deepGray, secondary = Color.Gray, outline = wheat)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = if (isDarkMode) Color.White else deepGray)),
        content = content
    )
}

@Composable
fun TerminalLog(isDarkMode: Boolean, onClose: () -> Unit) {
    val events = TrafficEvent.events.collectAsState(initial = "INITIALIZING...")
    val logHistory = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    
    LaunchedEffect(events.value) {
        if (events.value == "CONSOLE_CLEARED") { logHistory.clear() } 
        else {
            logHistory.add("${System.currentTimeMillis() % 100000} >> ${events.value}")
            if (logHistory.size > 50) logHistory.removeAt(0)
            listState.animateScrollToItem(logHistory.size)
        }
    }
    Column(modifier = Modifier.fillMaxSize().background(creamColor).padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Activity Log", color = deepGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Row {
                Text("Clear", color = Color(0xFFB8860B), fontSize = 12.sp, modifier = Modifier.clickable { TrafficEvent.clearLogs() }.padding(horizontal = 16.dp), fontFamily = FontFamily.Monospace)
                Text("X", color = Color.Red, fontSize = 12.sp, modifier = Modifier.clickable { onClose() }, fontFamily = FontFamily.Monospace)
            }
        }
        Divider(color = deepGray, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(logHistory) { log ->
                Text(text = log, color = if (log.contains("ERROR")) Color.Red else deepGray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
