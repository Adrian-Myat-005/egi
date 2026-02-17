package com.example.egi

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class DnsProvider(
    val name: String,
    val primary: String?,
    val secondary: String? = null,
    val description: String
)

val dnsProviders = listOf(
    DnsProvider("Cloudflare (Speed)", "1.1.1.1", "1.0.0.1", "OPTIMIZING ROUTE... LATENCY MINIMIZED."),
    DnsProvider("Google (Reliability)", "8.8.8.8", "8.8.4.4", "GLOBAL UPLINK STABLE."),
    DnsProvider("AdGuard (Block Ads)", "94.140.14.14", "94.140.15.15", "AD-BLOCKER MATRIX LOADED."),
    DnsProvider("System Default", null, null, "PASSTHROUGH MODE ACTIVE. LOGIN TO WIFI NOW.")
)

@Composable
fun DnsPickerScreen(onBack: (String?) -> Unit) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("egi_prefs", Context.MODE_PRIVATE) }
    var selectedDns by remember { mutableStateOf(sharedPrefs.getString("dns_provider", null)) }

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
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "EGI >> DNS_CONFIG",
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, Color.Green.copy(alpha = 0.5f))
                    .clickable { onBack(null) },
                contentAlignment = Alignment.Center
            ) {
                Text("[ ABORT ]", color = Color.Red, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // DNS List
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(0.5.dp, Color.Green.copy(alpha = 0.3f))
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(dnsProviders) { provider ->
                    MatrixDnsRow(
                        provider = provider,
                        isSelected = selectedDns == provider.primary,
                        onSelect = {
                            selectedDns = provider.primary
                            sharedPrefs.edit().putString("dns_provider", provider.primary).apply()
                            onBack(provider.description)
                        }
                    )
                }
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(0.5.dp, Color.Yellow.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "CAUTION: System Default fixes Captive Portals.",
                color = Color.Yellow.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun MatrixDnsRow(provider: DnsProvider, isSelected: Boolean, onSelect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .border(0.2.dp, Color.Green.copy(alpha = 0.1f))
            .background(if (isSelected) Color.Cyan.copy(alpha = 0.05f) else Color.Transparent)
            .clickable { onSelect() }
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = provider.name,
                color = if (isSelected) Color.Cyan else Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            if (isSelected) {
                Text(
                    text = "[ ACTIVE ]",
                    color = Color.Cyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
        Text(
            text = if (provider.primary != null) "${provider.primary} | ${provider.secondary}" else "Use Network Settings",
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

