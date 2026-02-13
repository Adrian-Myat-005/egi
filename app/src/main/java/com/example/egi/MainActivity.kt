package com.example.egi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force status bar to black
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
    var logs by remember { mutableStateOf(listOf("EGI >> System init...", "EGI >> Loading network modules...")) }
    var isSecure by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when logs change
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // Fake network log loop
    LaunchedEffect(isSecure) {
        while (!isSecure) {
            delay(800)
            val pings = listOf(124, 142, 98, 210, 156)
            val jitter = listOf(12, 45, 8, 32)
            val newLog = if (Random().nextBoolean()) {
                "EGI >> PING: ${pings.random()}ms"
            } else {
                "EGI >> JITTER DETECTED: ${jitter.random()}ms"
            }
            logs = logs + newLog
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(Color.Black)
    ) {
        // Header
        Header(isSecure)

        Spacer(modifier = Modifier.height(16.dp))

        // Log Window
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(logs) { log ->
                Text(
                    text = log,
                    color = if (log.contains("JITTER") || log.contains("ALERT")) Color.Red else Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable {
                    logs = listOf("EGI >> Killing background processes...", "EGI >> Optimizing network... Success.")
                    isSecure = true
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "> [ EXECUTE_BOOST_PROTOCOL ] <",
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun Header(isSecure: Boolean) {
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

        BlinkingStatus(isSecure)
    }
}

@Composable
fun BlinkingStatus(isSecure: Boolean) {
    var visible by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            visible = !visible
        }
    }

    val statusText = if (isSecure) "[ SECURE ]" else "[ UNSTABLE ]"
    val statusColor = if (isSecure) Color.Green else Color.Red

    if (visible) {
        Text(
            text = statusText,
            color = statusColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    } else {
        // Maintain layout space even when blinking
        Text(
            text = statusText,
            color = Color.Transparent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
