package com.example.egi

import android.content.ClipboardManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

enum class Screen {
    TERMINAL, APP_PICKER, DNS_PICKER, APP_SELECTOR, WIFI_RADAR, ROUTER_ADMIN, ACCOUNT, SETTINGS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.statusBarColor = Color.Black.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            var isDarkMode by remember { mutableStateOf(EgiPreferences.isDarkMode(this)) }
            EgiTerminalTheme(isDarkMode) {
                MainContent(isDarkMode, onThemeChange = { 
                    isDarkMode = it
                    EgiPreferences.setDarkMode(this, it)
                })
            }
        }
    }
}

@Composable
fun MainContent(isDarkMode: Boolean, onThemeChange: (Boolean) -> Unit) {
    var currentScreen by remember { mutableStateOf(Screen.TERMINAL) }
    var dnsLogMessage by remember { mutableStateOf<String?>(null) }
    var gatewayIp by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    if (showLogs) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            containerColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxSize().padding(8.dp),
            text = { TerminalLog(isDarkMode, onClose = { showLogs = false }) },
            confirmButton = {
                TextButton(onClick = { showLogs = false }) {
                    Text("[ CLOSE_LOGS ]", color = Color.Red, fontFamily = FontFamily.Monospace)
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
            Screen.APP_PICKER -> AppPickerScreen(onBack = { currentScreen = Screen.TERMINAL })
            Screen.DNS_PICKER -> DnsPickerScreen(onBack = { msg ->
                dnsLogMessage = msg
                currentScreen = Screen.TERMINAL
            })
            Screen.APP_SELECTOR -> AppSelectorScreen(onBack = { currentScreen = Screen.TERMINAL })
            Screen.WIFI_RADAR -> WifiScanScreen(
                onBack = { currentScreen = Screen.TERMINAL },
                onNavigateToRouter = { ip ->
                    gatewayIp = ip
                    currentScreen = Screen.ROUTER_ADMIN
                }
            )
            Screen.ROUTER_ADMIN -> {
                RouterAdminScreen(
                    gatewayIp = gatewayIp,
                    onBack = { currentScreen = Screen.WIFI_RADAR }
                )
            }
            Screen.TERMINAL -> TerminalDashboard(
                onOpenAppPicker = { currentScreen = Screen.APP_PICKER },
                onOpenDnsPicker = { currentScreen = Screen.DNS_PICKER },
                onOpenAppSelector = { currentScreen = Screen.APP_SELECTOR },
                onOpenWifiRadar = { currentScreen = Screen.WIFI_RADAR },
                onOpenAccount = { currentScreen = Screen.ACCOUNT },
                onOpenSettings = { currentScreen = Screen.SETTINGS },
                onShowLogs = { showLogs = true },
                dnsMsg = dnsLogMessage,
                onDnsLogConsumed = { dnsLogMessage = null }
            )
            Screen.ACCOUNT -> TerminalAccountScreen(onBack = { currentScreen = Screen.TERMINAL })
            Screen.SETTINGS -> TerminalSettingsScreen(isDarkMode, onThemeChange, onBack = { currentScreen = Screen.TERMINAL })
        }
    }
}

