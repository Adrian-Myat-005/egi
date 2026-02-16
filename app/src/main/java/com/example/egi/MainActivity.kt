package com.example.egi

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    val isSecure by TrafficEvent.vpnActive.collectAsState()
    var isBooting by remember { mutableStateOf(false) }
    var isStealthMode by remember { mutableStateOf(EgiPreferences.isStealthMode(context)) }
    var outlineKey by remember { mutableStateOf(EgiPreferences.getOutlineKey(context)) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var currentSsid by remember { mutableStateOf<String?>(null) }
    var isCurrentSsidTrusted by remember { mutableStateOf(false) }

    // HUD States
    var selectedServer by remember { mutableStateOf(GameServers.list.first()) }
    var currentPing by remember { mutableStateOf(0) }
    val animatedPing by animateIntAsState(targetValue = currentPing, animationSpec = tween(300), label = "PingAnim")
    var pingHistory by remember { mutableStateOf(List(20) { 0 }) }
    var currentJitter by remember { mutableStateOf(0) }
    var serverStatus by remember { mutableStateOf("CONNECTING") }
    val blockedCount by TrafficEvent.blockedCount.collectAsState()

    // Dropdown State
    var expanded by remember { mutableStateOf(false) }

    fun importConfigFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text.toString()
            if (text.startsWith("ss://")) {
                outlineKey = text
                EgiPreferences.saveOutlineKey(context, outlineKey)
                Toast.makeText(context, "CONFIG IMPORTED", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "INVALID SS KEY", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Sync Stealth state to native
    LaunchedEffect(isStealthMode, outlineKey) {
        if (EgiNetwork.isAvailable()) {
            EgiNetwork.toggleStealthMode(isStealthMode)
            if (outlineKey.isNotEmpty()) {
                EgiNetwork.setOutlineKey(outlineKey)
            }
        }
    }

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

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            context.startService(Intent(context, EgiVpnService::class.java))
            isBooting = false
        } else {
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

    // Real-Time Stats Loop (The HUD)
    LaunchedEffect(selectedServer) {
        while (true) {
            delay(1500)
            if (EgiNetwork.isAvailable()) {
                try {
                    val statsJson = withContext(Dispatchers.IO) {
                        EgiNetwork.measureNetworkStats(selectedServer.ip)
                    }
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
                .weight(0.48f)
                .fillMaxWidth()
        ) {
            // Server Selector & Stealth Toggle
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
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
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = if (isStealthMode) "[ STEALTH: ACTIVE ]" else "[ STEALTH: OFF ]",
                    color = if (isStealthMode) Color.Magenta else Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clickable {
                            isStealthMode = !isStealthMode
                            EgiPreferences.setStealthMode(context, isStealthMode)
                        }
                        .padding(4.dp)
                )
            }
                
            Spacer(modifier = Modifier.height(8.dp))
                
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.DarkGray.copy(alpha = 0.2f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("EGI CORE STATUS: NOMINAL", color = Color.Green, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(
                    text = "[ ? ]",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showManual = true }.padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            // WiFi Info
            if (currentSsid != null) {
                Text(
                    text = "WIFI: $currentSsid (${if(isCurrentSsidTrusted) "TRUSTED" else "UNTRUSTED"})",
                    color = if(isCurrentSsidTrusted) Color.Green else Color.Yellow,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            } else {
                Text("NO WIFI DETECTED", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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
                        text = if (currentPing == -1) "-- ms" else "$animatedPing ms",
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

        // --- BOTTOM SECTION: SILENT SHIELD (Weight 0.52) ---
        Column(
            modifier = Modifier
                .weight(0.52f)
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
                    MenuButton("[ STEALTH KEY ]") { showKeyDialog = true }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MenuButton("[ IMPORT KEY ]") { importConfigFromClipboard() }
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
                }
            }

                

                if (showKeyDialog) {

                    var tempKey by remember { mutableStateOf(outlineKey) }

                    AlertDialog(

                        onDismissRequest = { showKeyDialog = false },

                        containerColor = Color.Black,

                        title = { Text("STEALTH TUNNEL CONFIG", color = Color.Green, fontFamily = FontFamily.Monospace) },

                        text = {

                            OutlinedTextField(

                                value = tempKey,

                                onValueChange = { tempKey = it },

                                label = { Text("OUTLINE / SS KEY", color = Color.Gray) },

                                modifier = Modifier.fillMaxWidth(),

                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.Green, fontFamily = FontFamily.Monospace)

                            )

                        },

                        confirmButton = {

                            TextButton(onClick = {

                                outlineKey = tempKey

                                EgiPreferences.saveOutlineKey(context, outlineKey)

                                showKeyDialog = false

                            }) {

                                Text("SAVE", color = Color.Green, fontFamily = FontFamily.Monospace)

                            }

                        },

                        dismissButton = {

                            TextButton(onClick = { showKeyDialog = false }) {

                                Text("CANCEL", color = Color.Red, fontFamily = FontFamily.Monospace)

                            }

                        }

                    )

                }

    

                if (showManual) {

                    TacticalManual(onDismiss = { showManual = false })

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

                                                            isBooting = false // Reset here if already prepared

                                                        }

                                                    } else if (isSecure) {

                                val stopIntent = Intent(context, EgiVpnService::class.java).apply {

                                    action = EgiVpnService.ACTION_STOP

                                }

                                context.startService(stopIntent)

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

    

    fun TacticalManual(onDismiss: () -> Unit) {

    

        AlertDialog(

    

            onDismissRequest = onDismiss,

    

            containerColor = Color.Black,

    

            title = {

    

                Text("EGI >> SYSTEM MANUAL", color = Color.Cyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

    

            },

    

            text = {

    

                LazyColumn(modifier = Modifier.fillMaxWidth()) {

    

                    item {

    

                        Text("1. [ VIP LANE ]: Open this and pick the apps you want to keep ALIVE (e.g. Facebook, Chrome).", color = Color.Green, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    

                        Spacer(modifier = Modifier.height(8.dp))

    

                        Text("2. [ EXECUTE ]: Click this button. Egi will capture all traffic. Apps in your VIP list get 100% speed. Apps NOT in the list are silented (blocked) to save data and battery.", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    

                        Spacer(modifier = Modifier.height(8.dp))

    

                        Text("3. [ STEALTH ]: Only turn this ON if your WiFi is blocking you. It uses your Outline key to sneak past firewalls.", color = Color.Yellow, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    

                        Spacer(modifier = Modifier.height(8.dp))

    

                        Text("4. [ RADAR ]: Use this to see who else is on your WiFi. If they are stealing your speed, click [ KICK ] to silence them locally.", color = Color.Magenta, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    

                        

    

                        Divider(color = Color.DarkGray, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

    

                        Text("GOAL: 0ms Lag, 100% Signal, 0% Data Waste.", color = Color.Cyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

    

                    }

    

                }

    

            },

    

            confirmButton = {

    

                TextButton(onClick = onDismiss) {

    

                    Text("[ MISSION UNDERSTOOD ]", color = Color.Green, fontFamily = FontFamily.Monospace)

    

                }

    

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
fun ShieldStatusCard(count: Int, isActive: Boolean) {
    val animatedCount by animateIntAsState(targetValue = count, animationSpec = tween(500), label = "CountAnim")

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
            text = "$animatedCount",
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
