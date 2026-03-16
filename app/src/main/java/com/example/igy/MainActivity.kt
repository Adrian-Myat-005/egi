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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

enum class Screen {
    TERMINAL, ACCOUNT, SETTINGS, AUTO_START_PICKER, SERVERS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.statusBarColor = Color.Black.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            var isDarkMode by remember { mutableStateOf(IgyPreferences.isDarkMode(this)) }
            IgyEngineTheme(isDarkMode) {
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
    val context = LocalContext.current
    val forceAccount = (context as? androidx.activity.ComponentActivity)?.intent?.getBooleanExtra("FORCE_ACCOUNT", false) ?: false
    var currentScreen by remember { mutableStateOf(if (forceAccount) Screen.ACCOUNT else Screen.TERMINAL) }
    var dnsLogMessage by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }
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
            val urls = listOf("$serverUrl/api/ping", "$serverUrl/api/auth/login", "$serverUrl/api/vpn/test-key") // Multiple endpoints to force routing
            repeat(3) { // Triple-Burst to ensure LB wakes up
                urls.forEach { endpoint ->
                    try {
                        val url = java.net.URL(endpoint)
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 30000 // 30s allowance for cold start
                        conn.readTimeout = 30000
                        conn.responseCode
                    } catch (e: Exception) {}
                }
                delay(1000)
            }
            TrafficEvent.log("CORE >> PRE_WARM_SIGNAL_BURST_COMPLETE")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                Screen.TERMINAL -> IgyDashboard(isDarkMode, isPremium, currentScreen,
                    onOpenHub = { 
                        context.startActivity(Intent(context, SelectionHubActivity::class.java))
                    },
                    onOpenAccount = { currentScreen = Screen.ACCOUNT },
                    onOpenSettings = { currentScreen = Screen.SETTINGS },
                    onOpenServers = { currentScreen = Screen.SERVERS },
                    onShowLogs = { showLogs = true }
                )
                Screen.ACCOUNT -> IgyAccountScreen(isDarkMode, onBack = { currentScreen = Screen.TERMINAL })
                Screen.SERVERS -> IgyServerSelectionScreen(isDarkMode, isPremium, onBack = { currentScreen = Screen.TERMINAL })
                Screen.SETTINGS -> IgySettingsScreen(isDarkMode, isPremium, onThemeChange, 
                    onBack = { currentScreen = Screen.TERMINAL }, 
                    onOpenAutoStartPicker = { currentScreen = Screen.AUTO_START_PICKER },
                    onOpenAccount = { currentScreen = Screen.ACCOUNT }
                )
                Screen.AUTO_START_PICKER -> IgyAutoStartPickerScreen(isDarkMode, onBack = { currentScreen = Screen.SETTINGS })
            }
        }

        // Animated Log Pop-up
        AnimatedVisibility(
            visible = showLogs,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showLogs = false }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(0.95f).clickable(enabled = false) {},
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)),
                    border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
                ) {
                    TerminalLog(isDarkMode, onClose = { showLogs = false })
                }
            }
        }
    }
}

