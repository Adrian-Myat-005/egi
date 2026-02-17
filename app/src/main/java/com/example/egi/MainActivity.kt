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
import androidx.compose.foundation.border
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
    var gatewayIp by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    if (showLogs) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            containerColor = Color.Black,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxSize().padding(8.dp),
            text = { TerminalLog(onClose = { showLogs = false }) },
            confirmButton = {
                TextButton(onClick = { showLogs = false }) {
                    Text("[ CLOSE_LOGS ]", color = Color.Red, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

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
                onShowLogs = { showLogs = true },
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
    onShowLogs: () -> Unit,
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

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
                    .border(0.5.dp, Color.Green.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (currentSsid != null) "[ WIFI: $currentSsid ]" else "[ NO_WIFI ]",
                    color = if (isCurrentSsidTrusted) Color.Green else Color.Yellow,
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
                    .border(0.5.dp, Color.Green.copy(alpha = 0.5f))
                    .clickable { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
                contentAlignment = Alignment.Center
            ) {
                Text("[ CONFIG ]", color = Color.Cyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            Box(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .border(0.5.dp, Color.Green.copy(alpha = 0.5f))
                    .clickable { showManual = true },
                contentAlignment = Alignment.Center
            ) {
                Text("[ ? ]", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            StatsTile("PING", if (currentPing == -1) "--" else "$animatedPing ms", 1f, if (currentPing < 80) Color.Green else Color.Red)
            StatsTile("JITTER", "$currentJitter ms", 1f, Color.Cyan)
            StatsTile("STATUS", if (isSecure) "ACTIVE" else "STANDBY", 1.4f, if (isSecure) Color.Green else Color.Gray)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(0.5.dp, Color.Green.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "THREATS BLOCKED",
                    color = Color.Green.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = String.format("%,d", blockedCount),
                    color = if (isSecure) Color.Green else Color.DarkGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                if (isStrictBlocking) {
                    Text(
                        "STRICT_BLOCKING_DETECTED",
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
                    text = if (!isStealthMode) "[ NUCLEAR: ACTIVE ]" else "[ NUCLEAR MODE ]",
                    modifier = Modifier.weight(1f),
                    color = if (!isStealthMode) Color.Yellow else Color.Green
                ) {
                    isStealthMode = false
                    EgiPreferences.setStealthMode(context, false)
                    onOpenAppSelector()
                }
                GridButton("[ NETWORK RADAR ]", Modifier.weight(1f)) {
                    val status = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    if (status == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        onOpenWifiRadar()
                    } else {
                        permissionLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(55.dp)) {
                GridButton("[ TERMINAL LOG ]", Modifier.weight(1f)) { onShowLogs() }
                GridButton("[ STEALTH KEY ]", Modifier.weight(1f)) { showKeyDialog = true }
            }
            Row(modifier = Modifier.fillMaxWidth().height(55.dp)) {
                GridButton(
                    text = if (isBatteryOptimized) "[ BATTERY: OK ]" else "[ BATTERY: FIX ]",
                    modifier = Modifier.weight(0.8f),
                    color = if (isBatteryOptimized) Color.Green else Color.Red
                ) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = android.net.Uri.parse("package:${context.packageName}")
                        context.startActivity(intent)
                    } else {
                        Toast.makeText(context, "BATTERY_OPTIMIZATION: UNRESTRICTED", Toast.LENGTH_SHORT).show()
                    }
                }
                GridButton(
                    text = if (isAutoStart) "[ BOOT: ON ]" else "[ BOOT: OFF ]",
                    modifier = Modifier.weight(0.8f),
                    color = if (isAutoStart) Color.Cyan else Color.Gray
                ) {
                    isAutoStart = !isAutoStart
                    EgiPreferences.setAutoStart(context, isAutoStart)
                    Toast.makeText(context, "AUTO_BOOT: ${if(isAutoStart) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                }
                GridButton(
                    text = if (isStealthMode) "[ VIP LANE: ACTIVE ]" else "[ VIP LANE ]",
                    modifier = Modifier.weight(1.4f),
                    color = if (isStealthMode) Color.Magenta else Color.Green
                ) {
                    isStealthMode = true
                    EgiPreferences.setStealthMode(context, true)
                    onOpenAppPicker()
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .border(1.dp, Color.Green)
                .background(if (isSecure) Color.Red.copy(alpha = 0.1f) else Color.Green.copy(alpha = 0.05f))
                .clickable {
                    handleExecuteToggle(context, isSecure, isBooting, isStealthMode, onOpenAppSelector, onOpenAppPicker, vpnLauncher) { isBooting = it }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    isBooting -> "> [ BOOTING... ] <"
                    isSecure -> "> [ ABORT ] <"
                    isStrictBlocking && !isStealthMode -> "> [ LOCKED: CONFIG_VPN ] <"
                    else -> "> [ EXECUTE ] <"
                },
                color = if (isSecure) Color.Red else Color.Green,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
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
                Column {
                    OutlinedTextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
                        label = { Text("OUTLINE / SS KEY", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.Green, fontFamily = FontFamily.Monospace)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "[ PASTE FROM CLIPBOARD ]",
                        color = Color.Yellow,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { 
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                tempKey = clip.getItemAt(0).text.toString()
                            }
                        }.padding(4.dp)
                    )
                }
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

    if (showManual) { TacticalManual(onDismiss = { showManual = false }) }
}

@Composable
fun RowScope.StatsTile(label: String, value: String, weightRatio: Float, valueColor: Color) {
    Box(
        modifier = Modifier
            .weight(weightRatio)
            .fillMaxHeight()
            .border(0.5.dp, Color.Green.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RowScope.GridButton(text: String, modifier: Modifier = Modifier, color: Color = Color.Green, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .border(0.5.dp, Color.Green.copy(alpha = 0.5f))
            .clickable { onClick() },
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
    onOpenAppSelector: () -> Unit,
    onOpenAppPicker: () -> Unit,
    vpnLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    setBooting: (Boolean) -> Unit
) {
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
            if (vipList.isEmpty()) {
                if (isStealthMode) {
                    Toast.makeText(context, "PICK A VIP APP FIRST!", Toast.LENGTH_LONG).show()
                    onOpenAppPicker()
                } else {
                    Toast.makeText(context, "PICK A TARGET APP FIRST!", Toast.LENGTH_LONG).show()
                    onOpenAppSelector()
                }
                return
            }
            setBooting(true)
            TrafficEvent.log("USER >> BOOTING_SHIELD")
            val intent = VpnService.prepare(context)
            if (intent != null) { vpnLauncher.launch(intent) } 
            else { ContextCompat.startForegroundService(context, Intent(context, EgiVpnService::class.java)) }
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
        title = { Text("EGI >> SYSTEM_OPERATING_MANUAL", color = Color.Cyan, fontFamily = FontFamily.Monospace, fontSize = 16.sp) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    ManualSection(
                        "1. VIP LANE (VPN FOCUS)",
                        "EN: Only the apps you pick go through the VPN. Others stay on normal internet.\n" +
                        "MM: သင်ရွေးထားတဲ့ app တွေပဲ VPN ကိုဖြတ်မှာပါ။ ကျန်တာတွေက ရိုးရိုးအင်တာနက်ပဲ သုံးမှာပါ။\n\n" +
                        "• FOCUS: Pick 1 app (Best for Games/Banking).\n" +
                        "• CASUAL: Pick many apps.\n" +
                        "• MM: App တစ်ခုတည်း သို့မဟုတ် အများအပြားကို VPN နဲ့ သီးသန့်သုံးလို့ရပါတယ်။"
                    )
                    ManualSection(
                        "2. NUCLEAR MODE (BYPASS)",
                        "EN: Shields your WHOLE phone. Only your picked apps bypass the shield.\n" +
                        "MM: ဖုန်းတစ်ခုလုံးကို Shield နဲ့ ကာကွယ်လိုက်တာပါ။ သင်ရွေးထားတဲ့ app တွေပဲ အပြင်ထွက်ခွင့်ရပါမယ်။\n\n" +
                        "• USE: When you want total privacy but need 1-2 apps to work locally.\n" +
                        "• MM: ဖုန်းလုံခြုံရေးအတွက် တစ်ဖုန်းလုံးကို ပိတ်ထားပြီး လိုအပ်တဲ့ app ကိုပဲ ခွင့်ပြုချင်ရင် သုံးပါ။"
                    )
                    ManualSection(
                        "3. CONFIG (ALWAYS-ON VPN)",
                        "EN: CRITICAL! Click [CONFIG] -> Gear Icon -> Enable 'Always-on' and 'Block non-VPN'.\n" +
                        "MM: အရေးကြီး! [CONFIG] ကိုနှိပ်၊ ဂီယာပုံလေးကိုနှိပ်ပြီး 'Always-on' နဲ့ 'Block non-VPN' ကို ဖွင့်ထားပါ။\n\n" +
                        "• WHY: This stops data leaks if the VPN drops.\n" +
                        "• MM: VPN ပြုတ်သွားရင်တောင် အင်တာနက်မပေါက်အောင် ကာကွယ်ပေးဖို့ပါ။"
                    )
                    ManualSection(
                        "4. BATTERY & BOOT",
                        "EN: Make sure BATTERY says [OK]. This prevents Android from killing EGI in the background.\n" +
                        "MM: BATTERY ကို [OK] ဖြစ်အောင်လုပ်ပါ။ ဒါမှ နောက်ကွယ်မှာ EGI အမြဲအလုပ်လုပ်နေမှာပါ။\n\n" +
                        "• BOOT: If ON, EGI starts automatically when you restart your phone.\n" +
                        "• MM: BOOT ဖွင့်ထားရင် ဖုန်းပိတ်ပြီးပြန်တက်လာတာနဲ့ EGI က အလိုအလျောက် အလုပ်လုပ်ပေးမှာပါ။"
                    )
                    ManualSection(
                        "5. NETWORK RADAR",
                        "EN: Scan your WiFi for 'Intruders' (people stealing your net). Use [BLOCK] to kick them.\n" +
                        "MM: သင့် WiFi ကို ခိုးသုံးနေတဲ့သူတွေကို ရှာပြီး [BLOCK] နဲ့ နှင်ထုတ်နိုင်ပါတယ်။\n\n" +
                        "• NOTE: Requires your router username/password in Radar Setup.\n" +
                        "• MM: Radar Setup မှာ သင့် Router ရဲ့ user နဲ့ pass ထည့်ပေးဖို့ လိုပါတယ်။"
                    )
                    ManualSection(
                        "6. STEALTH KEY",
                        "EN: Paste your Outline or SS Key here to fuel the VIP Lane tunnel.\n" +
                        "MM: VPN လမ်းကြောင်းအတွက် Outline (သို့) SS Key ကို ဒီမှာ ထည့်ပေးပါ။"
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
fun TerminalLog(onClose: () -> Unit) {
    val events = TrafficEvent.events.collectAsState(initial = "INITIALIZING...")
    val logHistory = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    LaunchedEffect(events.value) {
        if (events.value == "CONSOLE_CLEARED") { logHistory.clear() } 
        else {
            logHistory.add("${System.currentTimeMillis() % 100000} >> ${events.value}")
            if (logHistory.size > 50) logHistory.removeAt(0)
            listState.animateScrollToItem(logHistory.size)
        }
    }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("EGI_CONSOLE_V1.0", color = Color.Cyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("[ X ]", color = Color.Red, fontSize = 12.sp, modifier = Modifier.clickable { onClose() })
        }
        Divider(color = Color.Cyan, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(logHistory) { log ->
                Text(text = log, color = if (log.contains("ERROR")) Color.Red else Color.Green, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
