package com.example.egi

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.statusBarColor = Color.Black.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            EgiTerminalTheme {
                TerminalDashboard()
            }
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
fun TerminalDashboard() {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(listOf("EGI >> System init...", "EGI >> Loading network modules...")) }
    var isSecure by remember { mutableStateOf(false) }
    var isBooting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            context.startService(Intent(context, EgiVpnService::class.java))
            isSecure = true
            isBooting = false
            logs = logs + "EGI >> VPN TUNNEL ESTABLISHED [1.1.1.1]"
        } else {
            isBooting = false
            logs = logs + "EGI >> VPN PERMISSION DENIED"
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // Background Network Stats Loop
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            try {
                val statsJson = withContext(Dispatchers.IO) {
                    EgiNetwork.measureNetworkStats()
                }
                
                val newLog = "EGI >> STATS: $statsJson"
                logs = (logs + newLog).takeLast(100)
            } catch (e: Exception) {
                logs = (logs + "EGI >> ERROR: ${e.message}").takeLast(100)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(Color.Black)
    ) {
        Header(isSecure, isBooting)

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(logs) { log ->
                Text(
                    text = log,
                    color = if (log.contains("ERROR") || log.contains("DENIED")) Color.Red else Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable {
                    if (!isSecure && !isBooting) {
                        isBooting = true
                        logs = logs + "EGI >> REQUESTING KERNEL PERMISSIONS..."
                        val intent = VpnService.prepare(context)
                        if (intent != null) {
                            vpnLauncher.launch(intent)
                        } else {
                            context.startService(Intent(context, EgiVpnService::class.java))
                            isSecure = true
                            isBooting = false
                            logs = logs + "EGI >> VPN TUNNEL RE-ESTABLISHED"
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isSecure) "> [ SYSTEM_SECURED ] <" else "> [ EXECUTE_BOOST_PROTOCOL ] <",
                color = if (isSecure) Color.Cyan else Color.Green,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun Header(isSecure: Boolean, isBooting: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "root@egi:~#",
            color = Color.Green,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        BlinkingStatus(isSecure, isBooting)
    }
}

@Composable
fun BlinkingStatus(isSecure: Boolean, isBooting: Boolean) {
    var visible by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            visible = !visible
        }
    }

    val statusText = when {
        isBooting -> "[ BOOTING KERNEL... ]"
        isSecure -> "[ SECURE | 1.1.1.1 ]"
        else -> "[ UNSTABLE ]"
    }
    
    val statusColor = when {
        isBooting -> Color.Yellow
        isSecure -> Color.Cyan
        else -> Color.Red
    }

    if (visible) {
        Text(
            text = statusText,
            color = statusColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    } else {
        Text(
            text = statusText,
            color = Color.Transparent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