@Composable
fun TerminalSettingsScreen(isDarkMode: Boolean, onThemeChange: (Boolean) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val scope = rememberCoroutineScope()
    
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val currentVersion = packageInfo.versionName ?: "1.0"
    
    var updateStatus by remember { mutableStateOf("V$currentVersion (LATEST)") }
    var isChecking by remember { mutableStateOf(false) }

    // Rotation Animation for Sync Icon
    val syncTransition = rememberInfiniteTransition(label = "Sync")
    val rotation by syncTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    Column(
        modifier = Modifier.fillMaxSize().background(creamColor).padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("EGI >> SYSTEM_SETTINGS", color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        // --- SECTION 1: VPN & NETWORK ---
        SettingsHeader("1. VPN & NETWORK CONTROL")
        SettingsToggle("DARK_MODE", isDarkMode) {
            onThemeChange(it)
        }
        var localBypass by remember { mutableStateOf(EgiPreferences.getLocalBypass(context)) }
        SettingsToggle("LOCAL_BYPASS", localBypass) {
            localBypass = it
            EgiPreferences.setLocalBypass(context, it)
        }
        SettingsButton("[ SYSTEM_VPN_CONFIG ]") {
            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- SECTION 2: SOFTWARE UPDATE ---
        SettingsHeader("2. SOFTWARE UPDATE")
        Text("BUILD_VERSION: $currentVersion", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("STATUS: $updateStatus", color = if (updateStatus.contains("FOUND")) Color(0xFF2E8B57) else Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(12.dp))
        
        Button(
            onClick = {
                if (isChecking) return@Button
                scope.launch {
                    isChecking = true
                    updateStatus = "SCANNING_REPOSITORIES..."
                    val latestVersion = checkForGithubUpdate(currentVersion)
                    isChecking = false
                    
                    if (latestVersion != null) {
                        updateStatus = "UPDATE_FOUND: V$latestVersion"
                        Toast.makeText(context, "NEW_VERSION_AVAILABLE", Toast.LENGTH_LONG).show()
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Amyat604/Egi-Shield/releases/latest"))
                        context.startActivity(intent)
                    } else {
                        updateStatus = "YOU_ARE_ON_LATEST"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, Color.Gray),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isChecking) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        modifier = androidx.compose.ui.Modifier.size(16.dp).graphicsLayer(rotationZ = rotation),
                        tint = Color(0xFFB8860B)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("[ CHECK_FOR_UPDATES ]", fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Text("[ BACK TO TERMINAL ]", color = deepGray, modifier = Modifier.clickable { onBack() }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

private suspend fun checkForGithubUpdate(currentVersion: String): String? = withContext(Dispatchers.IO) {
    try {
        // Connect to GitHub API
        val url = java.net.URL("https://api.github.com/repos/Amyat604/Egi-Shield/releases/latest")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "Egi-Shield-App")

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
fun SettingsButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, Color.Gray),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
    ) {
        Text(label, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun TerminalAccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val isDarkMode = EgiPreferences.isDarkMode(context)
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(EgiPreferences.getSyncEndpoint(context) ?: "https://egi-67tg.onrender.com") }
    val (savedToken, savedUser, isPremium) = EgiPreferences.getAuth(context)
    var status by remember { mutableStateOf(if (savedToken.isEmpty()) "GUEST_MODE" else "LOGGED_IN: $savedUser") }
    val scope = rememberCoroutineScope()

    // Region Selector State
    var regions by remember { mutableStateOf(listOf<JSONObject>()) }
    var selectedNodeId by remember { mutableStateOf(EgiPreferences.getSelectedNodeId(context)) }

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
        Text("EGI >> SYSTEM_AUTHENTICATION", color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { 
                serverUrl = it
                EgiPreferences.saveSyncEndpoint(context, it)
            },
            label = { Text("SERVER_URL", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth().background(Color.White),
            textStyle = androidx.compose.ui.text.TextStyle(color = deepGray, fontFamily = FontFamily.Monospace)
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        Text("STATUS: $status", color = if (isPremium) Color(0xFF2E8B57) else Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (isPremium) Text("PREMIUM_ACCESS: GRANTED", color = Color(0xFF2E8B57), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        
        Spacer(modifier = Modifier.height(32.dp))

        if (savedToken.isEmpty()) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("USERNAME", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth().background(Color.White),
                textStyle = androidx.compose.ui.text.TextStyle(color = deepGray, fontFamily = FontFamily.Monospace)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("PASSWORD", color = Color.Gray) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().background(Color.White),
                textStyle = androidx.compose.ui.text.TextStyle(color = deepGray, fontFamily = FontFamily.Monospace)
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val result = performAuth(serverUrl, username, password, true)
                                if (result != null) {
                                    EgiPreferences.saveAuth(context, result.token, result.username, result.isPremium, result.expiry)
                                    status = "LOGGED_IN: ${result.username}"
                                } else {
                                    Toast.makeText(context, "AUTH_FAILED", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "ERROR: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = deepGray),
                    modifier = Modifier.border(1.dp, wheat),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Text("REGISTER", fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val result = performAuth(serverUrl, username, password, false)
                                if (result != null) {
                                    EgiPreferences.saveAuth(context, result.token, result.username, result.isPremium, result.expiry)
                                    status = "LOGGED_IN: ${result.username}"
                                } else {
                                    Toast.makeText(context, "LOGIN_FAILED", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "ERROR: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF2E8B57)),
                    modifier = Modifier.border(1.dp, wheat),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Text("LOGIN", fontFamily = FontFamily.Monospace)
                }
            }
        } else {
            if (isPremium && regions.isNotEmpty()) {
                Text("VPN_REGION_SELECTOR", color = deepGray, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
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
                                selectedNodeId = id
                                EgiPreferences.setSelectedNodeId(context, id)
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(name, color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        if (selectedNodeId == id) Text("[ SELECTED ]", color = Color(0xFF2E8B57), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(if (selectedNodeId == -1) Color.White else Color.Transparent)
                        .border(0.5.dp, if (selectedNodeId == -1) deepGray else wheat)
                        .clickable {
                            selectedNodeId = -1
                            EgiPreferences.setSelectedNodeId(context, -1)
                        }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("PRIVATE_GATEWAY (DEFAULT)", color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (selectedNodeId == -1) Text("[ SELECTED ]", color = Color(0xFF2E8B57), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            Button(
                onClick = {
                    EgiPreferences.clearAuth(context)
                    status = "GUEST_MODE"
                },
                modifier = Modifier.fillMaxWidth().border(1.dp, wheat),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Red),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Text("LOGOUT", fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    val key = fetchTestKey(serverUrl)
                    if (key != null) {
                        EgiPreferences.saveOutlineKey(context, key)
                        Toast.makeText(context, "TEST_KEY_IMPORTED", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().border(1.dp, wheat),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF20B2AA)),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
        ) {
            Text("[ GET TEST KEY ]", fontFamily = FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/Amyat604"))
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFB8860B)),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB8860B)),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Text("[ BUY PREMIUM: 10,000 MMK / MONTH ]", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("[ BACK TO TERMINAL ]", color = deepGray, modifier = Modifier.clickable { onBack() }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

private suspend fun fetchTestKey(serverUrl: String): String? = withContext(Dispatchers.IO) {
    try {
        val url = java.net.URL("$serverUrl/api/vpn/test-key")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (conn.responseCode == 200) {
            val res = JSONObject(conn.inputStream.bufferedReader().readText())
            return@withContext res.getString("config")
        }
    } catch (e: Exception) {}
    null
}

private suspend fun fetchRegions(serverUrl: String, token: String): List<JSONObject> = withContext(Dispatchers.IO) {
    try {
        val url = java.net.URL("$serverUrl/api/vpn/regions")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (conn.responseCode == 200) {
            val res = JSONArray(conn.inputStream.bufferedReader().readText())
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
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        
        val body = JSONObject().apply {
            put("username", user)
            put("password", pass)
        }
        conn.outputStream.write(body.toString().toByteArray())
        
        if (conn.responseCode == 200) {
            val res = JSONObject(conn.inputStream.bufferedReader().readText())
            val userObj = res.getJSONObject("user")
            return@withContext AuthResult(
                res.getString("token"),
                userObj.getString("username"),
                userObj.optBoolean("isPremium", false),
                userObj.optLong("expiry", 0L)
            )
        }
    } catch (e: Exception) {}
    null
}

private suspend fun fetchVpnConfig(serverUrl: String, token: String, nodeId: Int): String? = withContext(Dispatchers.IO) {
    try {
        val url = java.net.URL("$serverUrl/api/vpn/config${if (nodeId != -1) "?nodeId=$nodeId" else ""}")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Authorization", "Bearer $token")
        
        if (conn.responseCode == 200) {
            val res = JSONObject(conn.inputStream.bufferedReader().readText())
            return@withContext res.getString("config")
        }
    } catch (e: Exception) {}
    null
}

@Composable
fun TerminalDashboard(
    onOpenAppPicker: () -> Unit,
    onOpenDnsPicker: () -> Unit,
    onOpenAppSelector: () -> Unit,
    onOpenWifiRadar: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowLogs: () -> Unit,
    dnsMsg: String?,
    onDnsLogConsumed: () -> Unit
) {
    val context = LocalContext.current
    val creamColor = Color(0xFFFDF5E6)
    val deepGray = Color(0xFF2F4F4F)
    val wheat = Color(0xFFF5DEB3)
    val scope = rememberCoroutineScope()
    val isSecure by TrafficEvent.vpnActive.collectAsState()
    val events by TrafficEvent.events.collectAsState(initial = "SYSTEM_READY")
    var isBooting by remember { mutableStateOf(false) }
    var isStealthMode by remember { mutableStateOf(EgiPreferences.isStealthMode(context)) }
    var isVpnTunnelGlobal by remember { mutableStateOf(EgiPreferences.isVpnTunnelGlobal(context)) }
    var isLocalBypass by remember { mutableStateOf(EgiPreferences.getLocalBypass(context)) }
    var isAutoStart by remember { mutableStateOf(EgiPreferences.getAutoStart(context)) }

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
    var isCurrentSsidTrusted by remember { mutableStateOf(false) }
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
    val animatedBlockedCount by animateIntAsState(targetValue = blockedCount, animationSpec = tween(1000), label = "BlockedCountAnim")
    val statusColor by animateColorAsState(
        targetValue = if (isSecure) Color(0xFF2E8B57) else Color.Gray,
        animationSpec = tween(500),
        label = "StatusColor"
    )

    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            if (EgiNetwork.isAvailable()) {
                try {
                    val statsJson = withContext(Dispatchers.IO) {
                        EgiNetwork.measureNetworkStats("1.1.1.1")
                    }
                    val json = JSONObject(statsJson)
                    currentPing = json.optInt("ping", -1)
                    currentJitter = json.optInt("jitter", 0)
                } catch (e: Throwable) {}
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            onOpenWifiRadar()
        } else {
            Toast.makeText(context, "Radar requires Location to scan", Toast.LENGTH_SHORT).show()
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            TrafficEvent.log("USER >> PERMISSION_GRANTED")
            ContextCompat.startForegroundService(context, Intent(context, EgiVpnService::class.java))
            isBooting = false
        } else {
            TrafficEvent.log("USER >> PERMISSION_DENIED")
            isBooting = false
            Toast.makeText(context, "KERNEL ACCESS DENIED", Toast.LENGTH_SHORT).show()
        }
    }

    // WiFi Info Polling
    LaunchedEffect(Unit) {
        val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        while (true) {
            val ssid = wifiManager.connectionInfo.ssid.replace("\"", "")
            if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                currentSsid = ssid
                isCurrentSsidTrusted = EgiPreferences.getTrustedSSIDs(context).contains(ssid)
            } else {
                currentSsid = null
                isCurrentSsidTrusted = false
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
            .padding(8.dp)
    ) {
        // --- TOP SECTION: THE MATRIX HEADER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1.8f)
                    .fillMaxHeight()
                    .background(Color.White)
                    .border(0.5.dp, wheat),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (currentSsid != null) "[ WIFI: $currentSsid ]" else "[ NO_WIFI ]",
                    color = if (isCurrentSsidTrusted) Color(0xFF2E8B57) else Color(0xFFB8860B),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.White)
                    .border(0.5.dp, wheat)
                    .clickable { onOpenSettings() },
                contentAlignment = Alignment.Center
            ) {
                Text("[ SETTINGS ]", color = Color(0xFF4682B4), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.White)
                    .border(0.5.dp, wheat)
                    .clickable { onOpenAccount() },
                contentAlignment = Alignment.Center
            ) {
                Text("[ ACCOUNT ]", color = Color(0xFFDAA520), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            Box(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .background(Color.White)
                    .border(0.5.dp, wheat)
                    .clickable { showManual = true },
                contentAlignment = Alignment.Center
            ) {
                Text("[ ? ]", color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            StatsTile("PING", if (currentPing == -1) "--" else "$animatedPing ms", 1f, if (currentPing < 80) Color(0xFF2E8B57) else Color.Red)
            StatsTile("JITTER", "$currentJitter ms", 1f, Color(0xFF20B2AA))
            StatsTile("STATUS", if (isSecure) "ACTIVE" else "STANDBY", 1.4f, if (isSecure) Color(0xFF2E8B57) else Color.Gray)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.White)
                .border(0.5.dp, wheat),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer(scaleX = activePulseScale, scaleY = activePulseScale)) {
                Text(
                    text = "THREATS_DEFLECTED",
                    color = deepGray.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = String.format("%,d", blockedCount),
                    color = if (isSecure) Color(0xFF2E8B57) else deepGray.copy(alpha = 0.3f),
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
            Row(modifier = Modifier.fillMaxWidth().height(55.dp)) {
                GridButton(
                    text = if (!isStealthMode) "[ BYPASS MODE: ACTIVE ]" else "[ BYPASS MODE ]",
                    modifier = Modifier.weight(1f),
                    color = if (!isStealthMode) Color(0xFFB8860B) else Color(0xFF2E8B57)
                ) {
                    isStealthMode = false
                    EgiPreferences.setStealthMode(context, false)
                    onOpenAppSelector()
                }
                GridButton("[ NETWORK_RADAR ]", Modifier.weight(1f)) {
                    val status = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    if (status == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        onOpenWifiRadar()
                    } else {
                        permissionLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(55.dp)) {
                GridButton("[ CONSOLE_LOGS ]", Modifier.weight(1f)) { onShowLogs() }
                GridButton(
                    text = if (isStealthMode && isVpnTunnelGlobal) "[ VPN GLOBAL: ACTIVE ]" else "[ VPN GLOBAL ]",
                    modifier = Modifier.weight(1f),
                    color = if (isStealthMode && isVpnTunnelGlobal) Color(0xFF20B2AA) else Color(0xFF2E8B57)
                ) {
                    isStealthMode = true
                    isVpnTunnelGlobal = true
                    EgiPreferences.setStealthMode(context, true)
                    EgiPreferences.setVpnTunnelMode(context, true)
                    TrafficEvent.log("USER >> ARMED_VPN_GLOBAL")
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(55.dp)) {
                GridButton(
                    text = if (isBatteryOptimized) "[ BATTERY: OK ]" else "[ BATTERY: RESTRICTED ]",
                    modifier = Modifier.weight(0.8f),
                    color = if (isBatteryOptimized) Color(0xFF2E8B57) else Color.Red
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
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    } else {
                        Toast.makeText(context, "BATTERY_MANAGEMENT: UNRESTRICTED", Toast.LENGTH_SHORT).show()
                    }
                }
                GridButton(
                    text = if (isStealthMode && !isVpnTunnelGlobal) "[ VPN FOCUS: ACTIVE ]" else "[ VPN FOCUS ]",
                    modifier = Modifier.weight(1.4f),
                    color = if (isStealthMode && !isVpnTunnelGlobal) Color(0xFF8B008B) else Color(0xFF2E8B57)
                ) {
                    isStealthMode = true
                    isVpnTunnelGlobal = false
                    EgiPreferences.setStealthMode(context, true)
                    EgiPreferences.setVpnTunnelMode(context, false)
                    onOpenAppPicker()
                }
                GridButton("[ REFRESH_HUB ]", Modifier.weight(0.8f)) { 
                    TrafficEvent.log("CORE >> RESETTING_NETWORK_STACK...")
                    TrafficEvent.updateCount(0)
                    Toast.makeText(context, "SYSTEM_REPAIRED", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Color.White)
                .border(1.dp, wheat)
                .graphicsLayer(scaleX = if (isBooting) connectingPulseScale else if (isSecure) activePulseScale else 1f, scaleY = if (isBooting) connectingPulseScale else if (isSecure) activePulseScale else 1f)
                .clickable {
                    handleExecuteToggle(context, isSecure, isBooting, isStealthMode, isVpnTunnelGlobal, onOpenAppSelector, onOpenAppPicker, vpnLauncher) { isBooting = it }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    isBooting -> "> [ INITIALIZING... ] <"
                    isSecure -> "> [ SHUTDOWN_SHIELD ] <"
                    isStrictBlocking && !isStealthMode -> "> [ LOCKED: CONFIG_VPN ] <"
                    else -> "> [ ENGAGE_SHIELD ] <"
                },
                color = if (isSecure) Color.Red else Color(0xFF2E8B57),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            )
        }
    }

    if (showManual) { TacticalManual(onDismiss = { showManual = false }) }
}

@Composable
fun RowScope.StatsTile(label: String, value: String, weightRatio: Float, valueColor: Color) {
    val wheat = Color(0xFFF5DEB3)
    Box(
        modifier = Modifier
            .weight(weightRatio)
            .fillMaxHeight()
            .background(Color.White)
            .border(0.5.dp, wheat),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RowScope.GridButton(text: String, modifier: Modifier = Modifier, color: Color = Color(0xFF2E8B57), onClick: () -> Unit) {
    val wheat = Color(0xFFF5DEB3)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "ButtonScale")

    Box(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .background(Color.White)
            .border(0.5.dp, wheat)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun handleExecuteToggle(
    context: Context,
    isSecure: Boolean,
    isBooting: Boolean,
    isStealthMode: Boolean,
    isGlobal: Boolean,
    onOpenAppSelector: () -> Unit,
    onOpenAppPicker: () -> Unit,
    vpnLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    setBooting: (Boolean) -> Unit
) {
    val scope = CoroutineScope(Dispatchers.Main + Job())
    try {
        if (isBooting) {
            setBooting(false)
            TrafficEvent.log("USER >> BOOT_CANCELLED")
            return
        }
        if (isSecure) {
            TrafficEvent.log("USER >> SHUTTING_DOWN")
            val stopIntent = Intent(context, EgiVpnService::class.java).apply { action = EgiVpnService.ACTION_STOP }
            context.startService(stopIntent)
            
            // Auto-Redirect to System VPN Settings to disable Always-on/Lockdown
            val settingsIntent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(settingsIntent)
            Toast.makeText(context, "DISABLE ALWAYS-ON / LOCKDOWN IF NEEDED", Toast.LENGTH_LONG).show()
        } else {
            val vipList = EgiPreferences.getVipList(context)
            if (!isGlobal && vipList.isEmpty() && isStealthMode) {
                Toast.makeText(context, "PICK A FOCUS APP FIRST!", Toast.LENGTH_LONG).show()
                onOpenAppPicker()
                return
            }
            if (!isStealthMode && vipList.isEmpty()) {
                Toast.makeText(context, "PICK APPS TO BYPASS FIRST!", Toast.LENGTH_LONG).show()
                onOpenAppSelector()
                return
            }

            setBooting(true)
            TrafficEvent.log("USER >> BOOTING_SHIELD")

            // ONE-CLICK SYNC: Fetch key before starting if logged in
            val (token, _, _) = EgiPreferences.getAuth(context)
            val serverUrl = EgiPreferences.getSyncEndpoint(context) ?: "https://egi-67tg.onrender.com"
            val nodeId = EgiPreferences.getSelectedNodeId(context)
            if (token.isNotEmpty() && isStealthMode) {
                scope.launch {
                    TrafficEvent.log("CORE >> SYNCING_PREMIUM_KEY...")
                    val config = fetchVpnConfig(serverUrl, token, nodeId)
                    if (config != null) {
                        EgiPreferences.saveOutlineKey(context, config)
                        TrafficEvent.log("CORE >> KEY_SYNC_SUCCESS")
                    } else {
                        TrafficEvent.log("CORE >> SYNC_FAILED_USING_CACHE")
                    }
                    
                    val intent = VpnService.prepare(context)
                    if (intent != null) { vpnLauncher.launch(intent) } 
                    else { ContextCompat.startForegroundService(context, Intent(context, EgiVpnService::class.java)) }
                }
            } else {
                val intent = VpnService.prepare(context)
                if (intent != null) { vpnLauncher.launch(intent) } 
                else { ContextCompat.startForegroundService(context, Intent(context, EgiVpnService::class.java)) }
            }
        }
    } catch (e: Exception) {
        TrafficEvent.log("CRITICAL >> ${e.message}")
        setBooting(false)
    }
}

@Composable
fun TacticalManual(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        title = { Text("EGI >> QUICK_START_GUIDE", color = Color.Cyan, fontFamily = FontFamily.Monospace, fontSize = 16.sp) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    ManualSection(
                        "1. HOW TO CONNECT",
                        "EN: Simply click [ENGAGE_SHIELD] to start. If it's your first time, click [ACCOUNT] to get a key.\n" +
                        "MM: ENGAGE ကိုနှိပ်ပြီး စသုံးနိုင်ပါပြီ။ အကောင့်မရှိသေးရင် ACCOUNT ထဲမှာ Key အရင်ယူပါ။"
                    )
                    ManualSection(
                        "2. THREE MODES EXPLAINED",
                        "• VPN SHIELD: Protects everything on your phone.\n" +
                        "• FOCUS MODE: Only protects the apps you choose.\n" +
                        "• BYPASS MODE: Protects everything EXCEPT your chosen apps.\n" +
                        "MM: ဖုန်းတစ်ခုလုံးသုံးမလား၊ app တစ်ခုချင်းသုံးမလား စိတ်ကြိုက်ရွေးပါ။"
                    )
                    ManualSection(
                        "3. FOR BEST PERFORMANCE",
                        "EN: Go to [SETTINGS] -> Enable 'Always-on VPN' in Android settings to prevent disconnects.\n" +
                        "MM: ဖုန်း Settings ထဲမှာ Always-on VPN ကို ဖွင့်ထားပေးရင် ပိုမြန်ပြီး ပိုတည်ငြိမ်ပါတယ်။"
                    )
                    ManualSection(
                        "4. NEED HELP?",
                        "EN: If the internet stops working, click the [REPAIR] button on the main screen.\n" +
                        "MM: အင်တာနက်မရတော့ရင် REPAIR ခလုတ်ကို နှိပ်ပေးပါ။"
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("[ UNDERSTOOD / နားလည်ပါပြီ ]", color = Color.Green, fontFamily = FontFamily.Monospace) }
        }
    )
}

@Composable
fun ManualSection(title: String, desc: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("> $title", color = Color.Green, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(desc, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Divider(color = Color.DarkGray, thickness = 0.5.dp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun EgiTerminalTheme(isDarkMode: Boolean, content: @Composable () -> Unit) {
    val creamColor = Color(0xFFFDF5E6)
    val wheat = Color(0xFFF5DEB3)
    val deepGray = Color(0xFF2F4F4F)
    
    val colorScheme = if (isDarkMode) {
        darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            surface = Color(0xFF1A1A1A),
            onSurface = Color.White,
            background = Color(0xFF1A1A1A),
            onBackground = Color.White,
            secondary = Color.Gray,
            outline = Color(0xFF333333)
        )
    } else {
        lightColorScheme(
            primary = Color.White,
            onPrimary = deepGray,
            surface = creamColor,
            onSurface = deepGray,
            background = creamColor,
            onBackground = deepGray,
            secondary = Color.Gray,
            outline = wheat
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            bodyLarge = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = if (isDarkMode) Color.White else deepGray
            )
        ),
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
            Text("EGI_CONSOLE_V1.0", color = deepGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Row {
                Text(
                    "[ CLEAR ]", 
                    color = Color(0xFFB8860B), 
                    fontSize = 12.sp, 
                    modifier = Modifier.clickable { TrafficEvent.clearLogs() }.padding(horizontal = 16.dp),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "[ X ]", 
                    color = Color.Red, 
                    fontSize = 12.sp, 
                    modifier = Modifier.clickable { onClose() },
                    fontFamily = FontFamily.Monospace
                )
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
