package com.example.egi

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RouterAdminScreen(targetMac: String, gatewayIp: String, onBack: () -> Unit) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. WebView (Background)
        AndroidView(
            factory = {
                WebView(it).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    loadUrl("http://$gatewayIp")
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Hacker Bar (Top Overlay)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TARGET MAC:",
                        color = Color.Red,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = targetMac,
                        color = Color.Green,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("MAC", targetMac))
                        Toast.makeText(context, "MAC COPIED TO CLIPBOARD", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("[ COPY ]", color = Color.Cyan, fontFamily = FontFamily.Monospace)
                    }

                    TextButton(onClick = { onBack() }) {
                        Text("[ X ]", color = Color.Red, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