@Composable
fun IgySettingsScreen(isDarkMode: Boolean, isPremium: Boolean, onThemeChange: (Boolean) -> Unit, onBack: () -> Unit, onOpenAutoStartPicker: () -> Unit, onOpenAccount: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val currentVersion = packageInfo.versionName ?: "1.0"
    var updateStatus by remember { mutableStateOf("V$currentVersion (LATEST)") }
    var isChecking by remember { mutableStateOf(false) }

    IgyBackground(isDarkMode) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            Text("ENGINE SETTINGS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))

            // Permissions
            IgySettingsHeader("SYSTEM PERMISSIONS")
            
            val isVpnPrepared = android.net.VpnService.prepare(context) == null
            IgyPermissionItem("VPN Service Access", isVpnPrepared) {
                val intent = android.net.VpnService.prepare(context)
                if (intent != null) context.startActivity(intent)
            }

            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else true
            IgyPermissionItem("Battery Optimization", isIgnoringBattery) {
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
            }

            Spacer(modifier = Modifier.height(16.dp))
            IgySettingsHeader("NETWORK CONTROL")
            
            var localBypass by remember { mutableStateOf(IgyPreferences.getLocalBypass(context)) }
            IgySettingsToggle("Local Network Access", localBypass) {
                localBypass = it
                IgyPreferences.setLocalBypass(context, it)
            }

            var autoStartTrigger by remember { mutableStateOf(IgyPreferences.isAutoStartTriggerEnabled(context)) }
            IgySettingsToggle("Auto-Connect VPN", autoStartTrigger) { enabled ->
                if (enabled && !isPremium) {
                    onOpenAccount()
                } else {
                    autoStartTrigger = enabled
                    IgyPreferences.setAutoStartTriggerEnabled(context, enabled)
                }
            }
            
            if (autoStartTrigger) {
                TactileIgyButton("Configure Auto-Apps", isDarkMode = isDarkMode, onClick = onOpenAutoStartPicker, color = Color.White.copy(alpha = 0.1f))
            }

            Spacer(modifier = Modifier.height(16.dp))
            IgySettingsHeader("SOFTWARE")
            Text("Version: $currentVersion", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            Text("Status: $updateStatus", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
            
            Spacer(modifier = Modifier.height(12.dp))
            TactileIgyButton("CHECK FOR UPDATES", isDarkMode = isDarkMode, isLoading = isChecking, onClick = {
                scope.launch {
                    isChecking = true
                    val latestVersion = checkForGithubUpdate(currentVersion)
                    isChecking = false
                    if (latestVersion != null) {
                        updateStatus = "Update Found: V$latestVersion"
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Amyat604/Igy-Shield/releases/latest"))
                        context.startActivity(intent)
                    } else {
                        updateStatus = "System Up to Date"
                    }
                }
            })

            Spacer(modifier = Modifier.height(32.dp))
            TactileIgyButton("BACK", onClick = onBack, isDarkMode = isDarkMode, color = Color.White.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun IgySettingsHeader(title: String) {
    Text(title, color = Color(0xFF00BFFF), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
}

@Composable
fun IgySettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 16.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00BFFF)))
    }
}

@Composable
fun IgyPermissionItem(label: String, granted: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, if (granted) Color(0xFF00BFFF).copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF00BFFF) else Color.Red.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(if (granted) "OK" else "FIX", color = if (granted) Color(0xFF00BFFF) else Color.Red, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
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
fun IgyAccountScreen(isDarkMode: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val serverUrl = remember { IgyPreferences.getSyncEndpoint(context) ?: "https://egi-67tg.onrender.com" }
    var authData by remember { mutableStateOf(IgyPreferences.getAuth(context)) }
    val (token, savedUser, isPremium) = authData
    var isAuthenticating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    IgyBackground(isDarkMode) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("VROOM ACCOUNT", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (token.isEmpty()) "Sign in to activate Premium" else "Welcome back, $savedUser",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp
            )
            
            if (isPremium) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(color = Color(0xFF00BFFF).copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFF00BFFF))) {
                    Text("PREMIUM ACTIVE", color = Color(0xFF00BFFF), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            if (token.isEmpty()) {
                IgyTextField(value = username, onValueChange = { username = it }, label = "Username")
                Spacer(modifier = Modifier.height(12.dp))
                IgyTextField(value = password, onValueChange = { password = it }, label = "Password", isPassword = true)
                Spacer(modifier = Modifier.height(24.dp))
                
                TactileIgyButton("SIGN IN", isLoading = isAuthenticating, onClick = {
                    scope.launch {
                        isAuthenticating = true
                        val result = performAuth(serverUrl, username.trim(), password, false)
                        if (result != null) {
                            IgyPreferences.saveAuth(context, result.token, result.username, result.isPremium, result.expiry)
                            authData = IgyPreferences.getAuth(context)
                            // Pre-sync key
                            val currentId = IgyPreferences.getSelectedNodeId(context)
                            val key = fetchVpnConfig(serverUrl, result.token, currentId)
                            if (key != null) IgyPreferences.saveOutlineKey(context, key)
                        } else {
                            Toast.makeText(context, "Authentication Failed", Toast.LENGTH_SHORT).show()
                        }
                        isAuthenticating = false
                    }
                })
            } else {
                TactileIgyButton("LOGOUT", color = Color.Red.copy(alpha = 0.6f), onClick = {
                    IgyPreferences.clearAuth(context)
                    authData = IgyPreferences.getAuth(context)
                })
            }

            Spacer(modifier = Modifier.height(16.dp))
            TactileIgyButton("GET PREMIUM", color = Color(0xFF00BFFF), onClick = {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/Amyat604"))
                context.startActivity(intent)
            })

            Spacer(modifier = Modifier.height(32.dp))
            Text("Back", color = Color.White, modifier = Modifier.clickable { onBack() }, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun IgyTextField(value: String, onValueChange: (String) -> Unit, label: String, isPassword: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.White.copy(alpha = 0.4f)) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFF00BFFF),
            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
            cursorColor = Color(0xFF00BFFF)
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
fun IgyAutoStartPickerScreen(isDarkMode: Boolean, onBack: () -> Unit) {
    // This will be implemented in AutoStartPicker.kt but we reference it here
    AutoStartPickerScreen(isDarkMode, onBack)
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

private suspend fun measurePing(address: String): String {
    if (address.isEmpty()) return "--"
    return withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val host = java.net.InetAddress.getByName(address)
            if (host.isReachable(3000)) {
                val endTime = System.currentTimeMillis()
                "${endTime - startTime}ms"
            } else {
                "Timed Out"
            }
        } catch (e: Exception) {
            "Error"
        }
    }
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

@Composable
fun IgyDashboard(
    isDarkMode: Boolean,
    isPremium: Boolean,
    currentScreen: Screen,
    onOpenHub: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenServers: () -> Unit,
    onShowLogs: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSecure by TrafficEvent.vpnActive.collectAsState()
    val connState by TrafficEvent.connectionState.collectAsState()
    var isBooting by remember { mutableStateOf(false) }
    var isStealthMode by remember { mutableStateOf(IgyPreferences.isStealthMode(context)) }
    var isVpnTunnelGlobal by remember { mutableStateOf(IgyPreferences.isVpnTunnelGlobal(context)) }
    var selectedNodeName by remember { mutableStateOf(IgyPreferences.getSelectedNodeName(context)) }
    var showManual by remember { mutableStateOf(false) }

    // Update node name when returning to screen
    LaunchedEffect(Unit) {
        while(true) {
            selectedNodeName = IgyPreferences.getSelectedNodeName(context)
            delay(2000)
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

    // Reset booting state when VPN becomes active
    LaunchedEffect(isSecure) {
        if (isSecure) {
            isBooting = false
        }
    }

    IgyBackground(isDarkMode) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))
            
            // 1. TOP LOGO
            Image(
                painter = rememberDrawablePainter(context.packageManager.getApplicationIcon(context.packageName)),
                contentDescription = "Logo",
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(20.dp))
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 2. DASHBOARD TEXT
            Text(
                text = "IGY SHIELD",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 3. MAIN CONNECT BUTTON
            IgyCircularButton(
                isActive = isSecure,
                isBooting = isBooting,
                onClick = {
                    handleExecuteToggle(context, isBooting, isStealthMode, isVpnTunnelGlobal, onOpenHub, vpnLauncher) { isBooting = it }
                }
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // 4. STATUS TEXT
            Text(
                text = when {
                    connState == ConnectionState.CONNECTING -> "Status: CONNECTING..."
                    isBooting -> "Status: INITIALIZING..."
                    isSecure -> "Status: SHIELD ACTIVE"
                    else -> "Status: READY"
                },
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 16.sp,
                fontFamily = FontFamily.SansSerif
            )
            
            Spacer(modifier = Modifier.weight(1.2f))
            
            // 6. BOTTOM NAVIGATION
            Divider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(Color.Black.copy(alpha = 0.2f)),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavItem("Settings", Icons.Default.Settings, currentScreen == Screen.SETTINGS) { onOpenSettings() }
                BottomNavItem("Servers", Icons.Default.Language, currentScreen == Screen.SERVERS) { onOpenServers() }
                BottomNavItem("Account", Icons.Default.Person, currentScreen == Screen.ACCOUNT) { onOpenAccount() }
            }
        }

        // Quick shortcut for Focus Hub
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            Row(modifier = Modifier.padding(top = 40.dp, end = 16.dp)) {
                IconButton(onClick = { showManual = true }) {
                    Icon(Icons.Default.Help, contentDescription = "Manual", tint = Color.White.copy(alpha = 0.3f))
                }
                IconButton(onClick = onOpenHub) {
                    Icon(Icons.Default.FilterCenterFocus, contentDescription = "Focus Mode", tint = Color.White.copy(alpha = 0.3f))
                }
            }
        }
        
        if (showManual) { TacticalManual(onDismiss = { showManual = false }) }
    }
}

@Composable
fun IgyServerSelectionScreen(isDarkMode: Boolean, isPremium: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverUrl = remember { IgyPreferences.getSyncEndpoint(context) ?: "https://egi-67tg.onrender.com" }
    val authData = remember { IgyPreferences.getAuth(context) }
    val (token, _, _) = authData
    
    var regions by remember { mutableStateOf(listOf<JSONObject>()) }
    var selectedNodeId by remember { mutableStateOf(IgyPreferences.getSelectedNodeId(context)) }
    var isLoading by remember { mutableStateOf(false) }
    var pings by remember { mutableStateOf(mapOf<Int, String>()) }

    LaunchedEffect(Unit) {
        if (token.isNotEmpty() && isPremium) {
            isLoading = true
            regions = fetchRegions(serverUrl, token)
            isLoading = false
        }
    }

    LaunchedEffect(regions, serverUrl) {
        // Ping regions
        regions.forEach { region ->
            val id = region.getInt("id")
            val address = region.optString("address", "")
            if (address.isNotEmpty()) {
                scope.launch {
                    val result = measurePing(address)
                    pings = pings + (id to result)
                }
            }
        }
        // Ping standard gateway
        scope.launch {
            val host = try { java.net.URL(serverUrl).host } catch (e: Exception) { "" }
            if (host.isNotEmpty()) {
                val result = measurePing(host)
                pings = pings + (-1 to result)
            }
        }
    }

    IgyBackground(isDarkMode) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            Text("SELECT VROOM NODE", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))

            if (token.isEmpty()) {
                Text("Login to access global nodes", color = Color.White.copy(alpha = 0.6f))
                Spacer(modifier = Modifier.height(16.dp))
            } else if (!isPremium) {
                Text("Premium Required for Region Selection", color = Color.Red.copy(alpha = 0.8f))
                Spacer(modifier = Modifier.height(16.dp))
            } else if (isLoading) {
                CircularProgressIndicator(color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        ServerItem("Standard Gateway", -1, selectedNodeId == -1, pings[-1]) {
                            IgyPreferences.setSelectedNodeId(context, -1)
                            IgyPreferences.setSelectedNodeName(context, "Standard Gateway")
                            selectedNodeId = -1
                        }
                    }
                    items(regions) { region ->
                        val id = region.getInt("id")
                        val name = region.getString("regionName")
                        ServerItem(name, id, selectedNodeId == id, pings[id]) {
                            IgyPreferences.setSelectedNodeId(context, id)
                            IgyPreferences.setSelectedNodeName(context, name)
                            selectedNodeId = id
                            
                            // Pre-sync key for the selected node
                            scope.launch {
                                val key = fetchVpnConfig(serverUrl, token, id)
                                if (key != null) IgyPreferences.saveOutlineKey(context, key)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            TactileIgyButton(text = "BACK", onClick = onBack, isDarkMode = isDarkMode, color = Color.White.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun ServerItem(name: String, id: Int, isSelected: Boolean, ping: String? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, if (isSelected) Color(0xFF00BFFF) else Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(name, color = Color.White, fontSize = 16.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                if (ping != null) {
                    Text(ping, color = if (ping.contains("ms")) Color(0xFF00FF00) else Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                }
            }
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color(0xFF00BFFF))
            }
        }
    }
}

@Composable
fun BottomNavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
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
    val vroomBlack = if (isDarkMode) Color.White else Color.Black
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White

    Column(modifier = Modifier.fillMaxWidth()) {
        TactileIgyButton(
            text = "Install Smart Button (One-Tap)",
            isDarkMode = isDarkMode,
            color = vroomBlack.copy(alpha = 0.1f),
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val statusBarManager = context.getSystemService(android.app.StatusBarManager::class.java)
                    val componentName = android.content.ComponentName(context, IgyTileService::class.java)
                    val icon = android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_shield_status)
                    statusBarManager.requestAddTileService(
                        componentName,
                        "Igy Engine",
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
                border = BorderStroke(1.dp, vroomBlack.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("MANUAL INSTALLATION GUIDE", color = vroomBlack, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(16.dp))
                    TileInstallationAnimation(isDarkMode)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("1. Swipe down twice from top status bar.\n2. Click the Pencil (Edit) icon.\n3. Find 'Igy Engine' and drag it up to your active buttons.", 
                        color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun TileInstallationAnimation(isDarkMode: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "TileAnim")
    val vroomBlack = if (isDarkMode) Color.White else Color.Black
    
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
            imageVector = Icons.Default.Speed,
            contentDescription = null,
            tint = vroomBlack.copy(alpha = iconAlpha),
            modifier = Modifier.size(24.dp).offset(y = (fingerY + 10).dp)
        )

        // Hand/Finger
        Icon(
            imageVector = Icons.Default.TouchApp,
            contentDescription = null,
            tint = if (isDarkMode) Color.White else Color.Black,
            modifier = Modifier.size(32.dp).offset(y = fingerY.dp)
        )
    }
}

@Composable
fun TacticalManual(onDismiss: () -> Unit) {
    val vroomBlack = if (MaterialTheme.colorScheme.surface == Color(0xFF1A1A1A)) Color.White else Color.Black
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        title = { Text("VROOM >> QUICK_START_GUIDE", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 16.sp) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    ManualSection("1. HOW TO IGNITE", "EN: Simply click Ignite to start. If it's your first time, click Account to sign in.\nMM: Ignite ကိုနှိပ်ပြီး စသုံးနိုင်ပါပြီ။ အကောင့်မရှိသေးရင် Account ထဲမှာ အကောင့်ဝင်ပါ။")
                    ManualSection("2. THREE MODES EXPLAINED", "• [VPN]: Encrypts ALL device traffic. Best for full privacy.\n• [VPN Focus]: ONLY encrypts traffic of apps you pick. Best for speed & target apps.\n• [Normal Focus]: ACCELERATE your VIP apps by blocking all background data thieves for maximum speed.\nMM: ဖုန်းတစ်ခုလုံးသုံးမလား (VPN)၊ app တစ်ခုချင်းသုံးမလား (VPN Focus) (သို့မဟုတ်) အင်တာနက်မြန်အောင် လုပ်မလား (Normal Focus) စိတ်ကြိုက်ရွေးပါ။")
                    ManualSection("3. FOR BEST PERFORMANCE", "EN: Go to Settings -> Enable 'Always-on VPN' in Android settings to prevent disconnects.\nMM: ဖုန်း Settings ထဲမှာ Always-on VPN ကို ဖွင့်ထားပေးရင် ပိုမြန်ပြီး ပိုတည်ငြိမ်ပါတယ်။")
                    ManualSection("4. NEED HELP?", "EN: If the engine stops working, click the refresh button on the main screen.\nMM: အင်ဂျင်မရတော့ရင် refresh ခလုတ်ကို နှိပ်ပေးပါ။")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK", color = Color.White, fontFamily = FontFamily.Monospace) }
        }
    )
}

@Composable
fun ManualSection(title: String, desc: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("> $title", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(desc, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Divider(color = Color.DarkGray, thickness = 0.5.dp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun TerminalLog(isDarkMode: Boolean, onClose: () -> Unit) {
    val events = TrafficEvent.events.collectAsState(initial = "INITIALIZING...")
    val logHistory = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val vroomBlack = if (isDarkMode) Color.White else Color.Black
    
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
            Text("Engine Log", color = deepGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Row {
                Text("Clear", color = vroomBlack, fontSize = 12.sp, modifier = Modifier.clickable { TrafficEvent.clearLogs() }.padding(horizontal = 16.dp), fontFamily = FontFamily.Monospace)
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
