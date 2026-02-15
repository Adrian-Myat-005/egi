package com.example.egi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
            .padding(16.dp)
    ) {
        Text(
            text = "EGI >> TARGET_SELECTOR",
            color = Color.Green,
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(installedApps) { app ->
                AppRow(
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

        Text(
            text = "[ RETURN_TO_TERMINAL ]",
            color = Color.Green,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(top = 16.dp)
                .align(Alignment.CenterHorizontally)
                .background(Color.DarkGray.copy(alpha = 0.3f))
                .padding(8.dp)
                .clickable { onBack() }
        )
    }
}

@Composable
fun AppRow(app: AppInfo, isBlocked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Image(
                painter = rememberDrawablePainter(drawable = app.icon),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = app.name,
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
                Text(
                    text = app.packageName,
                    color = Color.Green.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }

        Switch(
            checked = isBlocked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Red,
                checkedTrackColor = Color.Red.copy(alpha = 0.5f),
                uncheckedThumbColor = Color.Green,
                uncheckedTrackColor = Color.Green.copy(alpha = 0.5f)
            )
        )
    }
}
