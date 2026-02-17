package com.example.egi

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RouterAdminScreen(gatewayIp: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val (user, pass, brand) = remember { EgiPreferences.getRouterCredentials(context) }
    var scrapedDevices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("INITIALIZING_SECURE_BRIDGE...") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Real-Time Polling Loop
    LaunchedEffect(webViewInstance) {
        while (true) {
            webViewInstance?.let { view ->
                statusMessage = "SYNCING_WITH_ROUTER..."
                val scraper = """
                    (function() {
                        const devices = [];
                        // 1. Target tables, lists, and specific known device classes
                        const selectors = 'tr, .device-row, .client-item, li, div.device, .client-list-item';
                        const rows = Array.from(document.querySelectorAll(selectors));
                        
                        rows.forEach(r => {
                            const text = r.innerText;
                            // Regex for IP and MAC
                            const ipMatch = text.match(/\d+\.\d+\.\d+\.\d+/);
                            const macMatch = text.match(/([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})/);
                            
                            if (ipMatch && ipMatch[0] !== '$gatewayIp' && ipMatch[0] !== '0.0.0.0') {
                                devices.push({ 
                                    i: ipMatch[0], 
                                    m: macMatch ? macMatch[0] : 'MAC_HIDDEN', 
                                    s: 'Online' 
                                });
                            }
                        });
                        
                        // 2. Backup: Look for any IP-like string in the whole body if list is empty
                        if (devices.length === 0) {
                            const bodyText = document.body.innerText;
                            const allIps = bodyText.match(/\d+\.\d+\.\d+\.\d+/g);
                            if (allIps) {
                                [...new Set(allIps)].forEach(ip => {
                                    if (ip !== '$gatewayIp' && !ip.startsWith('127.')) {
                                        devices.push({ i: ip, m: 'AUTO_DETECT', s: 'Active' });
                                    }
                                });
                            }
                        }
                        
                        return JSON.stringify(devices);
                    })()
                """.trimIndent()
                
                withContext(Dispatchers.Main) {
                    view.evaluateJavascript(scraper) { json ->
                        if (json != null && json != "null" && json != "[]") {
                            try {
                                val cleanJson = json.replace("\\\"", "\"").trim('"')
                                val array = JSONArray(cleanJson)
                                val list = mutableListOf<DeviceInfo>()
                                for (i in 0 until array.length()) {
                                    val obj = array.getJSONObject(i)
                                    val ip = obj.getString("i")
                                    list.add(DeviceInfo(
                                        ip = ip, 
                                        status = obj.getString("s"), 
                                        mac = obj.getString("m"),
                                        name = "Asset_" + ip.split(".").last()
                                    ))
                                }
                                if (list.isNotEmpty()) {
                                    scrapedDevices = list.distinctBy { it.ip }
                                    statusMessage = "BRIDGE_STABLE >> ${list.size}_DEVICES_SCANNED"
                                } else {
                                    statusMessage = "BRIDGE_IDLE >> SEEKING_DATA"
                                }
                            } catch(e: Exception) {
                                statusMessage = "SCRAPE_DATA_PARSE_ERROR"
                            }
                        } else {
                            statusMessage = "BRIDGE_CONNECTED >> SCANNING_PAGE..."
                        }
                    }
                }
            }
            delay(3000) // Faster polling
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Hidden Scraper WebView (Headless)
        AndroidView(
            factory = {
                WebView(it).apply {
                    webViewInstance = this
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            statusMessage = "AUTH_CHALLENGE >> INJECTING_CREDENTIALS"
                            val loginScript = """
                                (function() {
                                    const b = '$brand'; const u = '$user'; const p = '$pass';
                                    const inputs = document.querySelectorAll('input');
                                    
                                    // 1. Precise Brand Login
                                    if (b === 'TP-Link') {
                                        const userF = document.querySelector('#username') || document.querySelector('#login-username');
                                        const passF = document.querySelector('#password') || document.querySelector('#login-password');
                                        if (userF) userF.value = u;
                                        if (passF) passF.value = p;
                                        (document.querySelector('.login-btn') || document.querySelector('#login-btn'))?.click();
                                    } else if (b === 'Huawei') {
                                        if (document.querySelector('#txt_user')) document.querySelector('#txt_user').value = u;
                                        if (document.querySelector('#txt_pwd')) document.querySelector('#txt_pwd').value = p;
                                        document.querySelector('#btn_login')?.click();
                                    } else if (b === 'ASUS') {
                                        if (document.querySelector('#login_username')) document.querySelector('#login_username').value = u;
                                        if (document.querySelector('#login_passwd')) document.querySelector('#login_passwd').value = p;
                                        document.querySelector('.button')?.click();
                                    } else if (b === 'ZTE') {
                                        if (document.querySelector('#username')) document.querySelector('#username').value = u;
                                        if (document.querySelector('#password')) document.querySelector('#password').value = p;
                                        document.querySelector('#loginBtn')?.click();
                                    } else {
                                        // Generic Fallback
                                        const pField = Array.from(inputs).find(i => i.type==='password');
                                        if (pField) pField.value = p;
                                        const uField = Array.from(inputs).find(i => (i.type==='text' || i.id.includes('user')) && i !== pField);
                                        if (uField) uField.value = u;
                                        document.querySelector('button[type="submit"], input[type="submit"]')?.click();
                                    }

                                    // 2. Continuous Navigation Seeking
                                    const seek = () => {
                                        const navLinks = Array.from(document.querySelectorAll('a, span, li, button'));
                                        const target = navLinks.find(l => ['device', 'client', 'attached', 'status', 'host', 'lan'].some(k => l.innerText.toLowerCase().includes(k)));
                                        if (target && !window.location.href.includes('client')) target.click();
                                    };
                                    setInterval(seek, 3000);
                                })()
                            """.trimIndent()
                            view?.evaluateJavascript(loginScript, null)
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    loadUrl("http://$gatewayIp")
                }
            },
            modifier = Modifier.size(1.dp) // Headless
        )

        // 2. Native Matrix UI
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            // Header
            Row(Modifier.fillMaxWidth().height(50.dp).border(0.5.dp, Color.Green.copy(alpha = 0.5f))) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Text("ROUTER_BRIDGE >> $brand", color = Color.Cyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Box(Modifier.width(80.dp).fillMaxHeight().border(0.5.dp, Color.Green.copy(alpha = 0.5f)).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Text("[ CLOSE ]", color = Color.Red, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Status Console
            Box(Modifier.fillMaxWidth().height(30.dp).background(Color.DarkGray.copy(alpha = 0.1f)).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                Text(statusMessage, color = Color.Green, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }

            // Scraped Asset List
            Box(modifier = Modifier.weight(1f).fillMaxWidth().border(0.5.dp, Color.Green.copy(alpha = 0.3f))) {
                if (scrapedDevices.isEmpty()) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.Green, modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(16.dp))
                        Text("INTERROGATING_ROUTER...", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(scrapedDevices) { device ->
                            MatrixScrapedRow(device) {
                                statusMessage = "EXECUTING_REMOTE_BLOCK >> ${device.ip}"
                                val blockScript = """
                                    (function() {
                                        const targetIp = '${device.ip}';
                                        const rows = Array.from(document.querySelectorAll('tr, li, .device-row'));
                                        const targetRow = rows.find(r => r.innerText.includes(targetIp));
                                        if (targetRow) {
                                            const btn = targetRow.querySelector('button, input, .switch, .block');
                                            if (btn) { btn.click(); return "SIGNAL_EMITTED"; }
                                        }
                                        return "TARGET_NOT_FOUND_ON_PAGE";
                                    })()
                                """.trimIndent()
                                webViewInstance?.evaluateJavascript(blockScript) { res ->
                                    statusMessage = "BRIDGE_RESP >> " + res.uppercase()
                                }
                            }
                        }
                    }
                }
            }
            
            // Sub-Footer
            Box(Modifier.fillMaxWidth().height(40.dp).border(0.5.dp, Color.Green.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Text("CAUTION: BLOCKED DEVICES WILL BE DISCONNECTED IMMEDIATELY", color = Color.Red.copy(alpha = 0.6f), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun MatrixScrapedRow(device: DeviceInfo, onKick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(65.dp).border(0.2.dp, Color.Green.copy(alpha = 0.1f)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(device.ip, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("HW_ID: " + device.mac.take(17), color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        Box(
            Modifier.width(80.dp).height(35.dp).border(1.dp, Color.Red.copy(alpha = 0.5f)).clickable { onKick() },
            contentAlignment = Alignment.Center
        ) {
            Text("[ BLOCK ]", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}
