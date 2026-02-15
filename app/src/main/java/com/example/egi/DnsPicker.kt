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
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EGI >> DNS_CONFIG",
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp
            )
            
            Text(
                text = "[ ABORT ]",
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable { onBack(null) }
                    .padding(8.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(dnsProviders) { provider ->
                DnsRow(
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
        
        Text(
            text = "CAUTION: System Default fixes Captive Portals.",
            color = Color.Yellow.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
fun DnsRow(provider: DnsProvider, isSelected: Boolean, onSelect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .background(if (isSelected) Color.DarkGray.copy(alpha = 0.3f) else Color.Transparent)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = provider.name,
                color = if (isSelected) Color.Cyan else Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            if (isSelected) {
                Text(
                    text = "[ SELECTED ]",
                    color = Color.Cyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
        }
        Text(
            text = if (provider.primary != null) "${provider.primary} | ${provider.secondary}" else "Use Network Settings",
            color = Color.Green.copy(alpha = 0.6f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}
