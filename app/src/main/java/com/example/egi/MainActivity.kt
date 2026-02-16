package com.example.egi

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
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
    val events by TrafficEvent.events.collectAsState(initial = "SYSTEM_READY")
    var isBooting by remember { mutableStateOf(false) }
    var isStealthMode by remember { mutableStateOf(EgiPreferences.isStealthMode(context)) }
    var isLocalBypass by remember { mutableStateOf(EgiPreferences.getLocalBypass(context)) }
    var isAutoStart by remember { mutableStateOf(EgiPreferences.getAutoStart(context)) }
    var outlineKey by remember { mutableStateOf(EgiPreferences.getOutlineKey(context)) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var currentSsid by remember { mutableStateOf<String?>(null) }
    var isCurrentSsidTrusted by remember { mutableStateOf(false) }
    var isBatteryOptimized by remember { mutableStateOf(false) }

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

    // HUD States
    var selectedServer by remember { mutableStateOf(GameServers.list.first()) }
    var currentPing by remember { mutableStateOf(0) }
    val animatedPing by animateIntAsState(targetValue = currentPing, animationSpec = tween(300), label = "PingAnim")
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

    fun openVpnSettings() {
        val intent = Intent("android.net.vpn.SETTINGS")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "COULD NOT OPEN SETTINGS", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestBatteryOptimizationBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "BATTERY_OPTIMIZATION ALREADY BYPASSED", Toast.LENGTH_SHORT).show()
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
            ContextCompat.startForegroundService(context, Intent(context, EgiVpnService::class.java))
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
                if (!isBatteryOptimized) {
                    Text("BATTERY_WARN", color = Color.Red, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
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

            // Stats HUD
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("PING", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = if (currentPing == -1) "-- ms" else "$animatedPing ms",
                        color = when {
                            currentPing == -1 -> Color.Red
                            currentPing < 60 -> Color.Green
                            currentPing < 120 -> Color.Yellow
                            else -> Color.Red
                        },
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("JITTER", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = "$currentJitter ms",
                        color = Color.Cyan,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("BLOCKED", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = blockedCount.toString(),
                        color = if (blockedCount > 0) Color.Red else Color.Green,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = when {
                    !isSecure -> ">> VPN TUNNEL: INACTIVE <<"
                    isStealthMode && outlineKey.isNotEmpty() -> ">> STEALTH TUNNEL: ENCRYPTED <<"
                    else -> ">> OFFLINE SHIELD: ACTIVE <<"
                },
                color = when {
                    !isSecure -> Color.Gray
                    isStealthMode && outlineKey.isNotEmpty() -> Color.Cyan
                    else -> Color.Yellow // Yellow for caution/offline blocking
                },
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
            if (showLogs) {
                TerminalLog(onClose = { showLogs = false })
            } else {
                ShieldStatusCard(blockedCount, isSecure)
            }

            Spacer(modifier = Modifier.weight(1f))

            // Control Grid
            Column(modifier = Modifier.fillMaxWidth()) {
                 Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MenuButton("[ NUCLEAR MODE ]") { onOpenAppSelector() }
                    MenuButton("[ TERMINAL_LOG ]") { showLogs = true }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MenuButton("[ STEALTH KEY ]") { showKeyDialog = true }
                    MenuButton("[ ALWAYS_ON_CONFIG ]") { openVpnSettings() }
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
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MenuButton("[ UNRESTRICTED_BATTERY ]") { requestBatteryOptimizationBypass() }
                    MenuButton(if (isAutoStart) "[ AUTO_BOOT: ON ]" else "[ AUTO_BOOT: OFF ]") {
                        isAutoStart = !isAutoStart
                        EgiPreferences.setAutoStart(context, isAutoStart)
                    }
                }
                
                // Local Bypass Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LOCAL NETWORK BYPASS", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Switch(
                        checked = isLocalBypass,
                        onCheckedChange = {
                            isLocalBypass = it
                            EgiPreferences.setLocalBypass(context, it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Green,
                            uncheckedThumbColor = Color.DarkGray
                        )
                    )
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
                            try {
                                if (!isSecure && !isBooting) {
                                    val vipList = EgiPreferences.getVipList(context)
                                    if (!isStealthMode && vipList.isEmpty()) {
                                        Toast.makeText(context, "PICK A TARGET APP FIRST!", Toast.LENGTH_LONG).show()
                                        onOpenAppSelector()
                                        return@clickable
                                    }
                                    if (isStealthMode && outlineKey.isEmpty()) {
                                        Toast.makeText(context, "NO KEY >> SWITCHING TO OFFLINE SHIELD", Toast.LENGTH_SHORT).show()
                                        isStealthMode = false
                                        EgiPreferences.setStealthMode(context, false)
                                        // Proceed to start in Passive Mode
                                    }
                                    isBooting = true
                                    TrafficEvent.log("USER >> INITIATING_SHIELD")
                                    val intent = VpnService.prepare(context)
                                    if (intent != null) {
                                        vpnLauncher.launch(intent)
                                    } else {
                                        TrafficEvent.log("USER >> STARTING_SERVICE")
                                        ContextCompat.startForegroundService(context, Intent(context, EgiVpnService::class.java))
                                        isBooting = false
                                    }
                                } else if (isSecure) {
                                    TrafficEvent.log("USER >> ABORTING_SHIELD")
                                    val stopIntent = Intent(context, EgiVpnService::class.java).apply {
                                        action = EgiVpnService.ACTION_STOP
                                    }
                                    context.startService(stopIntent)
                                }
                            } catch (e: Exception) {
                                TrafficEvent.log("CRITICAL_ERROR >> ${e.message}")
                                isBooting = false
                                Toast.makeText(context, "EXECUTION_FAILED: ${e.message}", Toast.LENGTH_LONG).show()
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
                Text("EGI >> COMPLETE OPERATING MANUAL", color = Color.Cyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        // English Section
                        Text("[ ENGLISH - FULL GUIDE ]", color = Color.Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        ManualSection("NUCLEAR MODE (OFFLINE)", "No VPN Key needed. Automatically blocks all apps except your 'Focus' target. The Shield runs locally.")
                        ManualSection("NUCLEAR MODE + VPN", "To use a VPN for your Focus App while blocking others: Turn on 'Stealth Mode', enter Key, and enable 'Block connections without VPN' in Android Settings.")
                        ManualSection("STEALTH MODE", "If your WiFi blocks everything, turn this ON. Use 'IMPORT KEY' to paste a Shadowsocks (ss://) link. It wraps your traffic in a secret tunnel to sneak past firewalls.")
                        ManualSection("NETWORK RADAR", "Scan your current WiFi to see 'intruders'. If someone is hogging your speed, click their device to isolate them. Use 'LOCAL BYPASS' if you need to use your home printer.")
                        ManualSection("STABILITY", "Turn on 'UNRESTRICTED_BATTERY' and 'AUTO_BOOT' so the shield never sleeps. IMPORTANT: For strict blocking, go to 'ALWAYS_ON_CONFIG' and enable 'Always-on VPN' and 'Block connections without VPN' for Egi.")
                        ManualSection("TROUBLESHOOTING", "If 'EXECUTE' does nothing: 1. Pick a target app in NUCLEAR MODE first. 2. Check if another VPN is active. 3. Ensure you granted VPN permission.")
                        ManualSection("THE HUD", "The green graph shows your speed (Ping). High numbers (Red) mean lag. The 'THREATS BLOCKED' counter shows exactly how many distractions were terminated.")

                        Spacer(modifier = Modifier.height(16.dp))
                        Divider(color = Color.Green, thickness = 2.dp)
                        Spacer(modifier = Modifier.height(16.dp))

                        // Burmese Section
                        Text("[ မြန်မာဘာသာ - အသုံးပြုနည်းအပြည့်အစုံ ]", color = Color.Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        ManualSection("NUCLEAR MODE (အဓိကလုပ်ဆောင်ချက်)", "မိမိသုံးမည့် App နှင့် Website ကို ရွေးချယ်ပေးရပါမည်။ 'Focus' တွင် App တစ်ခုတည်းကိုသာ သုံးနိုင်ပြီး 'Casual' တွင် App အများအပြားကို ရွေးချယ်နိုင်ပါသည်။ Website ကြည့်လိုပါက အကွက်ထဲတွင် Website link (ဥပမာ - stackoverflow.com) ကို ရိုက်ထည့်ပါ။ ကျန်ရှိသော App နှင့် Website အားလုံးကို ပိတ်ထားပေးပါမည်။")
                        ManualSection("STEALTH MODE (လျှို့ဝှက်ဥမှင်စနစ်)", "အင်တာနက်လိုင်း ပိတ်ဆို့ခံထားရပါက ဤစနစ်ကို သုံးပါ။ 'IMPORT KEY' ကိုနှိပ်ပြီး Shadowsocks (ss://) link ကို ထည့်ပါ။ ပိတ်ဆို့ထားသော အင်တာနက်လိုင်းများကို ကျော်ဖြတ်နိုင်ပါမည်။")
                        ManualSection("NETWORK RADAR (ဝိုင်ဖိုင်စစ်ဆေးခြင်း)", "မိမိသုံးနေသော WiFi ထဲတွင် အခြားသူများ ခိုးသုံးနေသလား စစ်ဆေးနိုင်ပါသည်။ အင်တာနက်လိုင်း ဆွဲနေသူများကို တွေ့ပါက [ KICK ] နှိပ်ပြီး ဖြတ်တောက်နိုင်ပါသည်။ အိမ်ရှိ Printer များကို သုံးလိုပါက 'LOCAL BYPASS' ကို ဖွင့်ထားပါ။")
                        ManualSection("STABILITY (အမြဲပွင့်နေစေရန်)", "ဖုန်းပိတ်ပြီး ပြန်ဖွင့်လျှင်လည်း အလိုအလျောက် ပွင့်နေစေရန် 'AUTO_BOOT' ကို ဖွင့်ပါ။ အင်တာနက်လိုင်း မပြတ်တောက်စေရန် 'UNRESTRICTED_BATTERY' ကို နှိပ်ပြီး ခွင့်ပြုချက်ပေးပါ။ အမြဲတမ်း ပိတ်ဆို့ထားလိုပါက 'ALWAYS_ON_CONFIG' ထဲတွင် 'Always-on VPN' နှင့် 'Block connections without VPN' ကို ဖွင့်ပါ။")
                        ManualSection("THE HUD (အခြေအနေပြဘုတ်)", "အစိမ်းရောင် ဂရပ်ဖစ်မျဉ်းသည် အင်တာနက် အမြန်နှုန်း (Ping) ကို ပြခြင်းဖြစ်သည်။ ဂဏန်းကြီးလျှင် (အနီရောင်ဖြစ်လျှင်) လိုင်းလေးနေခြင်း ဖြစ်သည်။ 'THREATS BLOCKED' သည် အနှောင့်အယှက်ပေးသော App မည်မျှကို ပိတ်ဆို့ထားသည်ကို ပြခြင်း ဖြစ်သည်။")

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("GOAL: 100% PRODUCTIVITY. 0% LAG.", color = Color.Cyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
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

@Composable
fun TerminalLog(onClose: () -> Unit) {
    val events = TrafficEvent.events.collectAsState(initial = "INITIALIZING...")
    val logHistory = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    LaunchedEffect(events.value) {
        if (events.value == "CONSOLE_CLEARED") {
            logHistory.clear()
        } else {
            logHistory.add("${System.currentTimeMillis() % 100000} >> ${events.value}")
            if (logHistory.size > 50) logHistory.removeAt(0)
            listState.animateScrollToItem(logHistory.size)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(Color.Black)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("EGI_CONSOLE_V1.0", color = Color.Cyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Row {
                Text(
                    "[ CLEAR ]", 
                    color = Color.Yellow, 
                    fontSize = 12.sp, 
                    modifier = Modifier.clickable { TrafficEvent.clearLogs() }.padding(end = 8.dp)
                )
                Text("[ X ]", color = Color.Red, fontSize = 12.sp, modifier = Modifier.clickable { onClose() })
            }
        }
        Divider(color = Color.Cyan, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(logHistory) { log ->
                Text(
                    text = log,
                    color = if (log.contains("ERROR") || log.contains("CRITICAL")) Color.Red else Color.Green,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
