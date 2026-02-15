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
import androidx.compose.foundation.Canvas
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class Screen {
    TERMINAL, APP_PICKER, DNS_PICKER, APP_SELECTOR, WIFI_RADAR, ROUTER_ADMIN
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
    var routerAdminData by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) } // mac, gateway, autoOpt

    Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
        when (screen) {
            Screen.APP_PICKER -> AppPickerScreen(onBack = { currentScreen = Screen.TERMINAL })
            Screen.DNS_PICKER -> DnsPickerScreen(onBack = { msg ->
                dnsLogMessage = msg
                currentScreen = Screen.TERMINAL
            })
            Screen.APP_SELECTOR -> AppSelectorScreen(onBack = { currentScreen = Screen.TERMINAL })
            Screen.WIFI_RADAR -> WifiScanScreen(
                onBack = { currentScreen = Screen.TERMINAL },
                onNavigateToRouter = { mac, gateway, autoOpt ->
                    routerAdminData = Triple(mac, gateway, autoOpt)
                    currentScreen = Screen.ROUTER_ADMIN
                }
            )
            Screen.ROUTER_ADMIN -> {
                routerAdminData?.let { (mac, gateway, autoOpt) ->
                    RouterAdminScreen(
                        targetMac = mac,
                        gatewayIp = gateway,
                        autoOptimize = autoOpt,
                        onBack = { currentScreen = Screen.WIFI_RADAR }
                    )
                }
            }
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
    var isSecure by remember { mutableStateOf(false) }
    var isBooting by remember { mutableStateOf(false) }

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

    // Handle DNS change log and restart
    LaunchedEffect(dnsMsg) {
        if (dnsMsg != null) {
            if (isSecure) {
                val restartIntent = Intent(context, EgiVpnService::class.java).apply {
                    action = EgiVpnService.ACTION_RESTART
                }
                context.startService(restartIntent)
            }
            onDnsLogConsumed()
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            context.startService(Intent(context, EgiVpnService::class.java))
            isSecure = true
            isBooting = false
        } else {
            isBooting = false
            Toast.makeText(context, "KERNEL ACCESS DENIED", Toast.LENGTH_SHORT).show()
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
            }
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

            // Secondary Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("JITTER", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("$currentJitter ms", color = Color.Green, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
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

        // --- BOTTOM SECTION: SILENT SHIELD (Weight 0.55) ---
        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ShieldStatusCard(blockedCount, isSecure)

            Spacer(modifier = Modifier.weight(1f))

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
                            val intent = VpnService.prepare(context)
                            if (intent != null) {
                                vpnLauncher.launch(intent)
                            } else {
                                context.startService(Intent(context, EgiVpnService::class.java))
                                isSecure = true
                                isBooting = false
                            }
                        } else if (isSecure) {
                            val stopIntent = Intent(context, EgiVpnService::class.java).apply {
                                action = EgiVpnService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                            isSecure = false
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
fun ShieldStatusCard(count: Int, isActive: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(24.dp)
    ) {
        Text(
            text = if (isActive) "SHIELD ACTIVE" else "SHIELD STANDBY",
            color = if (isActive) Color.Cyan else Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "$count",
            color = if (isActive) Color.Green else Color.DarkGray,
            fontFamily = FontFamily.Monospace,
            fontSize = 64.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "THREATS BLOCKED",
            color = if (isActive) Color.Green.copy(alpha = 0.7f) else Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
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
