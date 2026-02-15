package com.example.egi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.InetAddress

@Composable
fun WifiScanScreen(onBack: () -> Unit, onNavigateToRouter: (String, String, Boolean) -> Unit) {
    val context = LocalContext.current
    val wifiManager = remember { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    val gatewayIp = remember { WifiUtils.getGatewayIp(context) }
    val subnetPrefix = remember { WifiUtils.getSubnetPrefix(context) }
    var rawDevices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var resolvedDevices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var kickedDevices by remember { mutableStateOf(setOf<String>()) }

    // Channel Analysis State
    var channelUsage by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var bestChannel by remember { mutableStateOf(1) }
    var currentChannel by remember { mutableStateOf(0) }

    // Initial Subnet Scan
    LaunchedEffect(Unit) {
        if (EgiNetwork.isAvailable()) {
            try {
                val jsonStr = withContext(Dispatchers.IO) {
                    EgiNetwork.scanSubnet(subnetPrefix)
                }
                val array = JSONArray(jsonStr)
                val list = mutableListOf<DeviceInfo>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(DeviceInfo(
                        ip = obj.getString("ip"), 
                        mac = obj.getString("mac"),
                        status = obj.getString("status")
                    ))
                }
                rawDevices = list
                resolvedDevices = list // Show IPs immediately
            } catch (t: Throwable) {
                // Native scan failed
            }
        }
        isScanning = false
    }

    // WiFi Channel Scan Loop
    LaunchedEffect(Unit) {
        while (true) {
            val results = wifiManager.scanResults
            if (results.isNotEmpty()) {
                channelUsage = WifiAnalyzer.getChannelUsage(results)
                bestChannel = WifiAnalyzer.getBestChannel(channelUsage)
                
                val freq = wifiManager.connectionInfo.frequency
                currentChannel = if (freq > 0) (freq - 2407) / 5 else 0
            }
            delay(5000)
        }
    }

    // Secondary Name Resolution
    LaunchedEffect(rawDevices) {
        if (rawDevices.isNotEmpty()) {
            resolvedDevices = resolveDeviceNames(rawDevices)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Header(isScanning, gatewayIp, onBack)

        Spacer(modifier = Modifier.height(16.dp))
        
        ChannelHealthCard(
            usage = channelUsage,
            bestChannel = bestChannel,
            currentChannel = currentChannel,
            onFix = { 
                val gatewayDevice = resolvedDevices.find { it.status == "Gateway" }
                onNavigateToRouter(gatewayDevice?.mac ?: "00:00:00:00:00:00", gatewayIp, true) 
            }
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(resolvedDevices) { device ->
                val isKicked = kickedDevices.contains(device.ip)
                DeviceRow(
                    device = device.copy(isKicked = isKicked),
                    onKick = {
                        if (EgiNetwork.isAvailable()) {
                            EgiNetwork.kickDevice(device.ip, device.mac)
                            kickedDevices = kickedDevices + device.ip
                            Toast.makeText(context, "BLACK HOLE ACTIVE: ${device.ip}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClick = {
                        if (device.status != "Gateway") {
                            selectedDevice = device
                        }
                    }
                )
            }
        }
    }

    selectedDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { selectedDevice = null },
            containerColor = Color.Black,
            title = {
                Text("HOSTILE DETECTED", color = Color.Red, fontFamily = FontFamily.Monospace)
            },
            text = {
                Text(
                    """Name: ${device.name}
Target: ${device.ip}
Action: Intercept and Redirect to Gateway for manual blacklisting.""",
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onNavigateToRouter(device.mac, gatewayIp, false)
                    selectedDevice = null
                }) {
                    Text("OPEN ROUTER DASHBOARD", color = Color.Red, fontFamily = FontFamily.Monospace)
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
fun ChannelHealthCard(usage: Map<Int, Int>, bestChannel: Int, currentChannel: Int, onFix: () -> Unit) {
    val ch1 = usage.getOrDefault(1, 0)
    val ch6 = usage.getOrDefault(6, 0)
    val ch11 = usage.getOrDefault(11, 0)
    val maxUsage = maxOf(ch1, ch6, ch11, 1)

    val isCrowded = usage.getOrDefault(currentChannel, 0) > 3 || (currentChannel != bestChannel && currentChannel in listOf(1, 6, 11))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.2f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Green.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "CHANNEL OPTIMIZER",
                color = Color.Green,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                // Bars
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                    UsageBar("1", ch1, maxUsage)
                    UsageBar("6", ch6, maxUsage)
                    UsageBar("11", ch11, maxUsage)
                }

                // Info
                Column(modifier = Modifier.weight(1.2f).padding(start = 16.dp)) {
                    Text(
                        text = "YOUR CH: ${if (currentChannel in 1..14) currentChannel else "???"}",
                        color = if (isCrowded) Color.Red else Color.Green,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (isCrowded) "(CROWDED!)" else "(OPTIMIZED)",
                        color = if (isCrowded) Color.Red else Color.Green,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "REC: CH $bestChannel (FAST)",
                        color = Color.Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onFix,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                shape = androidx.compose.ui.graphics.RectangleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text(
                    "[ FIX SLOW WIFI ]",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun UsageBar(label: String, count: Int, max: Int) {
    val height = (40 * (count.toFloat() / max)).coerceAtLeast(4f).dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(12.dp)
                .height(height)
                .background(if (count > 3) Color.Red else Color.Green)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    }
}


suspend fun resolveDeviceNames(devices: List<DeviceInfo>): List<DeviceInfo> = withContext(Dispatchers.IO) {
    devices.map { device ->
        try {
            val address = InetAddress.getByName(device.ip)
            val hostname = address.canonicalHostName
            if (hostname != device.ip) {
                device.copy(name = hostname)
            } else {
                device
            }
        } catch (e: Exception) {
            device
        }
    }
}

@Composable
fun Header(isScanning: Boolean, gateway: String, onBack: () -> Unit) {
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(isScanning) {
        while (isScanning) {
            delay(500)
            blink = !blink
        }
        blink = true
    }

    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "NETWORK RADAR ${if (isScanning && blink) "[SCANNING...]" else ""}",
                color = if (isScanning) Color.Yellow else Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "[ BACK ]",
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() }
            )
        }
        Text(
            text = "GATEWAY: $gateway",
            color = Color.Cyan,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp
        )
    }
}

@Composable
fun DeviceRow(device: DeviceInfo, onKick: () -> Unit, onClick: () -> Unit) {
    val icon = when {
        device.status == "Gateway" -> Icons.Default.Router
        device.name.contains("Android", true) || device.name.contains("Galaxy", true) || device.name.contains("Phone", true) -> Icons.Default.PhoneAndroid
        device.name.contains("PC", true) || device.name.contains("Desktop", true) || device.name.contains("Windows", true) -> Icons.Default.Monitor
        else -> Icons.Default.Info
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (device.isKicked) Color.Red else if (device.status == "Gateway") Color.Green else Color.Yellow,
            modifier = Modifier.size(32.dp).padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (device.status == "Gateway" && device.name == "Unknown Device") "Gateway (${device.ip})" else device.name,
                color = if (device.isKicked) Color.Red else Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "IP: ${device.ip} | MAC: ${device.mac}",
                color = if (device.isKicked) Color.Red.copy(alpha = 0.5f) else Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        
        if (device.status != "Gateway") {
            Text(
                text = if (device.isKicked) "[ BLOCKED ]" else "[ KICK ]",
                color = if (device.isKicked) Color.Red else Color.Cyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onKick() }
                    .padding(8.dp)
            )
        }
    }
}

data class DeviceInfo(
    val ip: String,
    val name: String = "Unknown Device",
    val status: String,
    val mac: String = "00:00:00:00:00:00",
    val isKicked: Boolean = false
)
