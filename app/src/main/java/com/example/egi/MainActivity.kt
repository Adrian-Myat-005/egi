package com.example.egi

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class Screen {
    TERMINAL, APP_PICKER, DNS_PICKER, APP_SELECTOR, WIFI_RADAR
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.statusBarColor = Color.Black.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            EgiTerminalTheme {
                MainContent()
            }
        }
    }
}

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun PingGraph(history: List<Int>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val maxPing = 200f
        
        // Draw Grid lines
        drawLine(Color.DarkGray, start = androidx.compose.ui.geometry.Offset(0f, height * 0.5f), end = androidx.compose.ui.geometry.Offset(width, height * 0.5f), strokeWidth = 1f)
        
        if (history.size < 2) return@Canvas

        val path = Path()
        val step = width / (history.size - 1)
        
        history.forEachIndexed { index, ping ->
            val x = index * step
            val normalizedPing = (ping.coerceIn(0, maxPing.toInt()) / maxPing)
            val y = height - (normalizedPing * height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        
        drawPath(path, color = Color.Green, style = Stroke(width = 4f))

        // Draw Simulated "Unoptimized" Red Path (Lag Spikes)
        val redPath = Path()
        history.forEachIndexed { index, ping ->
            val x = index * step
            // Simulate a lag spike on the red line
            val simulatedLag = if (index % 5 == 0) (ping + 80).coerceIn(0, maxPing.toInt()) else (ping + 20).coerceIn(0, maxPing.toInt())
            val normalizedPing = (simulatedLag / maxPing)
            val y = height - (normalizedPing * height)
            if (index == 0) redPath.moveTo(x, y) else redPath.lineTo(x, y)
        }
        drawPath(redPath, color = Color.Red.copy(alpha = 0.3f), style = Stroke(width = 2f))
    }
}

@Composable
fun MainContent() {
    var currentScreen by remember { mutableStateOf(Screen.TERMINAL) }
    var dnsLogMessage by remember { mutableStateOf<String?>(null) }

    Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
        when (screen) {
            Screen.APP_PICKER -> AppPickerScreen(onBack = { currentScreen = Screen.TERMINAL })
            Screen.DNS_PICKER -> DnsPickerScreen(onBack = { msg ->
                dnsLogMessage = msg
                currentScreen = Screen.TERMINAL
            })
            Screen.APP_SELECTOR -> AppSelectorScreen(onBack = { currentScreen = Screen.TERMINAL })
            Screen.WIFI_RADAR -> WifiScanScreen(onBack = { currentScreen = Screen.TERMINAL })
            Screen.TERMINAL -> TerminalDashboard(
                onOpenAppPicker = { currentScreen = Screen.APP_PICKER },
                onOpenDnsPicker = { currentScreen = Screen.DNS_PICKER },
                onOpenAppSelector = { currentScreen = Screen.APP_SELECTOR },
                onOpenWifiRadar = { currentScreen = Screen.WIFI_RADAR },
                dnsMsg = dnsLogMessage,
                onDnsLogConsumed = { dnsLogMessage = null }
            )
        }
    }
}

@Composable
fun EgiTerminalTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
            content = content
        )
    }
}

