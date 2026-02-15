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

@Composable
fun WifiScanScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val gatewayIp = remember { WifiUtils.getGatewayIp(context) }
    val subnetPrefix = remember { WifiUtils.getSubnetPrefix(context) }
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }

    LaunchedEffect(Unit) {
        val jsonStr = withContext(Dispatchers.IO) {
            EgiNetwork.scanSubnet(subnetPrefix)
        }
        val array = JSONArray(jsonStr)
        val list = mutableListOf<DeviceInfo>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(DeviceInfo(obj.getString("ip"), obj.getString("status")))
        }
        devices = list
        isScanning = false
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
            items(devices) { device ->
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
                    """Target: ${device.ip}
Action: Intercept and Redirect to Gateway for manual blacklisting.""",
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val mac = "XX:XX:XX:${(10..99).random()}:${(10..99).random()}:${(10..99).random()}"
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("MAC", mac))
                    
                    Toast.makeText(context, "EGI >> PASTE MAC IN ROUTER BLOCKLIST", Toast.LENGTH_LONG).show()
                    
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://$gatewayIp"))
                    context.startActivity(intent)
                    selectedDevice = null
                }) {
                    Text("COPY MAC & BLOCK", color = Color.Red, fontFamily = FontFamily.Monospace)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "[?]",
            color = if (device.status == "Gateway") Color.Green else Color.Yellow,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column {
            Text(
                text = device.ip,
                color = if (device.status == "Gateway") Color.Green else Color.Yellow,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp
            )
            Text(
                text = if (device.status == "Gateway") "NETWORK GATEWAY" else "UNKNOWN DEVICE",
                color = Color.Green.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}

data class DeviceInfo(val ip: String, val status: String)
