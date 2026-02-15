package com.example.egi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
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
fun WifiScanScreen(onBack: () -> Unit, onNavigateToRouter: (String, String) -> Unit) {
    val context = LocalContext.current
    val gatewayIp = remember { WifiUtils.getGatewayIp(context) }
    val subnetPrefix = remember { WifiUtils.getSubnetPrefix(context) }
    var rawDevices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var resolvedDevices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }

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

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(resolvedDevices) { device ->
                DeviceRow(device) {
                    if (device.status != "Gateway") {
                        selectedDevice = device
                    }
                }
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
                    onNavigateToRouter(device.mac, gatewayIp)
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
fun DeviceRow(device: DeviceInfo, onClick: () -> Unit) {
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
            tint = if (device.status == "Gateway") Color.Green else Color.Yellow,
            modifier = Modifier.size(32.dp).padding(end = 12.dp)
        )
        Column {
            Text(
                text = if (device.status == "Gateway" && device.name == "Unknown Device") "Gateway (${device.ip})" else device.name,
                color = if (device.status == "Gateway") Color.Green else Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "IP: ${device.ip} | MAC: ${device.mac}",
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}

data class DeviceInfo(
    val ip: String,
    val name: String = "Unknown Device",
    val status: String,
    val mac: String = "00:00:00:00:00:00"
)