@Composable
fun TerminalDashboard(
    onOpenAppPicker: () -> Unit,
    onOpenDnsPicker: () -> Unit,
    onOpenAppSelector: () -> Unit,
    onOpenWifiRadar: () -> Unit,
    dnsMsg: String?,
    onDnsLogConsumed: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf(listOf("EGI >> System init...", "EGI >> Waiting for uplink...")) }
    var isSecure by remember { mutableStateOf(false) }
    var isBooting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // HUD States
    var selectedServer by remember { mutableStateOf(GameServers.list.first()) }
    var currentPing by remember { mutableStateOf(0) }
    var pingHistory by remember { mutableStateOf(List(20) { 0 }) }
    var currentJitter by remember { mutableStateOf(0) }
    var serverStatus by remember { mutableStateOf("CONNECTING") }
    val blockedCount by TrafficEvent.blockedCount.collectAsState()
    
    // Dropdown State
    var expanded by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            onOpenWifiRadar()
        } else {
            Toast.makeText(context, "Radar requires Location to scan", Toast.LENGTH_SHORT).show()
        }
    }

    // Helper to type a message into logs
    fun addLog(msg: String) {
        if (!msg.contains("INTERNAL")) {
            logs = (logs + msg).takeLast(50)
        }
    }

    suspend fun typeLog(msg: String) {
        var currentText = ""
        logs = (logs + "").takeLast(50) // Ensure we stay within limit even when typing
        val lastIndex = logs.size - 1
        for (char in msg) {
            currentText += char
            val newLogs = logs.toMutableList()
            newLogs[lastIndex] = currentText
            logs = newLogs
            delay(15) // Faster typing
        }
    }

    // Handle DNS change log and restart
    LaunchedEffect(dnsMsg) {
        if (dnsMsg != null) {
            scope.launch {
                typeLog("EGI >> $dnsMsg")
                if (isSecure) {
                    typeLog("EGI >> RESTARTING TUNNEL TO APPLY CONFIG...")
                    val restartIntent = Intent(context, EgiVpnService::class.java).apply {
                        action = EgiVpnService.ACTION_RESTART
                    }
                    context.startService(restartIntent)
                }
                onDnsLogConsumed()
            }
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            context.startService(Intent(context, EgiVpnService::class.java))
            isSecure = true
            isBooting = false
            scope.launch {
                val mode = EgiPreferences.getMode(context)
                val vipCount = EgiPreferences.getVipList(context).size
                if (mode == AppMode.FOCUS) {
                    val target = EgiPreferences.getFocusTarget(context) ?: "UNKNOWN"
                    typeLog("EGI >> MODE: NUCLEAR (TARGET: $target)")
                } else {
                    typeLog("EGI >> MODE: TACTICAL (VIPs: $vipCount APPS)")
                }
                typeLog("EGI >> INITIALIZING BLACK HOLE...")
                typeLog("EGI >> PROTOCOL ACTIVE.")
            }
        } else {
            isBooting = false
            addLog("EGI >> KERNEL ACCESS DENIED.")
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // Real-Time Stats Loop (The HUD)
    LaunchedEffect(selectedServer) {
        while (true) {
            delay(1500)
            if (EgiNetwork.isAvailable()) {
                try {
                    val statsJson = withContext(Dispatchers.IO) {
                        EgiNetwork.measureNetworkStats(selectedServer.ip)
                    }
                    // Parse JSON for HUD
                    val json = JSONObject(statsJson)
                    currentPing = json.optInt("ping", -1)
                    currentJitter = json.optInt("jitter", 0)
                    serverStatus = if (currentPing != -1) "ONLINE" else "UNREACHABLE"
                    
                    if (currentPing != -1) {
                        pingHistory = (pingHistory + currentPing).takeLast(20)
                    }
                } catch (e: Throwable) {
                    serverStatus = "ERROR"
                }
            } else {
                serverStatus = "LIB_ERR"
                if (logs.lastOrNull() != "EGI >> CRITICAL: NATIVE CORE MISSING") {
                    addLog("EGI >> CRITICAL: NATIVE CORE MISSING")
                }
            }
        }
    }

    // Collect Live Traffic Logs (The Matrix)
    LaunchedEffect(Unit) {
        TrafficEvent.events.collect { event ->
            addLog(event)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // --- TOP SECTION: THE HUD (Weight 0.45) ---
        Column(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxWidth()
        ) {
            // Server Selector
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .background(Color.DarkGray.copy(alpha = 0.3f))
                    .padding(8.dp)
            ) {
                Text(
                    text = "SERVER: ${selectedServer.name}",
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(Color.Black)
                ) {
                    GameServers.list.forEach { server ->
                        DropdownMenuItem(
                            text = { Text(server.name, color = Color.Green, fontFamily = FontFamily.Monospace) },
                            onClick = {
                                selectedServer = server
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Graph & Ping
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PingGraph(
                    history = pingHistory,
                    modifier = Modifier
                        .weight(1f)
                        .height(80.dp)
                        .padding(end = 16.dp)
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (currentPing == -1) "-- ms" else "$currentPing ms",
                        color = when {
                            currentPing == -1 -> Color.Red
                            currentPing < 60 -> Color.Green
                            currentPing < 120 -> Color.Yellow
                            else -> Color.Red
                        },
                        fontSize = 32.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text("CURRENT PING", color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Secondary Stats & Blocked Counter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("JITTER", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("$currentJitter ms", color = Color.Green, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DISTRACTIONS BLOCKED", color = Color.Cyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("$blockedCount", color = Color.Cyan, fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("STATUS", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(serverStatus, color = if(serverStatus == "ONLINE") Color.Green else Color.Red, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = if (isSecure) "VPN TUNNEL: ENCRYPTED" else "VPN TUNNEL: INACTIVE",
                color = if (isSecure) Color.Cyan else Color.Gray,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        Divider(color = Color.Green, thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))

        // --- BOTTOM SECTION: THE MATRIX (Weight 0.55) ---
        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        color = when {
                            log.contains("BLOCKED") -> Color.Red
                            log.contains("ACTIVE") -> Color.Cyan
                            log.contains("ERROR") -> Color.Red
                            log.contains("NUCLEAR") || log.contains("TACTICAL") -> Color.Yellow
                            else -> Color.Green
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, // Slightly smaller for log
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Control Grid
            Column(modifier = Modifier.fillMaxWidth()) {
                 Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MenuButton("[ NUCLEAR MODE ]") { onOpenAppSelector() }
                    MenuButton("[ DNS ]") { onOpenDnsPicker() }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MenuButton("[ NETWORK RADAR ]") {
                        val status = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                        if (status == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            onOpenWifiRadar()
                        } else {
                            permissionLauncher.launch(arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            ))
                        }
                    }
                    MenuButton("[ VIP LANE ]") { onOpenAppPicker() }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Toggle Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        if (!isSecure && !isBooting) {
                            isBooting = true
                            scope.launch {
                                typeLog("EGI >> REQUESTING KERNEL PERMISSIONS...")
                                val intent = VpnService.prepare(context)
                                if (intent != null) {
                                    vpnLauncher.launch(intent)
                                } else {
                                    context.startService(Intent(context, EgiVpnService::class.java))
                                    isSecure = true
                                    isBooting = false
                                    typeLog("EGI >> PROTOCOL RE-ENGAGED.")
                                }
                            }
                        } else if (isSecure) {
                            val stopIntent = Intent(context, EgiVpnService::class.java).apply {
                                action = EgiVpnService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                            isSecure = false
                            scope.launch {
                                typeLog("EGI >> ABORTING PROTOCOL...")
                                typeLog("EGI >> PROTOCOL DISENGAGED.")
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSecure) "> [ ABORT ] <" else "> [ EXECUTE ] <",
                    color = if (isSecure) Color.Red else Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun MenuButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.Green,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier
            .clickable { onClick() }
            .padding(8.dp)
    )
}
