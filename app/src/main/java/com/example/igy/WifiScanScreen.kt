package com.example.igy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.InetAddress

@Composable
fun WifiScanScreen(isDarkMode: Boolean, onBack: () -> Unit, onNavigateToRouter: (String) -> Unit) {
    val context = LocalContext.current
    val wifiManager = remember { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    val gatewayIp = remember { WifiUtils.getGatewayIp(context) }
    val subnetPrefix = remember { WifiUtils.getSubnetPrefix(context) }
    var rawDevices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var resolvedDevices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var isMapView by remember { mutableStateOf(false) }
    var showRouterCreds by remember { mutableStateOf(false) }
    var routerUser by remember { mutableStateOf("") }
    var routerPass by remember { mutableStateOf("") }
    var routerBrand by remember { mutableStateOf("Generic") }

    // Load saved credentials
    LaunchedEffect(Unit) {
        val (u, p, b) = IgyPreferences.getRouterCredentials(context)
        routerUser = u
        routerPass = p
        routerBrand = b
        
        // AUTO-TRIGGER: If no username or password, force setup
        if (u.isEmpty() || p.isEmpty()) {
            showRouterCreds = true
        }
    }

    // Channel Analysis State
    var channelUsage by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var bestChannel by remember { mutableStateOf(1) }
    var currentChannel by remember { mutableStateOf(0) }

    // Initial Subnet Scan
    LaunchedEffect(Unit) {
        if (IgyNetwork.isAvailable()) {
            try {
                val jsonStr = withContext(Dispatchers.IO) {
                    IgyNetwork.scanSubnet(subnetPrefix)
                }
                val array = JSONArray(jsonStr)
                val list = mutableListOf<DeviceInfo>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(DeviceInfo(
                        ip = obj.getString("i"), 
                        mac = obj.getString("m"),
                        status = obj.getString("s")
                    ))
                }
                // Ensure Gateway is always present
                if (list.none { it.status == "Gateway" }) {
                    list.add(0, DeviceInfo(ip = gatewayIp, status = "Gateway", mac = "FF:FF:FF:FF:FF:FF", name = "SYSTEM_GATEWAY"))
                }
                rawDevices = list
                resolvedDevices = list 
            } catch (t: Throwable) {
                // Fallback
                rawDevices = listOf(DeviceInfo(ip = gatewayIp, status = "Gateway", mac = "FF:FF:FF:FF:FF:FF", name = "SYSTEM_GATEWAY"))
                resolvedDevices = rawDevices
            }
        }
        isScanning = false
    }

    // ... (keep channel scan and resolution loops)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp)
    ) {
        // --- MATRIX HEADER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .border(0.5.dp, Color.Green.copy(alpha = 0.5f))
        ) {
            Box(
                modifier = Modifier
                    .weight(1.5f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp)
                    .clickable { showRouterCreds = true }, // Click header to manage router
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "NETWORK RADAR >> ${if (isScanning) "[ SCANNING... ]" else "[ $routerBrand ]"}",
                    color = if (isScanning) Color.Yellow else Color.Cyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, Color.Green.copy(alpha = 0.5f))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("[ BACK ]", color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        // Info Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(0.5.dp, Color.Green.copy(alpha = 0.3f))
                .clickable { showRouterCreds = true }
        ) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                Text("GATEWAY: $gatewayIp", color = Color.Cyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
                Text("CH: ${if(currentChannel > 0) currentChannel else "?"} (OPTIMIZED)", color = Color.Green, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // ... (Main Content Area remains same)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(0.5.dp, Color.Green.copy(alpha = 0.2f))
        ) {
            if (isMapView) {
                TopologyMap(
                    devices = resolvedDevices,
                    onDeviceClick = { if (it.status != "Gateway") selectedDevice = it else showRouterCreds = true }
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(resolvedDevices) { device ->
                        MatrixDeviceRow(
                            device = device,
                            onClick = {
                                if (device.status != "Gateway") {
                                    selectedDevice = device
                                } else {
                                    showRouterCreds = true
                                }
                            }
                        )
                    }
                }
            }
        }
        // ... (rest remains same)
    }

    if (showRouterCreds) {
        AlertDialog(
            onDismissRequest = { showRouterCreds = false },
            containerColor = Color.Black,
            title = { Text("ROUTER ACCESS CONTROL", color = Color.Cyan, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    val brands = listOf("TP-Link", "Huawei", "ASUS", "ZTE", "Generic")
                    Text("BRAND PROFILE:", color = Color.Gray, fontSize = 10.sp)
                    Row(Modifier.fillMaxWidth().height(40.dp)) {
                        brands.forEach { b ->
                            Box(
                                Modifier.weight(1f).fillMaxHeight().border(0.2.dp, Color.Green)
                                    .background(if(routerBrand == b) Color.Green.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable { routerBrand = b },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(b, color = if(routerBrand == b) Color.Green else Color.Gray, fontSize = 8.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = routerUser, onValueChange = { routerUser = it },
                        label = { Text("ADMIN USERNAME", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.Green, fontFamily = FontFamily.Monospace)
                    )
                    OutlinedTextField(
                        value = routerPass, onValueChange = { routerPass = it },
                        label = { Text("ADMIN PASSWORD", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.Green, fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    IgyPreferences.saveRouterCredentials(context, routerUser, routerPass, routerBrand)
                    showRouterCreds = false
                    onNavigateToRouter(gatewayIp)
                }) {
                    Text("SAVE & LOGIN", color = Color.Green, fontFamily = FontFamily.Monospace)
                }
            }

        )
    }



    selectedDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { selectedDevice = null },
            containerColor = Color.Black,
            title = {
                Text("DEVICE DETAILS", color = Color.Cyan, fontFamily = FontFamily.Monospace)
            },
            text = {
                Text(
                    """Name: ${device.name}
Target: ${device.ip}
Action: Open router dashboard for advanced management or use TCP Flood to isolate.""",
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                Column {
                    Button(
                        onClick = {
                            if (IgyNetwork.isAvailable()) {
                                IgyNetwork.kickDevice(device.ip, device.mac)
                                Toast.makeText(context, "TCP FLOOD INITIATED", Toast.LENGTH_SHORT).show()
                            }
                            selectedDevice = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red),
                        shape = androidx.compose.ui.graphics.RectangleShape
                    ) {
                        Text("INITIATE TCP FLOOD", color = Color.Red, fontFamily = FontFamily.Monospace)
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Button(
                        onClick = {
                            onNavigateToRouter(gatewayIp)
                            selectedDevice = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan.copy(alpha = 0.2f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Cyan),
                        shape = androidx.compose.ui.graphics.RectangleShape
                    ) {
                        Text("OPEN ROUTER DASHBOARD", color = Color.Cyan, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedDevice = null }) {
                    Text("CANCEL", color = Color.Green, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}


@Composable
fun RadarGridButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .border(0.5.dp, Color.Green.copy(alpha = 0.5f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
fun MatrixDeviceRow(device: DeviceInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .border(0.2.dp, Color.Green.copy(alpha = 0.1f))
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = device.name, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1)
            Text(text = "IP: ${device.ip}", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        if (device.status != "Gateway") {
            Text(text = "[ KICK ]", color = Color.Red, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TopologyMap(devices: List<DeviceInfo>, onDeviceClick: (DeviceInfo) -> Unit) {
    val gateway = devices.find { it.status == "Gateway" }
    val others = devices.filter { it.status != "Gateway" }
    
    Box(
        modifier = Modifier.fillMaxSize().background(Color.DarkGray.copy(alpha = 0.05f)),
        contentAlignment = Alignment.Center
    ) {
        // Radar Rings
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = minOf(size.width, size.height) / 2 * 0.8f
            drawCircle(Color.Green.copy(alpha = 0.1f), radius = maxRadius * 0.33f, style = Stroke(1f))
            drawCircle(Color.Green.copy(alpha = 0.1f), radius = maxRadius * 0.66f, style = Stroke(1f))
            drawCircle(Color.Green.copy(alpha = 0.1f), radius = maxRadius, style = Stroke(1f))
        }
        
        gateway?.let { Node(it, isGateway = true, onClick = {}) }

        others.forEachIndexed { index, device ->
            val angle = (2 * Math.PI * index / others.size).toFloat()
            val rMult = if (index % 2 == 0) 0.5f else 0.8f
            val radius = 150.dp * rMult
            val offsetX = (radius.value * Math.cos(angle.toDouble())).dp
            val offsetY = (radius.value * Math.sin(angle.toDouble())).dp

            Box(modifier = Modifier.offset(x = offsetX, y = offsetY)) {
                Node(device, isGateway = false, onClick = { onDeviceClick(device) })
            }
        }
    }
}

@Composable
fun Node(device: DeviceInfo, isGateway: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(if (isGateway) 40.dp else 28.dp)
                .background(Color.Black, androidx.compose.foundation.shape.CircleShape)
                .border(1.dp, if(isGateway) Color.Green else Color.Cyan, androidx.compose.foundation.shape.CircleShape)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if(isGateway) Icons.Default.Router else Icons.Default.SettingsInputAntenna,
                contentDescription = null,
                tint = if (isGateway) Color.Green else Color.Cyan,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            text = device.ip.split(".").last(),
            color = Color.Green,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

data class DeviceInfo(
    val ip: String,
    val name: String = "Unknown Device",
    val status: String,
    val mac: String = "00:00:00:00:00:00"
)
