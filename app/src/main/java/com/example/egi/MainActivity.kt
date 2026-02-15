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
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf(listOf("EGI >> System init...", "EGI >> Waiting for uplink...")) }
    var isSecure by remember { mutableStateOf(false) }
    var isBooting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Helper to type a message into logs
    fun addLog(msg: String) {
        logs = (logs + msg).takeLast(100)
    }

    suspend fun typeLog(msg: String) {
        var currentText = ""
        logs = logs + "" // Add empty slot
        val lastIndex = logs.size - 1
        for (char in msg) {
            currentText += char
            val newLogs = logs.toMutableList()
            newLogs[lastIndex] = currentText
            logs = newLogs
            delay(30)
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
                typeLog("EGI >> INITIALIZING BLACK HOLE...")
                typeLog("EGI >> TARGET: INSTAGRAM [BLOCKED]")
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

    // Background Network Stats Loop
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            try {
                val statsJson = withContext(Dispatchers.IO) {
                    EgiNetwork.measureNetworkStats()
                }
                addLog("EGI >> STATS: $statsJson")
            } catch (e: Exception) {
                // Ignore stats errors during boot
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
                    color = when {
                        log.contains("BLOCKED") -> Color.Red
                        log.contains("ACTIVE") -> Color.Cyan
                        log.contains("ERROR") -> Color.Red
                        else -> Color.Green
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Toggle Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
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
                                typeLog("EGI >> RE-INITIALIZING BLACK HOLE...")
                                typeLog("EGI >> PROTOCOL RE-ENGAGED.")
                            }
                        }
                    } else if (isSecure) {
                        // ABORT PROTOCOL
                        val stopIntent = Intent(context, EgiVpnService::class.java).apply {
                            action = EgiVpnService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                        isSecure = false
                        scope.launch {
                            typeLog("EGI >> ABORTING PROTOCOL...")
                            typeLog("EGI >> DISMANTLING BLACK HOLE...")
                            typeLog("EGI >> PROTOCOL DISENGAGED.")
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isSecure) "> [ ABORT PROTOCOL ] <" else "> [ EXECUTE BOOST ] <",
                color = if (isSecure) Color.Red else Color.Green,
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
        isBooting -> "[ BOOTING... ]"
        isSecure -> "[ FOCUS MODE ]"
        else -> "[ IDLE ]"
    }
    
    val statusColor = when {
        isBooting -> Color.Yellow
        isSecure -> Color.Cyan
        else -> Color.LightGray
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
