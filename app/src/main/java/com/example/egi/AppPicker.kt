package com.example.egi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.google.accompanist.drawablepainter.rememberDrawablePainter

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

@Composable
fun AppPickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("egi_prefs", Context.MODE_PRIVATE) }
    
    var blockedApps by remember { 
        mutableStateOf(sharedPrefs.getStringSet("kill_list", emptySet()) ?: emptySet()) 
    }
    
    val installedApps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { 
                AppInfo(
                    name = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(pm)
                )
            }
            .sortedBy { it.name }
    }

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
                    text = "EGI >> VIP_LANE",
                    color = Color.Cyan,
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
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("[ BACK ]", color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        // Sub-Header Tile
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(0.5.dp, Color.Green.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "AUTHORIZED APPS BYPASS THE SHIELD (0ms OVERHEAD)",
                color = Color.Green.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // List Grid
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(0.5.dp, Color.Green.copy(alpha = 0.3f))
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(installedApps) { app ->
                    MatrixAppRow(
                        app = app,
                        isBlocked = blockedApps.contains(app.packageName),
                        onToggle = { isChecked ->
                            val newList = blockedApps.toMutableSet()
                            if (isChecked) newList.add(app.packageName) else newList.remove(app.packageName)
                            blockedApps = newList
                            sharedPrefs.edit().putStringSet("kill_list", newList).apply()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MatrixAppRow(app: AppInfo, isBlocked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(65.dp)
            .border(0.2.dp, Color.Green.copy(alpha = 0.1f))
            .clickable { onToggle(!isBlocked) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painter = rememberDrawablePainter(drawable = app.icon), contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.name, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1)
            Text(text = app.packageName, color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1)
        }
        Text(
            text = if (isBlocked) "[ AUTHORIZED ]" else "[ SHIELDED ]",
            color = if (isBlocked) Color.Cyan else Color.Red.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

