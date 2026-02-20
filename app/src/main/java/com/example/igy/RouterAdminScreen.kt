package com.example.igy

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
fun RouterAdminScreen(isDarkMode: Boolean, gatewayIp: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val (user, pass, brand) = remember { IgyPreferences.getRouterCredentials(context) }
    var scrapedDevices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("INITIALIZING_SECURE_BRIDGE...") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isManualMode by remember { mutableStateOf(false) }

    // Real-Time Polling Loop
    LaunchedEffect(webViewInstance) {
        while (true) {
            webViewInstance?.let { view ->
                val monitorScript = """
                    (function() {
                        const allDevices = [];
                        
                        const scrapeFromDoc = (doc) => {
                            const body = doc.body.innerText.toLowerCase();
                            
                            // 1. Check for Login Failures
                            if (body.includes('invalid') || body.includes('fail') || body.includes('wrong') || body.includes('incorrect')) {
                                return 'ERROR_AUTH_FAILED';
                            }
                            
                            // 2. Check if we are still on Login Page
                            const inputs = doc.querySelectorAll('input');
                            const passFields = Array.from(inputs).filter(i => i.type === 'password');
                            if (passFields.length > 0 && passFields.some(f => f.offsetWidth > 0)) {
                                return 'STATUS_WAITING_AUTH';
                            }

                            // 3. Scrape Devices
                            const selectors = 'tr, .device-row, .client-item, li, div.device, .client-list-item';
                            const rows = Array.from(doc.querySelectorAll(selectors));
                            
                            rows.forEach(r => {
                                const text = r.innerText;
                                const ipMatch = text.match(/\d+\.\d+\.\d+\.\d+/);
                                const macMatch = text.match(/([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})/);
                                
                                if (ipMatch && ipMatch[0] !== '$gatewayIp' && ipMatch[0] !== '0.0.0.0' && !ipMatch[0].startsWith('127.')) {
                                    allDevices.push({ 
                                        i: ipMatch[0], 
                                        m: macMatch ? macMatch[0] : 'MAC_HIDDEN', 
                                        s: 'Online' 
                                    });
                                }
                            });
                            
                            if (allDevices.length === 0) {
                                const allIps = body.match(/\d+\.\d+\.\d+\.\d+/g);
                                if (allIps) {
                                    [...new Set(allIps)].forEach(ip => {
                                        if (ip !== '$gatewayIp' && !ip.startsWith('127.')) {
                                            allDevices.push({ i: ip, m: 'AUTO_DETECT', s: 'Active' });
                                        }
                                    });
                                }
                            }
                            return null;
                        };

                        const runOnAllFrames = (doc) => {
                            const err = scrapeFromDoc(doc);
                            if (err) return err;
                            const frames = doc.querySelectorAll('iframe, frame');
                            for (let f of frames) {
                                try {
                                    const res = runOnAllFrames(f.contentDocument || f.contentWindow.document);
                                    if (res) return res;
                                } catch(e) {}
                            }
                            return null;
                        };

                        const globalErr = runOnAllFrames(document);
                        if (globalErr) return globalErr;

                        return JSON.stringify({
                            status: 'SUCCESS',
                            data: allDevices
                        });
                    })()
                """.trimIndent()
                
                withContext(Dispatchers.Main) {
                    view.evaluateJavascript(monitorScript) { res ->
                        val cleanRes = res?.trim('"') ?: ""
                        when {
                            cleanRes == "ERROR_AUTH_FAILED" -> {
                                statusMessage = "CRITICAL >> AUTH_FAILED: INVALID_CREDENTIALS"
                            }
                            cleanRes == "STATUS_WAITING_AUTH" -> {
                                statusMessage = "BRIDGE >> WAITING_FOR_ROUTER_HANDSHAKE..."
                            }
                            cleanRes.startsWith("{") -> {
                                try {
                                    val json = JSONObject(cleanRes.replace("\\\"", "\""))
                                    val array = json.getJSONArray("data")
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
                                        statusMessage = "BRIDGE_IDLE >> SEARCHING_INTERNAL_TABLES"
                                    }
                                } catch(e: Exception) {
                                    statusMessage = "BRIDGE >> DATA_SYNC_IN_PROGRESS..."
                                }
                            }
                            else -> {
                                statusMessage = "BRIDGE_CONNECTED >> SCANNING_NODES..."
                            }
                        }
                    }
                }
            }
            delay(3000)
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
                            statusMessage = "BRIDGE >> INJECTING_CREDENTIALS..."
                            val loginScript = """
                                (function() {
                                    const b = '$brand'; const u = '$user'; const p = '$pass';
                                    
                                    const tryLogin = (doc) => {
                                        const inputs = Array.from(doc.querySelectorAll('input'));
                                        const buttons = Array.from(doc.querySelectorAll('button, input[type="submit"], input[type="button"], a.login-btn, #loginBtn, .button'));
                                        
                                        let userField = null;
                                        let passField = doc.querySelector('input[type="password"]');

                                        if (b === 'TP-Link') {
                                            userField = doc.querySelector('#username') || doc.querySelector('#login-username');
                                            passField = doc.querySelector('#password') || doc.querySelector('#login-password');
                                        } else if (b === 'Huawei') {
                                            userField = doc.querySelector('#txt_user');
                                            passField = doc.querySelector('#txt_pwd');
                                        } else if (b === 'ASUS') {
                                            userField = doc.querySelector('#login_username');
                                            passField = doc.querySelector('#login_passwd');
                                        } else if (b === 'ZTE') {
                                            userField = doc.querySelector('#username');
                                            passField = doc.querySelector('#password');
                                        }

                                        if (!userField && passField) {
                                            userField = inputs.find(i => (i.type === 'text' || i.id.toLowerCase().includes('user') || i.name.toLowerCase().includes('user')) && i !== passField);
                                        }

                                        if (passField) {
                                            if (userField) { userField.value = u; userField.dispatchEvent(new Event('input', { bubbles: true })); }
                                            passField.value = p;
                                            passField.dispatchEvent(new Event('input', { bubbles: true }));
                                            
                                            setTimeout(() => {
                                                const loginBtn = buttons.find(btn => {
                                                    const t = (btn.innerText || btn.value || '').toLowerCase();
                                                    return t.includes('log') || t.includes('ok') || t.includes('sign') || t.includes('enter') || btn.id.toLowerCase().includes('login');
                                                });
                                                if (loginBtn) loginBtn.click();
                                                else if (passField.form) passField.form.submit();
                                            }, 500);
                                            return true;
                                        }
                                        return false;
                                    };

                                    const runOnAllFrames = (doc) => {
                                        if (tryLogin(doc)) return;
                                        const frames = doc.querySelectorAll('iframe, frame');
                                        for (let f of frames) {
                                            try { runOnAllFrames(f.contentDocument || f.contentWindow.document); } catch(e) {}
                                        }
                                    };

                                    // Periodic retry in case of slow JS rendering
                                    let attempts = 0;
                                    const interval = setInterval(() => {
                                        runOnAllFrames(document);
                                        if (++attempts > 5) clearInterval(interval);
                                    }, 2000);

                                    const seek = () => {
                                        const allDocs = [document];
                                        const getAllDocs = (doc) => {
                                            const frames = doc.querySelectorAll('iframe, frame');
                                            for (let f of frames) {
                                                try {
                                                    const d = f.contentDocument || f.contentWindow.document;
                                                    allDocs.push(d);
                                                    getAllDocs(d);
                                                } catch(e) {}
                                            }
                                        };
                                        getAllDocs(document);

                                        allDocs.forEach(doc => {
                                            const navLinks = Array.from(doc.querySelectorAll('a, span, li, button, div'));
                                            const target = navLinks.find(l => {
                                                const t = (l.innerText || '').toLowerCase();
                                                return ['device', 'client', 'attached', 'status', 'host', 'lan', 'wireless'].some(k => t.includes(k)) && 
                                                       !['log', 'help', 'reboot', 'map'].some(k => t.includes(k));
                                            });
                                            if (target && !window.location.href.includes('client')) {
                                                target.click();
                                            }
                                        });
                                    };
                                    setInterval(seek, 5000);
                                })()
                            """.trimIndent()
                            view?.evaluateJavascript(loginScript, null)
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                    loadUrl("http://$gatewayIp")
                }
            },
            modifier = Modifier.size(1.dp)
        )

        // 2. Native Matrix UI
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            // Header
            Row(Modifier.fillMaxWidth().height(50.dp).border(0.5.dp, Color.Green.copy(alpha = 0.5f))) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Text("ROUTER_BRIDGE >> $brand", color = Color.Cyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Box(Modifier.width(80.dp).fillMaxHeight().border(0.5.dp, Color.Green.copy(alpha = 0.5f)).clickable { isManualMode = !isManualMode }, contentAlignment = Alignment.Center) {
                    Text(if (isManualMode) "[ AUTO ]" else "[ MANUAL ]", color = Color.Yellow, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Box(Modifier.width(80.dp).fillMaxHeight().border(0.5.dp, Color.Green.copy(alpha = 0.5f)).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Text("[ CLOSE ]", color = Color.Red, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            if (isManualMode) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(cardBg)) {
                    AndroidView(
                        factory = { webViewInstance!! },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                // Status Console
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color.DarkGray.copy(alpha = 0.1f)).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Text(statusMessage, color = if(statusMessage.contains("FAILED")) Color.Red else Color.Green, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }

                // Scraped Asset List
                Box(modifier = Modifier.weight(1f).fillMaxWidth().border(0.5.dp, Color.Green.copy(alpha = 0.3f))) {
                    if (scrapedDevices.isEmpty()) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = if(statusMessage.contains("FAILED")) Color.Red else Color.Green, modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = if(statusMessage.contains("FAILED")) "AUTHENTICATION_REQUIRED" else "INTERROGATING_ROUTER...", 
                                color = if(statusMessage.contains("FAILED")) Color.Red else Color.Gray, 
                                fontSize = 11.sp, 
                                fontFamily = FontFamily.Monospace
                            )
                            if (statusMessage.contains("FAILED")) {
                                Text(
                                    "CHECK CREDENTIALS IN RADAR SETUP",
                                    color = Color.Red.copy(alpha = 0.6f),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
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
                                        statusMessage = "BRIDGE_RESP >> " + (res?.uppercase() ?: "NULL")
                                    }
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
